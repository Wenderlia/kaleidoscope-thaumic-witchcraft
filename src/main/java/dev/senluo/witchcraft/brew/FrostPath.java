package dev.senluo.witchcraft.brew;

import com.mojang.serialization.MapCodec;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import net.minecraft.core.*;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.sounds.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.registries.*;
import org.joml.Vector3f;
import java.util.function.Supplier;

/** Local support and swept footprints; native scheduled ticks own cleanup, including reload. */
public final class FrostPath {
    private static final DeferredRegister.Blocks BLOCKS=DeferredRegister.createBlocks(KaleidoscopeWitchcraft.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BES=DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE,KaleidoscopeWitchcraft.MOD_ID);
    public static final DeferredBlock<LightBlock> BLOCK=BLOCKS.register("frost_light",()->new LightBlock(BlockBehaviour.Properties.of()
            .strength(-1,3600000).noLootTable().noOcclusion().dynamicShape().lightLevel(s->10).pushReaction(PushReaction.BLOCK).sound(SoundType.AMETHYST)));
    public static final Supplier<BlockEntityType<Expiry>> BE=BES.register("frost_light",()->BlockEntityType.Builder.of(Expiry::new,BLOCK.get()).build(null));
    // Bound memoization and presentation, never the length of the resolved movement.
    private static final int RECENT_POSITIONS=512,FORMATIONS_PER_TICK=4;
    private static final DustParticleOptions GLOW=new DustParticleOptions(new Vector3f(.62F,.88F,1),.6F);
    private FrostPath(){}
    static void register(IEventBus bus){BLOCKS.register(bus);BES.register(bus);FrostSupport.register(bus);NeoForge.EVENT_BUS.addListener(FrostPath::fall);}
    static void start(ServerPlayer p){p.serverLevel().playSound(null,p.blockPosition(),SoundEvents.AMETHYST_BLOCK_CHIME,SoundSource.PLAYERS,.3F,1.5F);}
    private static void fall(LivingFallEvent e){if(FrostAbilities.view(e.getEntity()).support())e.setCanceled(true);}
    public static boolean quiet(Entity e,Holder<GameEvent> type){
        return e!=null && FrostAbilities.view(e).quiet() && (type.is(GameEvent.STEP.key()) || type.is(GameEvent.HIT_GROUND.key()) || type.is(GameEvent.BLOCK_DESTROY.key()));
    }
    /** A local retry/renewal clock. Moving or resuming descent invalidates it immediately. */
    static final class Footing {
        BlockPos foot;net.minecraft.resources.ResourceKey<Level> dimension;long nextProbe,nextSound;
        long visitTick=Long.MIN_VALUE;boolean placing;int formations;
        net.minecraft.resources.ResourceKey<Level> visitDimension;
        final java.util.LinkedHashSet<BlockPos> visited=new java.util.LinkedHashSet<>();
        void reset(){foot=null;dimension=null;nextProbe=nextSound=0;}
        boolean visit(BlockPos p,net.minecraft.resources.ResourceKey<Level> dim,long now){
            if(visitTick!=now || visitDimension!=dim){visitTick=now;visitDimension=dim;visited.clear();formations=0;}
            if(!visited.add(p.immutable()))return false;
            if(visited.size()>RECENT_POSITIONS)visited.removeFirst();
            return true;
        }
        boolean probe(BlockPos pos,net.minecraft.resources.ResourceKey<Level> dim,long now){
            if(pos.equals(foot) && dim==dimension && now<nextProbe && nextProbe-now<=5)return false;
            foot=pos.immutable();dimension=dim;nextProbe=now+5;return true;
        }
    }
    static void tick(ServerPlayer p,WineAbilities.Window w,Footing footing){
        if(!eligible(p,w) || p.getDeltaMovement().y>.08){footing.reset();return;}
        BlockPos foot=below(p.position());
        long now=p.serverLevel().getGameTime();if(!footing.probe(foot,p.level().dimension(),now))return;
        footprint(p,foot,w,footing);
    }
    private static boolean eligible(ServerPlayer p,WineAbilities.Window w){
        return FrostAbilities.view(p).support() && p.isAlive() && !p.isSpectator() && (!p.isShiftKeyDown() || FrostSupport.holding(p))
                && !p.isPassenger() && !p.isFallFlying() && !p.getAbilities().flying && p.mayBuild()
                && !dev.senluo.witchcraft.ritual.OwnershipRitual.frozen(p);
    }
    private static BlockPos below(Vec3 feet){return BlockPos.containing(feet.x,Math.floor(feet.y+1e-5)-1,feet.z);}
    /** Called at the existing final-motion seam, after time scaling and before native collision. */
    public static void beforeMotion(Entity entity,Vec3 movement){
        if(!(entity instanceof ServerPlayer p) || !finite(movement) || movement.y>.08)return;
        var s=WineAbilities.existing(p);
        if(s==null || s.unsupported!=null || s.footing.placing || !eligible(p,s.frost))return;
        BlockPos foot=below(p.position());
        if(s.footing.probe(foot,p.level().dimension(),p.serverLevel().getGameTime()))footprint(p,foot,s.frost,s.footing);
    }
    /** Consume resolved movement, never the view direction or the uncollided velocity. */
    public static void afterMotion(Entity entity,Vec3 movement){
        if(!(entity instanceof ServerPlayer p) || !finite(movement) || movement.y>.08)return;
        var s=WineAbilities.existing(p);
        if(s==null || s.unsupported!=null || s.footing.placing || !eligible(p,s.frost))return;
        BlockPos start=below(p.position()),end=below(p.position().add(movement));
        if(start.equals(end))return;
        // A diagonal movement visits its crossed columns, not its rectangular bounding area.
        footprint(p,end,s.frost,s.footing);
        double x=p.getX(),z=p.getZ(),dx=movement.x,dz=movement.z;
        int bx=start.getX(),bz=start.getZ(),sx=Double.compare(dx,0),sz=Double.compare(dz,0);
        double tx=dx==0?Double.POSITIVE_INFINITY:((sx>0?bx+1:bx)-x)/dx;
        double tz=dz==0?Double.POSITIVE_INFINITY:((sz>0?bz+1:bz)-z)/dz;
        double ix=dx==0?Double.POSITIVE_INFINITY:Math.abs(1/dx),iz=dz==0?Double.POSITIVE_INFINITY:Math.abs(1/dz);
        long crossings=Math.abs((long)end.getX()-bx)+Math.abs((long)end.getZ()-bz);
        int chunkX=bx>>4,chunkZ=bz>>4;
        if(!p.serverLevel().hasChunk(chunkX,chunkZ))return;
        for(long i=0;i<crossings && (bx!=end.getX() || bz!=end.getZ());i++){
            double t=Math.min(tx,tz);if(t>1 || !Double.isFinite(t))break;
            if(tx<=t){bx+=sx;tx+=ix;}if(tz<=t){bz+=sz;tz+=iz;}
            // Stop at an unavailable segment; never load a remote chunk to complete a path.
            if((bx>>4)!=chunkX || (bz>>4)!=chunkZ){
                chunkX=bx>>4;chunkZ=bz>>4;
                if(!p.serverLevel().hasChunk(chunkX,chunkZ))break;
            }
            int by=(int)Math.floor(p.getY()+movement.y*t+1e-5)-1;
            footprint(p,new BlockPos(bx,by,bz),s.frost,s.footing);
        }
    }
    private static boolean finite(Vec3 v){return Double.isFinite(v.x)&&Double.isFinite(v.y)&&Double.isFinite(v.z);}
    private static void footprint(ServerPlayer p,BlockPos pos,WineAbilities.Window w,Footing f){
        if(f.placing)return;f.placing=true;
        try{
            long now=p.serverLevel().getGameTime();boolean formed=false;
            var dim=p.level().dimension();
            if(f.visit(pos,dim,now))formed=touch(p,pos,w);
            if(FrostAbilities.view(p).cross())for(Direction d:Direction.Plane.HORIZONTAL){
                BlockPos neighbour=pos.relative(d);if(f.visit(neighbour,dim,now))formed|=touch(p,neighbour,w);
            }
            if(formed && f.formations<FORMATIONS_PER_TICK){
                f.formations++;
                p.serverLevel().sendParticles(GLOW,pos.getX()+.5,pos.getY()+1.015,pos.getZ()+.5,3,.28,.015,.28,0);
            }
            if(formed && now>=f.nextSound){
                p.serverLevel().playSound(null,pos,SoundEvents.AMETHYST_BLOCK_STEP,SoundSource.PLAYERS,.12F,1.7F);f.nextSound=now+5;
            }
        }finally{f.placing=false;}
    }
    private static boolean touch(ServerPlayer p,BlockPos pos,WineAbilities.Window w){
        ServerLevel level=p.serverLevel();if(!level.hasChunkAt(pos) || level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos))return false;
        var ability=FrostAbilities.view(p);if(!ability.support())return false;
        BlockState before=level.getBlockState(pos);int ttl=ability.lifetime();
        if(before.is(BLOCK.get())){if(level.getBlockEntity(pos) instanceof Expiry e)e.touch(ttl);return false;}
        boolean paid=!ability.unlimited();
        if(!before.isAir() || paid && w.credits<=0)return false;
        int revision=w.revision;if(paid)w.credits--;
        if(DawnCultivation.commit(p,pos,before,BLOCK.get().defaultBlockState())){
            if(level.getBlockEntity(pos) instanceof Expiry e)e.touch(ttl);
            return true;
        }
        if(paid && revision==w.revision)w.credits++;
        return false;
    }
    public static final class LightBlock extends BaseEntityBlock {
        private static final MapCodec<LightBlock> CODEC=simpleCodec(LightBlock::new);
        private static final VoxelShape TOP=box(0,15,0,16,16,16);
        LightBlock(Properties p){super(p);}
        @Override protected MapCodec<? extends BaseEntityBlock> codec(){return CODEC;}
        @Override public BlockEntity newBlockEntity(BlockPos p,BlockState s){return new Expiry(p,s);}
        // The visible plane is submitted after clouds, without writing terrain depth.
        @Override protected RenderShape getRenderShape(BlockState s){return RenderShape.INVISIBLE;}
        @Override protected VoxelShape getShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){return TOP;}
        @Override protected VoxelShape getCollisionShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){
            // Scaffolding's per-entity above/descending contract; no side or underside wall.
            return c instanceof EntityCollisionContext ec && ec.getEntity()!=null
                    && FrostAbilities.view(ec.getEntity()).support()
                    && ((!ec.getEntity().isShiftKeyDown() && !c.isDescending()) || FrostSupport.holding(ec.getEntity()))
                    && c.isAbove(Shapes.block(),p,false)?TOP:Shapes.empty();
        }
        @Override protected void tick(BlockState s,ServerLevel l,BlockPos p,RandomSource r){
            if(l.getBlockEntity(p) instanceof Expiry e && e.expires>l.getGameTime()){e.wake();return;}
            if(l.removeBlock(p,false))l.sendParticles(GLOW,p.getX()+.5,p.getY()+.9,p.getZ()+.5,3,.25,.1,.25,.01);
        }
    }
    public static final class Expiry extends BlockEntity {
        private long expires;
        Expiry(BlockPos p,BlockState s){super(BE.get(),p,s);}
        void touch(int ticks){
            if(level==null)return;long next=level.getGameTime()+ticks;
            // Short-lived module steps renew in quarter-second steps; never shorten a wine step.
            if(next-expires<(ticks<=20?5:10))return;expires=next;setChanged();wake();
        }
        void wake(){if(level!=null && !level.isClientSide)level.scheduleTick(worldPosition,BLOCK.get(),(int)Math.clamp(expires-level.getGameTime(),1,100));}
        @Override public void onLoad(){super.onLoad();wake();}
        @Override protected void saveAdditional(CompoundTag t,HolderLookup.Provider p){super.saveAdditional(t,p);t.putLong("Expires",expires);}
        @Override protected void loadAdditional(CompoundTag t,HolderLookup.Provider p){super.loadAdditional(t,p);expires=t.getLong("Expires");}
    }
}
