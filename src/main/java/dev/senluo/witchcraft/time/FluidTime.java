package dev.senluo.witchcraft.time;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.WaterFluid;
import net.minecraft.world.level.material.LavaFluid;
import net.minecraft.world.phys.Vec3;

/** Conservative first-surface ledger: short straight, single-source channels only. */
public final class FluidTime {
    private static final Map<ServerLevel,FluidTime> WORLDS=new IdentityHashMap<>();
    private record Channel(BlockPos source,BlockPos surface,Direction direction,List<BlockPos> path,List<FluidState> states) {}
    private final ServerLevel level;
    private final Map<BlockPos,Channel> channels=new LinkedHashMap<>();
    private final Map<BlockPos,BlockPos> sourcesByCell=new HashMap<>();
    private final Map<Channel,Boolean> validity=new IdentityHashMap<>();
    private long validityTick=Long.MIN_VALUE;
    public static FluidTime get(ServerLevel level){return WORLDS.computeIfAbsent(level,FluidTime::new);}
    public static void unload(ServerLevel level){WORLDS.remove(level);}
    public static void changed(ServerLevel level){FluidTime known=WORLDS.get(level);if(known!=null)known.validity.clear();}
    private FluidTime(ServerLevel level){this.level=level;}

    private Channel trace(BlockPos sample) {
        if(!level.hasChunkAt(sample))return null;
        FluidState state=level.getFluidState(sample);
        if(!(state.getType() instanceof WaterFluid)&&!(state.getType() instanceof LavaFluid))return null;
        Vec3 flow=state.getFlow(level,sample);
        if(Math.abs(flow.y)>.1||flow.horizontalDistanceSqr()<1E-8)return null;
        // Turns, pools, vertical falls and ambiguous branches deliberately remain vanilla.
        Direction direction=Math.abs(flow.x)>Math.abs(flow.z)?(flow.x>0?Direction.EAST:Direction.WEST):(flow.z>0?Direction.SOUTH:Direction.NORTH);
        List<BlockPos> path=new ArrayList<>();List<FluidState> states=new ArrayList<>();
        BlockPos p=sample.immutable();
        for(int steps=0;steps<32;steps++) {
            if(!level.hasChunkAt(p)||!level.shouldTickBlocksAt(p))return null;
            FluidState current=level.getFluidState(p);
            if(!current.getType().isSame(state.getType()))return null;
            Vec3 f=current.getFlow(level,p);
            if(Math.abs(f.y)>.1||f.dot(new Vec3(direction.getStepX(),0,direction.getStepZ()))<=0)return null;
            for(Direction side:List.of(direction.getClockWise(),direction.getCounterClockWise())) {
                BlockPos neighbor=p.relative(side);if(!level.hasChunkAt(neighbor)||level.getFluidState(neighbor).getType().isSame(state.getType()))return null;
            }
            path.add(p);states.add(current);
            if(current.isSource()) {
                BlockPos surface=null;
                for(int i=path.size()-1;i>=0;i--)if(TemporalWorld.get(level).receiver(path.get(i),2)!=null){surface=path.get(i);break;}
                return surface==null?null:new Channel(p,surface,direction,List.copyOf(path),List.copyOf(states));
            }
            p=p.relative(direction.getOpposite());
        }
        return null;
    }
    public boolean capture(BlockPos pos,int interval) {
        if(interval<=0)return false;
        Channel cached=channels.get(sourcesByCell.get(pos));
        if(cached!=null&&valid(cached))return true;
        Channel channel=trace(pos);if(channel==null)return false;
        Channel prior=channels.get(channel.source);
        // The bound limits supported stream identities, never pays duplicate downstream cells.
        if(prior==null&&channels.size()>=256)return false;
        if(prior==null||!valid(prior)||prior.path.size()<=channel.path.size()) {
            if(prior!=null)forget(prior);
            channels.put(channel.source,channel);
            for(BlockPos p:channel.path)sourcesByCell.put(p,channel.source);
        }
        return true;
    }
    private boolean valid(Channel c) {
        if(validityTick!=level.getGameTime()){validityTick=level.getGameTime();validity.clear();}
        return validity.computeIfAbsent(c,this::checkValid);
    }
    private void forget(Channel c){for(BlockPos p:c.path)sourcesByCell.remove(p,c.source);validity.remove(c);}
    private boolean checkValid(Channel c) {
        for(int i=0;i<c.path.size();i++) {
            BlockPos p=c.path.get(i);
            if(!level.hasChunkAt(p)||!level.shouldTickBlocksAt(p)||level.getFluidState(p)!=c.states.get(i))return false;
            Vec3 flow=c.states.get(i).getFlow(level,p);
            if(Math.abs(flow.y)>.1||flow.dot(new Vec3(c.direction.getStepX(),0,c.direction.getStepZ()))<=0)return false;
            for(Direction side:List.of(c.direction.getClockWise(),c.direction.getCounterClockWise())) {
                BlockPos n=p.relative(side);if(!level.hasChunkAt(n)||level.getFluidState(n).getType().isSame(c.states.get(i).getType()))return false;
            }
        }
        return true;
    }
    public void tick() {
        var it=channels.values().iterator();
        while(it.hasNext()) {
            Channel c=it.next();if(!valid(c)){it.remove();forget(c);continue;}
            TemporalWorld world=TemporalWorld.get(level);LivingEntity receiver=world.receiver(c.surface,2);
            if(receiver==null){it.remove();forget(c);continue;}
            FluidState fluid=level.getFluidState(c.surface);
            int interval=world.scheduled.interval(c.surface,fluid.getType());if(interval<=0){it.remove();forget(c);continue;}
            double area=Math.min(1,fluid.getAmount()/8.0);
            double price=fluid.getType() instanceof LavaFluid?SourceIncomeRates.LAVA:SourceIncomeRates.WATER;
            // 1/20 simulated second, half captured; frequency applies exactly once.
            world.credit(receiver,2,price*(5.0/interval)*area*.025);
        }
    }
    public static Vec3 adjustPush(Entity entity,Vec3 before,Vec3 after) {
        Vec3 push=after.subtract(before);
        // Item motion now advances every tick; synchronize the half-step force on both peers too.
        if (entity instanceof net.minecraft.world.entity.item.ItemEntity item && ItemTime.slow(item))
            return before.add(push.scale(.5));
        if(!(entity.level() instanceof ServerLevel level)||EntityTime.protectedEntity(entity)||push.lengthSqr()<1E-12)return after;
        if(TemporalWorld.get(level).receiver(entity.blockPosition(),2)==null)return after;
        FluidTime fluids=get(level);FluidState state=level.getFluidState(entity.blockPosition());
        if(!fluids.capture(entity.blockPosition(),state.getType().getTickDelay(level)))return after;
        // A half-rate entity already receives this force on only half its local steps.
        boolean localHalf=EntityTime.inStep(entity)&&TemporalWorld.get(level).receiver(entity.blockPosition(),4)!=null;
        Vec3 applied=localHalf?push:push.scale(.5);
        return before.add(applied);
    }
    public static Vec3 independentMotion(ServerLevel level,Entity entity,Vec3 movement) {
        FluidTime fluids=get(level);Channel channel=fluids.channels.get(fluids.sourcesByCell.get(entity.blockPosition()));
        if(channel==null||!fluids.valid(channel)||TemporalWorld.get(level).receiver(entity.blockPosition(),2)==null)return movement;
        FluidState state=level.getFluidState(entity.blockPosition());
        if(entity.getBoundingBox().minY>=entity.blockPosition().getY()+state.getHeight(level,entity.blockPosition()))return movement;
        // Persisting fluid momentum cannot be separated from voluntary parallel swimming cheaply.
        // Conservatively sell no positive along-flow displacement a second time; lateral motion remains separate.
        Vec3 direction=new Vec3(channel.direction.getStepX(),0,channel.direction.getStepZ());
        return movement.subtract(direction.scale(Math.max(0,movement.dot(direction))));
    }
}
