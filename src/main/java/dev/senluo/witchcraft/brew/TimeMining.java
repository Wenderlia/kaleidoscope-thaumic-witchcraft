package dev.senluo.witchcraft.brew;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

/** Quotes mining from native progress; only the server's successful removal settles the time fee. */
public final class TimeMining {
    private static final Map<ServerPlayer,Session> SESSIONS=new WeakHashMap<>();
    private TimeMining() {}
    private static int tick(ServerPlayer player) {return ((MiningClock)player.gameMode).witchcraft$miningTick();}
    public static void start(ServerPlayer player,BlockPos pos) {
        SESSIONS.put(player,new Session(pos.immutable(),player.serverLevel().getBlockState(pos),
                player.level().dimension().location(),tick(player)));
    }
    public static void stop(ServerPlayer player) {SESSIONS.remove(player);}
    private static Session session(ServerPlayer player,BlockPos pos,BlockState state) {
        Session session=SESSIONS.get(player);
        return session!=null && session.pos.equals(pos) && session.state==state
                && session.dimension.equals(player.level().dimension().location())?session:null;
    }
    public static float progress(Player player,BlockPos pos,BlockState state,float normal) {
        if(!Float.isFinite(normal) || normal<=0 || normal>=1 || !player.isAlive()
                || player.isCreative() || player.isSpectator()) {
            if(player instanceof ServerPlayer server) {
                Session session=session(server,pos,state);
                if(session!=null)session.sample(tick(server),Float.isFinite(normal)?normal:0,1);
            }
            return normal;
        }
        if(player.level().isClientSide) {
            var effect=player.getEffect(BrewContent.YEARS_ABUNDANCE);
            if(effect==null || effect.getAmplifier()<4)return normal;
            int multiplier=TimeMelee.multiplier(effect.getAmplifier()+1);
            double maximumFee=Math.max(0,Math.ceil(1.0/normal)-Math.ceil(1.0/(normal*multiplier)));
            return BodyTime.displayCanSpend(effect.getDuration(),maximumFee)?normal*multiplier:normal;
        }
        if(!(player instanceof ServerPlayer server))return normal;
        Session session=session(server,pos,state);
        if(session==null)return normal;
        BodyTime time=ExtraTime.existing(server);
        int multiplier=time==null?1:TimeMelee.multiplier(time.grade());
        double maximumFee=Math.max(0,Math.ceil(1.0/normal)-Math.ceil(1.0/(normal*multiplier)));
        boolean boosted=time!=null && time.active() && time.grade()>=5 && time.canSpend(maximumFee);
        session.sample(tick(server),normal,boosted?multiplier:1);
        return boosted?normal*multiplier:normal;
    }
    public static Payment beginRemoval(ServerPlayer player,BlockPos pos) {
        Session session=session(player,pos,player.serverLevel().getBlockState(pos));
        if(session==null || player.isCreative() || session.normal<=0)return new Payment(true,null);
        double elapsed=Math.max(1,(long)tick(player)-session.start+1);
        double saved=Math.min(session.boostedTicks,Math.max(0,Math.ceil(1.0/session.normal)-elapsed));
        if(saved<=0)return new Payment(true,null);
        BodyTime time=ExtraTime.existing(player);
        BodyTime.Hold hold=time!=null && time.grade()>=5?time.hold(saved):null;
        return new Payment(hold!=null,hold);
    }
    public static final class Payment {
        public final boolean allowed;
        private final BodyTime.Hold hold;
        private boolean removed;
        private Payment(boolean allowed,BodyTime.Hold hold) {this.allowed=allowed;this.hold=hold;}
        public void removed(boolean value) {removed|=value;}
        public void finish() {if(hold!=null)hold.finish(removed);}
    }
    private static final class Session {
        private final BlockPos pos;
        private final BlockState state;
        private final ResourceLocation dimension;
        private final int start;
        private int lastTick;
        private long boostedTicks;
        private int lastExtra;
        private float normal;
        private Session(BlockPos pos,BlockState state,ResourceLocation dimension,int start) {
            this.pos=pos;this.state=state;this.dimension=dimension;this.start=start;lastTick=start;
        }
        private void sample(int now,float progress,int multiplier) {
            long gap=Math.max(0,(long)now-lastTick);
            int extra=multiplier-1;
            if(gap>0)boostedTicks+=lastExtra*Math.max(0,gap-1)+extra;
            else boostedTicks+=extra-lastExtra;
            lastTick=now;lastExtra=extra;normal=progress;
        }
    }
}
