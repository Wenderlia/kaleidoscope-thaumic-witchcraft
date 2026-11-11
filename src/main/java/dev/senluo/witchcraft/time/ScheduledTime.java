package dev.senluo.witchcraft.time;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.ticks.*;

/** Persisted delays preserve one native identity, original interval and remaining extra wait. */
public final class ScheduledTime extends SavedData {
    // Queue identity is a runtime binding, explicitly released on LevelEvent.Unload.
    private static final Map<Object,ScheduledTime> OWNERS=new IdentityHashMap<>();
    private static final int VERSION=1;
    private static final int LIMIT=65536;
    private record Key(BlockPos pos,Object type) {}
    private static final class Pending {
        final Key key; final int interval; final TickPriority priority;
        int remaining; int state; // 0 native queue, 1 extra delay, 2 released native queue
        Pending(Key key,int interval,TickPriority priority){this.key=key;this.interval=interval;this.priority=priority;remaining=interval;}
    }
    private final Map<Key,Pending> entries=new LinkedHashMap<>();
    // Native queues own states 0 and 2. Rotate their stale-entry checks independently of real delays.
    private final Set<Pending> active = new LinkedHashSet<>();
    private final LinkedHashSet<Pending> nativeChecks = new LinkedHashSet<>();
    private void track(Pending p) {
        if (entries.putIfAbsent(p.key,p) != null) return;
        if (p.state == 1) active.add(p); else nativeChecks.addLast(p);
    }
    private ServerLevel level;
    private CompoundTag unsupportedData;
    private boolean releasing;
    public static ScheduledTime bind(ServerLevel level) {
        ScheduledTime data=level.getDataStorage().computeIfAbsent(new Factory<>(ScheduledTime::new,ScheduledTime::load),"witchcraft_scheduled_time");
        data.level=level;OWNERS.put(level.getBlockTicks(),data);OWNERS.put(level.getFluidTicks(),data);return data;
    }
    public static void unbind(ServerLevel level){
        OWNERS.values().removeIf(data -> data.level==level);
    }
    public static boolean alreadyDeferred(Object queue,ScheduledTick<?> tick) {
        ScheduledTime data=OWNERS.get(queue);if(data==null||data.releasing)return false;
        Pending p=data.entries.get(new Key(tick.pos(),tick.type()));return p!=null&&p.state==1;
    }
    public static void accepted(Object queue,ScheduledTick<?> tick) {
        ScheduledTime data=OWNERS.get(queue);if(data==null||data.releasing||data.unsupportedData!=null)return;
        Object type=tick.type();int category=type instanceof Fluid?2:3;
        if(!supported(type)||data.entries.size()>=LIMIT||TemporalWorld.get(data.level).receiver(tick.pos(),category)==null)return;
        long interval=tick.triggerTick()-data.level.getGameTime();
        if(interval<=0||interval>Integer.MAX_VALUE)return;
        Key key=new Key(tick.pos().immutable(),type);
        if(!data.entries.containsKey(key)){data.track(new Pending(key,(int)interval,tick.priority()));data.setDirty();}
    }
    private static boolean supported(Object type) {
        return type instanceof RepeaterBlock||type instanceof ComparatorBlock||type instanceof ObserverBlock||type instanceof ButtonBlock
                ||type instanceof FireBlock||type instanceof FrostedIceBlock
                ||type instanceof net.minecraft.world.level.material.WaterFluid||type instanceof net.minecraft.world.level.material.LavaFluid;
    }
    public int interval(BlockPos pos,Fluid fluid) {
        Pending p=entries.get(new Key(pos,fluid));return p==null?fluid.getTickDelay(level):p.interval;
    }
    public boolean intercept(BlockPos pos,Object type) {
        Key key=new Key(pos,type);Pending p=entries.get(key);
        if(p==null)return false;
        if(!matches(p)||p.state==2){entries.remove(key);active.remove(p);nativeChecks.remove(p);setDirty();return false;}
        if(p.state==1)return true;
        int source=type instanceof Fluid?2:3;
        LivingEntity receiver=TemporalWorld.get(level).receiver(pos,source);
        if(receiver==null||source==2&&!FluidTime.get(level).capture(pos,p.interval)) {entries.remove(key);active.remove(p);nativeChecks.remove(p);setDirty();return false;}
        nativeChecks.remove(p);p.state=1;active.add(p);setDirty();return true;
    }
    private boolean matches(Pending p) {
        return p.key.type instanceof Fluid f?level.getFluidState(p.key.pos).is(f):level.getBlockState(p.key.pos).is((Block)p.key.type);
    }
    private boolean queued(Pending p) {
        return p.key.type instanceof Fluid f?level.getFluidTicks().hasScheduledTick(p.key.pos,f):level.getBlockTicks().hasScheduledTick(p.key.pos,(Block)p.key.type);
    }
    public void tick() {
        if(entries.isEmpty()){active.clear();nativeChecks.clear();return;}
        List<Pending> release=new ArrayList<>();
        for(int i=0,n=Math.min(128,nativeChecks.size());i<n;i++){
            Pending p=nativeChecks.removeFirst();
            if(entries.get(p.key)!=p || p.state==1)continue;
            if(level.hasChunkAt(p.key.pos) && level.shouldTickBlocksAt(p.key.pos)){
                if(!matches(p) || p.state==0 && !queued(p)){entries.remove(p.key);setDirty();continue;}
                if(p.state==2 && !queued(p))release.add(p);
            }
            nativeChecks.addLast(p);
        }
        var it=active.iterator();
        while(it.hasNext()) {
            Pending p=it.next();if(!level.hasChunkAt(p.key.pos)||!level.shouldTickBlocksAt(p.key.pos))continue;
            if(entries.get(p.key)!=p){it.remove();continue;}
            if(!matches(p)){it.remove();entries.remove(p.key);setDirty();continue;}
            int source=p.key.type instanceof Fluid?2:3;
            TemporalWorld world=TemporalWorld.get(level);LivingEntity receiver=world.receiver(p.key.pos,source);
            boolean keep=receiver!=null;
            if(keep&&source==2)keep=FluidTime.get(level).capture(p.key.pos,p.interval);
            if(keep&&source==3)keep=world.credit(receiver,3,SourceIncomeRates.SCHEDULED_OPPORTUNITY/p.interval);
            if(!keep||--p.remaining<=0){p.state=2;it.remove();nativeChecks.addLast(p);release.add(p);}
            setDirty();
        }
        releasing=true;
        try{for(Pending p:release){
            if(p.key.type instanceof Fluid f)level.scheduleTick(p.key.pos,f,1,p.priority);
            else level.scheduleTick(p.key.pos,(Block)p.key.type,1,p.priority);
        }}finally{releasing=false;}
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        // A downgraded build cannot interpret future work, but must not erase it on save.
        if(unsupportedData!=null)return unsupportedData.copy();
        ListTag list=new ListTag();
        for(Pending p:entries.values()) {
            CompoundTag t=new CompoundTag();boolean fluid=p.key.type instanceof Fluid;
            t.putLong("Pos",p.key.pos.asLong());t.putBoolean("Fluid",fluid);
            t.putString("Type",(fluid?BuiltInRegistries.FLUID.getKey((Fluid)p.key.type):BuiltInRegistries.BLOCK.getKey((Block)p.key.type)).toString());
            t.putInt("Interval",p.interval);t.putInt("Remaining",p.remaining);t.putInt("State",p.state);t.putInt("Priority",p.priority.getValue());list.add(t);
        }
        tag.putInt("Version",VERSION);tag.put("Pending",list);return tag;
    }
    static ScheduledTime load(CompoundTag tag,HolderLookup.Provider registries) {
        ScheduledTime data=new ScheduledTime();
        int version=tag.getInt("Version");
        if(version!=VERSION||!tag.contains("Version",Tag.TAG_INT)){
            data.unsupportedData=tag.copy();
            com.mojang.logging.LogUtils.getLogger().warn("Unsupported scheduled-time data version {}; preserving data without applying delays", tag.get("Version"));
            return data;
        }
        ListTag list=tag.getList("Pending",Tag.TAG_COMPOUND);
        for(int i=0;i<Math.min(LIMIT,list.size());i++) {
            CompoundTag t=list.getCompound(i);ResourceLocation id=ResourceLocation.tryParse(t.getString("Type"));if(id==null)continue;
            Object type=t.getBoolean("Fluid")?BuiltInRegistries.FLUID.getOptional(id).orElse(null):BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
            int interval=t.getInt("Interval");if(type==null||!supported(type)||interval<=0)continue;
            Pending p=new Pending(new Key(BlockPos.of(t.getLong("Pos")),type),interval,TickPriority.byValue(t.getInt("Priority")));
            p.remaining=Math.max(0,Math.min(interval,t.getInt("Remaining")));p.state=Math.max(0,Math.min(2,t.getInt("State")));
            data.track(p);
        }
        return data;
    }
}
