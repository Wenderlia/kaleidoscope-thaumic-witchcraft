package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.reservoir.*;
import dev.tc4port.thaumcraft.api.aspect.*;
import dev.tc4port.thaumcraft.api.essentia.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** TC public transactions only; no raw wand components, whitelist or Mixin. */
public final class ReservoirThaumcraft implements ReservoirBridge {
    private final ReservoirWandCharging charging=new ReservoirWandCharging();
    public static void registerCapabilities(net.neoforged.bus.api.IEventBus bus){
        bus.addListener((net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent e)->{
            e.registerBlockEntity(EssentiaCapabilities.TRANSPORT,ReservoirContent.ENTITY.get(),(entity,side)->new PlacedTransport(entity));
            e.registerBlockEntity(EssentiaCapabilities.STORAGE,ReservoirContent.ENTITY.get(),(entity,side)->new PlacedTransport(entity));
        });
    }
    public static void register(){
        ReservoirContent.bridge=new ReservoirThaumcraft();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(ReservoirInfusion::upgraded);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(ReservoirInfusion::start);
    }
    public boolean container(ItemStack stack){return EssentiaContainerApi.capacity(stack)>0;}
    private static AspectId id(String text){var value=ResourceLocation.tryParse(text);return value==null?null:AspectId.of(value);}
    public boolean emptyContainer(ItemStack stack){
        return container(stack) && EssentiaContainerApi.contents(stack).amounts().isEmpty();
    }
    public boolean fill(Level level,ItemStack bag,ItemStack single) {
        return fill(level,bag,single,Integer.MAX_VALUE);
    }
    private boolean fill(Level level,ItemStack bag,ItemStack single,int limit) {
        if(level.isClientSide || !ReservoirData.supported(bag) || single.getCount()!=1)return false;
        var contents=EssentiaContainerApi.contents(single).amounts();
        if(contents.size()!=1)return false;
        var entry=contents.entrySet().iterator().next();String aspect=entry.getKey().toString();
        int room=Math.min(limit,ReservoirData.room(bag,aspect));
        int quote=EssentiaContainerApi.extract(level,single,entry.getKey(),room,EssentiaTransferMode.SIMULATE);
        if(quote<=0)return false;
        int actual=EssentiaContainerApi.extract(level,single,entry.getKey(),quote,EssentiaTransferMode.EXECUTE);
        if(actual<=0)return false;
        ReservoirData.deposit(bag,aspect,actual);return true;
    }
    public ItemStack drainInPlace(Level level,ItemStack bag,ItemStack containers){
        // Public jar identity deliberately excludes phials. Never change stackability components.
        if(level.isClientSide || !ReservoirData.supported(bag) || !FilledJarEssentiaApi.isJar(containers)
                || containers.getCount()<1 || containers.getCount()>ReservoirData.PORT_LIMIT)return ItemStack.EMPTY;
        var contents=EssentiaContainerApi.contents(containers).amounts();
        if(contents.size()!=1)return ItemStack.EMPTY;
        int each=ReservoirData.room(bag,contents.keySet().iterator().next().toString())/containers.getCount();
        if(each<=0)return ItemStack.EMPTY;
        ItemStack result=ItemStack.EMPTY;
        // Host API executes on single carriers; equal budgets preserve an honestly identical stack.
        // All mutations stay in the caller's copies until every carrier has the same result.
        for(int i=0;i<containers.getCount();i++){
            ItemStack single=containers.copyWithCount(1);
            if(!fill(level,bag,single,each))return ItemStack.EMPTY;
            if(i==0)result=single;
            else if(!ItemStack.isSameItemSameComponents(result,single))return ItemStack.EMPTY;
        }
        return result.copyWithCount(containers.getCount());
    }
    public boolean extractToContainer(Level level,ItemStack bag,ItemStack single){
        if(level.isClientSide || !ReservoirData.supported(bag) || single.getCount()!=1 || !emptyContainer(single))return false;
        int cell=ReservoirData.selected(bag),available=ReservoirData.amount(bag,cell);
        AspectId aspect=id(ReservoirData.aspect(bag,cell));
        if(aspect==null || available<=0)return false;
        int quote=EssentiaContainerApi.insert(level,single,aspect,available,EssentiaTransferMode.SIMULATE);
        if(quote<=0)return false;
        int actual=EssentiaContainerApi.insert(level,single,aspect,quote,EssentiaTransferMode.EXECUTE);
        if(actual<=0)return false;
        ReservoirData.cell(bag,cell,aspect.toString(),available-actual);return true;
    }
    public boolean label(Level level,ItemStack bag,int cell,ItemStack stack) {
        if(level.isClientSide || !EssentiaContainerApi.isLabel(stack) || !ReservoirData.supported(bag))return false;
        var filter=EssentiaContainerApi.labelAspect(stack);
        String text=filter.map(AspectId::toString).orElse("");
        if(!text.isEmpty() && ReservoirData.amount(bag,cell)>0 && !text.equals(ReservoirData.aspect(bag,cell)))return false;
        ReservoirData.edit(bag,data->{var value=data.getCompound("cell_"+cell);value.putString("label",text);data.put("cell_"+cell,value);});
        return true;
    }
    public void charge(ServerPlayer player,ItemStack bag,int slot,ItemStack device) {
        charging.charge(player,bag,slot,device);
    }
    @Override public void tickPlaced(ReservoirBlockEntity entity){
        var level=entity.getLevel();if(level==null || level.isClientSide || !entity.available())return;
        var sink=new PlacedTransport(entity);
        for(var face:net.minecraft.core.Direction.values()){
            var otherFace=face.getOpposite();
            var source=EssentiaApi.transport(level,entity.getBlockPos().relative(face),otherFace);
            if(source==null || !source.canOutputTo(otherFace))continue;
            source=source.transferView();if(source==null)continue;
            int suction=sink.suctionAmount(face);
            if(suction<=source.suctionAmount(otherFace) || suction<source.minimumSuction())continue;
            AspectId aspect=source.extractableAspect(otherFace);
            if(aspect==null)aspect=source.essentiaType(otherFace);
            if(aspect==null || sink.addEssentia(aspect,1,face,EssentiaTransferMode.SIMULATE)==0)continue;
            int moved=EssentiaApi.take(level,source,aspect,1,otherFace,EssentiaTransferMode.EXECUTE);
            if(moved>0)sink.addEssentia(aspect,moved,face,EssentiaTransferMode.EXECUTE);
        }
    }
    /** Native capabilities expose the item-owned chambers; there is no second block inventory. */
    private record PlacedTransport(ReservoirBlockEntity entity) implements EssentiaTransport,EssentiaStorageView {
        private ItemStack bag(){return entity.bag();}
        public boolean isConnectable(net.minecraft.core.Direction face){return face!=null && entity.available();}
        public boolean canInputFrom(net.minecraft.core.Direction face){return isConnectable(face);}
        public boolean canOutputTo(net.minecraft.core.Direction face){return isConnectable(face);}
        public AspectId suctionType(net.minecraft.core.Direction face){
            if(!canInputFrom(face))return null;
            var bag=bag();var data=ReservoirData.read(bag);
            int cells=ReservoirData.cells(bag),capacity=ReservoirData.capacity(bag);AspectId single=null;
            for(int cell=0;cell<cells;cell++){
                int amount=ReservoirData.amount(data,cell);
                if(amount>=capacity)continue;
                String type=amount>0?ReservoirData.aspect(data,cell):ReservoirData.label(data,cell);
                AspectId candidate=id(type);if(candidate==null || single!=null && !single.equals(candidate))return null;
                single=candidate;
            }
            return single;
        }
        public int suctionAmount(net.minecraft.core.Direction face){
            if(!canInputFrom(face))return 0;
            var bag=bag();var data=ReservoirData.read(bag);
            int cells=ReservoirData.cells(bag),capacity=ReservoirData.capacity(bag);
            for(int cell=0;cell<cells;cell++)if(ReservoirData.amount(data,cell)<capacity)return 32;
            return 0;
        }
        public int minimumSuction(){return 0;}
        public boolean renderExtendedTube(){return false;}
        public AspectId essentiaType(net.minecraft.core.Direction face){return extractableAspect(face);}
        public int essentiaAmount(net.minecraft.core.Direction face){var aspect=extractableAspect(face);return aspect==null?0:availableEssentia(aspect,face);}
        public AspectId extractableAspect(net.minecraft.core.Direction face){
            if(!canOutputTo(face))return null;
            var bag=bag();var data=ReservoirData.read(bag);int cells=ReservoirData.cells(bag),selected=ReservoirData.selected(data,cells);
            for(int n=0;n<cells;n++){
                int cell=(selected+n)%cells;
                if(ReservoirData.amount(data,cell)>0){var aspect=id(ReservoirData.aspect(data,cell));if(aspect!=null)return aspect;}
            }
            return null;
        }
        public int availableEssentia(AspectId aspect,net.minecraft.core.Direction face){
            if(aspect==null || !canOutputTo(face))return 0;
            var bag=bag();var data=ReservoirData.read(bag);String type=aspect.toString();int cells=ReservoirData.cells(bag);
            long total=0;for(int cell=0;cell<cells;cell++)if(type.equals(ReservoirData.aspect(data,cell)))total+=ReservoirData.amount(data,cell);
            return (int)Math.min(Integer.MAX_VALUE,total);
        }
        public AspectAmounts visibleAspects(){
            var result=new java.util.LinkedHashMap<AspectId,Integer>();
            if(entity.available()){
                var bag=bag();var data=ReservoirData.read(bag);int cells=ReservoirData.cells(bag);
                for(int cell=0;cell<cells;cell++){
                    var aspect=id(ReservoirData.aspect(data,cell));int amount=ReservoirData.amount(data,cell);
                    if(aspect!=null && amount>0)result.merge(aspect,amount,(a,b)->(int)Math.min(Integer.MAX_VALUE,(long)a+b));
                }
            }
            return new AspectAmounts(result);
        }
        public int essentiaCapacity(){return entity.available()?(int)Math.min(Integer.MAX_VALUE,(long)ReservoirData.cells(bag())*ReservoirData.capacity(bag())):0;}
        public int addEssentia(AspectId aspect,int amount,net.minecraft.core.Direction face,EssentiaTransferMode mode){
            if(!canInputFrom(face) || aspect==null || amount<=0 || mode==null || mode.executes() && entity.getLevel().isClientSide || AspectApi.registry().get(aspect).isEmpty())return 0;
            int moved=Math.min(amount,ReservoirData.room(bag(),aspect.toString()));
            if(moved>0 && mode.executes()){ReservoirData.deposit(bag(),aspect.toString(),moved);entity.changed();}
            return moved;
        }
        public int takeEssentia(AspectId aspect,int amount,net.minecraft.core.Direction face,EssentiaTransferMode mode){
            if(!canOutputTo(face) || aspect==null || amount<=0 || mode==null || mode.executes() && entity.getLevel().isClientSide)return 0;
            int moved=Math.min(amount,availableEssentia(aspect,face));
            if(moved>0 && mode.executes()){
                moved=ReservoirData.withdraw(bag(),aspect.toString(),moved);
                if(moved>0)entity.changed();
            }
            return moved;
        }
    }
}
