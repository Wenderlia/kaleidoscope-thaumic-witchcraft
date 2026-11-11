package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.reservoir.*;
import dev.tc4port.thaumcraft.api.aspect.*;
import dev.tc4port.thaumcraft.api.wand.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Installed converter policy. Wand inspection and mutation remain owned by TC's public API. */
final class ReservoirWandCharging {
    private static final long MICROVIS_PER_CENTIVIS=10_000;
    private static final VisChannel[] CHANNELS=VisChannel.values();
    // Mixed grades keep separate rule caches; alternating slots must not evict each other.
    private final Map<ConversionKey,ReservoirConversion> conversions=new HashMap<>();
    private record ConversionKey(ReservoirProfile host,ReservoirTransducerItem.Grade grade) {}
    private final Map<ServerPlayer,Schedule[]> schedules=new WeakHashMap<>();
    private static final class Schedule {
        ItemStack bag,device=ItemStack.EMPTY;long seen,source,output;
    }
    private record Source(int cell,String aspect,long[] quote) {}
    private static final class Target {
        final int slot;
        final ItemStack stack;
        final int[] room,request=new int[6];
        int channel;
        Target(int slot,ItemStack stack,int[] room,int channel){
            this.slot=slot;this.stack=stack;this.room=room;this.channel=channel;
        }
    }

    void charge(ServerPlayer player,ItemStack bag,int slot,ItemStack device) {
        int index=slot-ReservoirData.FIRST_PLUGIN_SLOT;
        if(index<0 || index>=ReservoirProfile.of(bag).plugins || !ReservoirData.supported(bag)
                || device.getCount()!=1 || !(device.getItem() instanceof ReservoirTransducerItem)
                || !ReservoirData.supported(device))return;
        long now=player.serverLevel().getGameTime();
        var rate=((ReservoirTransducerItem)device.getItem()).throughput();
        Schedule[] clocks=schedules.computeIfAbsent(player,p->new Schedule[ReservoirProfile.SILVERWOOD.plugins]);
        Schedule clock=clocks[index];if(clock==null)clocks[index]=clock=new Schedule();
        if(clock.bag!=bag || now<clock.seen || now-clock.seen>1 || !ItemStack.matches(clock.device,device)){
            clock.bag=bag;clock.source=now+rate.sourceTicks();clock.output=now+rate.deliveryTicks();
        }
        clock.seen=now;
        try{
            boolean output=now>=clock.output || now>=clock.source;
            chargeOnce(player,bag,slot,device,clock,rate,now);
            if(output)vent(player,bag,slot,device);
        }
        finally{clock.device=device.copy();}
    }

    private void chargeOnce(ServerPlayer player,ItemStack bag,int slot,ItemStack device,Schedule clock,
                            ReservoirTransducerItem.Throughput rate,long now) {
        boolean source=now>=clock.source,output=now>=clock.output;
        if(!source && !output)return;
        if(source)clock.source=now+rate.sourceTicks();
        if(output)clock.output=now+rate.deliveryTicks();
        if(!installed(player,bag,slot,device))return;
        // Inventory discovery occurs only on a due pulse. No stored ItemStack targets or idle writes.
        long[] balances=balances(device);
        List<Source> sources=sources(bag,device);
        List<Target> targets=targets(player,device,balances,sources);
        if(targets.isEmpty())return;
        // Flush paid balances first, making space before conversion. Delivery is not rate-limited.
        // A conversion pulse also flushes, even when configured clocks do not align.
        boolean changed=flush(player,bag,slot,device,targets,now);
        if(changed)targets=targets(player,device,balances(device),sources);
        if(source && installed(player,bag,slot,device)
                && convert(bag,device,targets,sources,balances(device))){
            // Persist the paid batch before calling the host, including when output is not due.
            if(!ItemStack.matches(device,ReservoirData.slot(bag,slot,player.registryAccess())))
                ReservoirData.slot(bag,slot,device,player.registryAccess());
            // Refresh room after the first transfer, then deliver this batch in the same game tick.
            try{
                targets=targets(player,device,balances(device),sources);
                changed|=flush(player,bag,slot,device,targets,now);
            }finally{
                if(installed(player,bag,slot,device)){
                    retainBuffer(device,rate.bufferMicrovis());
                    ReservoirData.slot(bag,slot,device,player.registryAccess());
                    player.getInventory().setChanged();
                }
            }
        }
        if(changed && installed(player,bag,slot,device)){
            var rules=ReservoirProfile.of(bag).conversion(((ReservoirTransducerItem)device.getItem()).rules());
            if(rules.emitsGas()){
                ReservoirEmission.charged(device,rules.ventEvery());
                ReservoirData.slot(bag,slot,device,player.registryAccess());
            }
        }
    }

    private static boolean flush(ServerPlayer player,ItemStack bag,int slot,ItemStack device,List<Target> targets,long now){
        if(targets.isEmpty() || !installed(player,bag,slot,device))return false;
        Target last=allocate(targets,balances(device));
        return last!=null && deliver(player,bag,slot,device,targets,last,now);
    }

    private static long[] balances(ItemStack device){
        var data=ReservoirData.read(device);long[] result=new long[6];
        for(int c=0;c<6;c++)result[c]=Math.max(0,data.getLong("vis_"+c));
        return result;
    }

    private List<Source> sources(ItemStack bag,ItemStack device){
        var item=(ReservoirTransducerItem)device.getItem();var host=ReservoirProfile.of(bag);var rules=host.conversion(item.rules());
        var conversion=conversions.computeIfAbsent(new ConversionKey(host,item.grade()),unused->new ReservoirConversion());
        int cursor=Math.floorMod(ReservoirData.read(device).getInt("source_cursor"),ReservoirData.cells(bag));
        var data=ReservoirData.read(bag);List<Source> result=new ArrayList<>(ReservoirData.cells(bag));
        for(int n=0;n<ReservoirData.cells(bag);n++){
            int cell=(cursor+n)%ReservoirData.cells(bag);var stored=data.getCompound("cell_"+cell);
            if(stored.getInt("amount")<=0)continue;
            String aspect=stored.getString("aspect");var location=ResourceLocation.tryParse(aspect);
            if(location!=null)conversion.quote(AspectId.of(location),rules.primalMicrovis(),rules.retainedBasisPoints()).ifPresent(q->result.add(new Source(cell,aspect,q)));
        }
        return result;
    }

    private static List<Target> targets(ServerPlayer player,ItemStack device,long[] balances,List<Source> sources){
        var inventory=player.getInventory();var data=ReservoirData.read(device);
        int channel=Math.floorMod(data.getInt("output_cursor"),6);
        // Preserve the existing concentrated priority: main hand, then offhand.
        Target main=inspect(inventory,inventory.selected,channel,balances,sources);
        if(main!=null)return List.of(main);
        Target off=inspect(inventory,Inventory.SLOT_OFFHAND,channel,balances,sources);
        if(off!=null)return List.of(off);
        int cursor=Math.floorMod(data.getInt("target_cursor"),Inventory.INVENTORY_SIZE);
        List<Target> result=new ArrayList<>();
        Set<ItemStack> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(int n=0;n<Inventory.INVENTORY_SIZE;n++){
            int slot=(cursor+n)%Inventory.INVENTORY_SIZE;
            if(slot==inventory.selected || !seen.add(inventory.getItem(slot)))continue;
            Target target=inspect(inventory,slot,channel,balances,sources);
            if(target!=null)result.add(target);
        }
        return result;
    }

    private static Target inspect(Inventory inventory,int slot,int channel,long[] balances,List<Source> sources){
        if(slot<0 || slot>=inventory.getContainerSize())return null;
        ItemStack stack=inventory.getItem(slot);if(stack.isEmpty())return null;
        var view=WandApi.view(stack);
        if(view.isEmpty())return null;
        int[] room=room(view.get());boolean useful=false;
        for(int c=0;c<6;c++)if(room[c]>0){
            if(balances[c]>=MICROVIS_PER_CENTIVIS){useful=true;break;}
            if((long)room[c]*MICROVIS_PER_CENTIVIS>balances[c])
                for(Source source:sources)if(source.quote[c]>0){useful=true;break;}
        }
        return useful?new Target(slot,stack,room,channel):null;
    }

    private static int[] room(WandView view){
        int[] result=new int[6];
        for(var c:CHANNELS)result[c.index()]=(int)Math.clamp(
                (long)view.capacityCentivis().getOrDefault(c,0)-view.visCentivis().getOrDefault(c,0),0,Integer.MAX_VALUE);
        return result;
    }

    private static boolean convert(ItemStack bag,ItemStack device,List<Target> targets,List<Source> sources,long[] balances){
        long[] need=new long[6];
        for(Target target:targets)for(int c=0;c<6;c++)need[c]+=(long)target.room[c]*MICROVIS_PER_CENTIVIS;
        for(int c=0;c<6;c++)need[c]=Math.max(0,need[c]-balances[c]);
        // Delivery precedes overflow: surplus in five channels must not block the sixth.
        for(int pass=0;pass<2;pass++)for(Source source:sources){
            boolean any=false,all=true;
            for(int c=0;c<6;c++)if(source.quote[c]>0){
                any|=need[c]>0;all&=need[c]>=source.quote[c];
            }
            if(!any || (pass==0 && !all))continue;
            ReservoirData.cell(bag,source.cell,source.aspect,ReservoirData.amount(bag,source.cell)-1);
            ReservoirData.credit(device,source.quote);
            ReservoirData.edit(device,d->d.putInt("source_cursor",(source.cell+1)%ReservoirData.cells(bag)));
            return true;
        }
        return false;
    }

    private static void retainBuffer(ItemStack device,long capacity){
        long[] stock=balances(device),kept=new long[6];
        if(Arrays.stream(stock).sum()<=capacity)return;
        // Preserve small balances (including sub-centivis tails); share the rest among larger ones.
        boolean[] settled=new boolean[6];long free=capacity;int count=6;
        while(count>0){
            long share=free/count;boolean found=false;
            for(int c=0;c<6;c++)if(!settled[c] && stock[c]<=share){
                kept[c]=stock[c];free-=stock[c];settled[c]=true;count--;found=true;
            }
            if(found)continue;
            int start=Math.floorMod(ReservoirData.read(device).getInt("output_cursor"),6);
            for(int n=0;n<6;n++){
                int c=(start+n)%6;if(settled[c])continue;
                kept[c]=free/count;free-=kept[c];count--;
            }
        }
        ReservoirData.edit(device,data->{for(int c=0;c<6;c++)data.putLong("vis_"+c,kept[c]);});
    }

    private static Target allocate(List<Target> targets,long[] balances){
        long[] available=balances.clone();
        for(int c=0;c<6;c++)available[c]/=MICROVIS_PER_CENTIVIS;
        var queue=new ArrayDeque<>(targets);Target last=null;
        // Share by wand first, then by channel. Whole quotas avoid one loop per centivis.
        // A short grant exhausts this target's eligible channels; otherwise a round spends
        // its equal quota. The only one-centivis remainder round is bounded by target count.
        long left=Arrays.stream(available).sum();
        while(left>0 && !queue.isEmpty()){
            long share=Math.max(1,left/queue.size());int size=queue.size();
            for(int i=0;i<size && left>0;i++){
                Target target=queue.removeFirst();long grant=allocateTo(target,available,Math.min(share,left));
                if(grant>0){left-=grant;last=target;}
                if(grant==share)queue.addLast(target);
            }
        }
        return last;
    }

    private static long allocateTo(Target target,long[] available,long quota){
        long left=quota;
        while(left>0){
            int count=0;
            for(int c=0;c<6;c++)if(available[c]>0 && target.request[c]<target.room[c])count++;
            if(count==0)break;
            long share=Math.max(1,left/count);int start=target.channel;
            for(int n=0;n<6 && left>0;n++){
                int c=(start+n)%6;
                int amount=(int)Math.min(Math.min(share,left),Math.min(available[c],target.room[c]-target.request[c]));
                if(amount<=0)continue;
                target.request[c]+=amount;available[c]-=amount;left-=amount;target.channel=(c+1)%6;
            }
        }
        return quota-left;
    }

    private static boolean installed(ServerPlayer player,ItemStack bag,int slot,ItemStack device){
        return player.isAlive() && !player.isSpectator() && ReservoirContent.worn(player)==bag
                && ReservoirData.supported(bag)
                && ItemStack.matches(device,ReservoirData.slot(bag,slot,player.registryAccess()));
    }

    private static boolean deliver(ServerPlayer player,ItemStack bag,int slot,ItemStack device,List<Target> targets,Target last,long now){
        long[] moved=new long[6];boolean changed=false;
        for(Target target:targets){
            boolean requested=false;for(int amount:target.request)requested|=amount>0;
            if(!requested)continue;
            if(!installed(player,bag,slot,device))break;
            if(player.getInventory().getItem(target.slot)!=target.stack)continue;
            var view=WandApi.view(target.stack);if(view.isEmpty())continue;
            int[] room=room(view.get());long[] balances=balances(device);
            var request=new EnumMap<VisChannel,Integer>(VisChannel.class);
            for(var c:CHANNELS){
                int amount=(int)Math.min(Math.min(target.request[c.index()],room[c.index()]),balances[c.index()]/MICROVIS_PER_CENTIVIS);
                if(amount>0)request.put(c,amount);
            }
            if(request.isEmpty())continue;
            // 20754 settles the source after all target processors validate and before their committed callbacks.
            var transferred=WandApi.insert(player,target.stack,VisCost.ofCentivis(request),VisAction.EXECUTE, plan -> {
                if(!installed(player,bag,slot,device) || player.getInventory().getItem(target.slot)!=target.stack)return false;
                long[] fresh=balances(device);
                for(var c:CHANNELS)if((long)plan.settled(c)*MICROVIS_PER_CENTIVIS>fresh[c.index()])return false;
                for(var c:CHANNELS)if(plan.settled(c)>0)ReservoirData.debit(device,c.index(),(long)plan.settled(c)*MICROVIS_PER_CENTIVIS);
                ReservoirData.slot(bag,slot,device,player.registryAccess());
                player.getInventory().setChanged();
                return true;
            });
            if(!transferred.mutated())continue;
            for(var c:CHANNELS)moved[c.index()]+=transferred.transferred(c);
            changed=true;
            // A committed observer may replace an item. Do not write an old device snapshot over it.
        }
        if(!changed || !installed(player,bag,slot,device))return changed;
        // Resume after the last allocation turn, not the last batched API call: otherwise
        // the same first slot would receive every indivisible remainder on successive pulses.
        int targetCursor=(last.slot+1)%Inventory.INVENTORY_SIZE,channelCursor=last.channel;
        long red=0,green=0,blue=0,total=0;
        for(var c:CHANNELS){
            long amount=moved[c.index()];if(amount<=0)continue;
            int color=AspectApi.registry().get(c.aspectId()).map(AspectDefinition::color).orElse(0xFFFFFF);
            total+=amount;red+=(color>>16&255)*amount;green+=(color>>8&255)*amount;blue+=(color&255)*amount;
        }
        int color=(int)(red/total)<<16|(int)(green/total)<<8|(int)(blue/total);
        ReservoirData.edit(device,d->{d.putInt("target_cursor",targetCursor);d.putInt("output_cursor",channelCursor);
            d.putInt("light_color",color);d.putLong("light_tick",now);});
        ReservoirData.slot(bag,slot,device,player.registryAccess());
        player.getInventory().setChanged();
        return true;
    }

    private static void vent(ServerPlayer player,ItemStack bag,int slot,ItemStack device){
        if(!installed(player,bag,slot,device) || !ReservoirEmission.pending(device))return;
        var outlet=ReservoirFluxVenting.outlet(player);if(outlet.isEmpty())return;
        // Publish the reservation before the host's world callbacks. Never overwrite a changed slot.
        ReservoirEmission.reserve(device);ReservoirData.slot(bag,slot,device,player.registryAccess());
        player.getInventory().setChanged();
        boolean emitted=false;
        try{emitted=ReservoirFluxVenting.emit(player,outlet.get());}
        finally{
            if(!emitted && installed(player,bag,slot,device)){
                ReservoirEmission.refund(device);ReservoirData.slot(bag,slot,device,player.registryAccess());
                player.getInventory().setChanged();
            }
        }
    }
}
