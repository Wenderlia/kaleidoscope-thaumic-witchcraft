package dev.senluo.witchcraft.brew;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.SweepAttackEvent;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

/** Independent fractional melee clock. Vanilla owns each hit; no idle or lag backlog. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID)
public final class AutomaticAttack {
    public static final double COST=5;
    public static final int MAX_ATTACKS_PER_TICK=64;
    private static final Map<ServerPlayer,Clock> CLOCKS=new WeakHashMap<>();
    private static final ThreadLocal<Invocation> CURRENT=new ThreadLocal<>();
    private AutomaticAttack() {}

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void singleTarget(SweepAttackEvent event) {
        // Cancellation preserves isSweeping in this event; handle canceled events too,
        // without changing cancellation or suppressing unrelated manual attacks.
        if (active(event.getEntity())) event.setSweeping(false);
    }
    public static boolean active(Player player) {
        Invocation invocation=CURRENT.get();
        return invocation!=null && invocation.player==player;
    }
    /** A protected ritual hit may be seen by both attack and damage hooks; count it once. */
    public static boolean claimRitualContact(Player player,net.minecraft.world.entity.Entity target) {
        Invocation invocation=CURRENT.get();
        if(invocation==null || invocation.player!=player || invocation.target!=target || invocation.ritualContact)return false;
        invocation.ritualContact=true;
        return true;
    }
    public static int multiplier(Player player) {
        Invocation invocation=CURRENT.get();
        return invocation!=null && invocation.player==player?invocation.multiplier:1;
    }
    public static float yaw(Player player,float ordinary) {
        Invocation invocation=CURRENT.get();
        if(invocation==null || invocation.player!=player)return ordinary;
        return (float)Math.toDegrees(Math.atan2(-(invocation.target.getX()-player.getX()),
                invocation.target.getZ()-player.getZ()));
    }
    /** Only this player's synchronous invocation sees a shortened, fractional hurt window. */
    public static double bypassTicks(LivingEntity target,int length,int remaining) {
        Invocation invocation=CURRENT.get();
        if(invocation==null || invocation.target!=target)return Double.NaN;
        if(length<=0 || remaining<=0)return 0.0;
        Impact impact=invocation.clock.impacts.get(target);
        double elapsed;
        if(impact!=null && target instanceof MeleeWindow window && window.witchcraft$windowSerial()==impact.serial)
            elapsed=invocation.at-impact.at;
        else
            elapsed=length-remaining+(invocation.at-Math.floor(invocation.at));
        return elapsed+1.0E-9>=length/(double)invocation.multiplier?(double)remaining:0.0;
    }
    public static void recordHit(Player player,LivingEntity target,long previousSerial) {
        Invocation invocation=CURRENT.get();
        if(invocation==null || invocation.player!=player || invocation.target!=target
                || !(target instanceof MeleeWindow window) || window.witchcraft$windowSerial()==previousSerial)return;
        if(invocation.clock.impacts.size()>=128 && !invocation.clock.impacts.containsKey(target))invocation.clock.impacts.clear();
        invocation.clock.impacts.put(target,new Impact(window.witchcraft$windowSerial(),invocation.at));
    }
    public static boolean eligible(ServerPlayer player,Mob mob) {
        return mob.isAlive() && !mob.isRemoved() && mob.isAttackable()
                && !(mob instanceof OwnableEntity owned && owned.getOwnerUUID()!=null)
                && !player.isAlliedTo(mob) && !mob.isAlliedTo(player)
                && (mob.getTarget()==player || mob instanceof Enemy)
                && player.canInteractWithEntity(mob,0) && player.hasLineOfSight(mob);
    }
    private static Mob choose(ServerPlayer player,List<Mob> candidates) {
        Mob chosen=null;
        double nearest=Double.POSITIVE_INFINITY;
        boolean targetingPlayer=false;
        for(Mob mob:candidates) {
            boolean aggro=mob.getTarget()==player;
            double distance=player.distanceToSqr(mob);
            if((chosen==null || aggro && !targetingPlayer || aggro==targetingPlayer && distance<nearest)
                    && eligible(player,mob)) {
                chosen=mob;nearest=distance;targetingPlayer=aggro;
            }
        }
        return chosen;
    }
    public static void tick(ServerPlayer player,BodyTime time) {
        int stride=TimeStride.completedMultiplier(player);
        if(time==null || !time.active() || time.grade()<6 || stride<2 || !player.isSprinting()
                || !player.isAlive() || player.isSpectator() || player.isSleeping()) {
            CLOCKS.remove(player);return;
        }
        if(active(player))return;
        long now=player.level().getGameTime();
        ResourceLocation dimension=player.level().dimension().location();
        Clock clock=CLOCKS.computeIfAbsent(player,p->new Clock(dimension));
        if(clock.lastSeen==now && clock.dimension.equals(dimension))return;
        if(!clock.dimension.equals(dimension) || now-clock.lastSeen!=1) {
            clock.dimension=dimension;clock.progress=1;clock.impacts.clear();
        }
        clock.lastSeen=now;
        int multiplier=TimeMelee.multiplier(time.grade())*stride;
        double delay=player.getCurrentItemAttackStrengthDelay()/multiplier;
        double reach=player.entityInteractionRange();
        if(!Double.isFinite(delay) || delay<=0 || !Double.isFinite(reach) || reach<=0
                || !time.canSpend(COST)) {clock.progress=1;return;}
        double first=(1-clock.progress)*delay;
        if(first>=1) {clock.progress=Math.min(1,clock.progress+1/delay);return;}
        // One bounded spatial query per active tick, reused for all due attacks.
        List<Mob> candidates=player.serverLevel().getEntitiesOfClass(Mob.class,player.getBoundingBox().inflate(reach));
        int count=0;
        double at=first;
        for(;at<1 && count<MAX_ATTACKS_PER_TICK;at+=delay) {
            if(!time.active() || time.grade()<6 || !player.isAlive() || player.isSpectator()
                    || !player.isSprinting() || TimeStride.completedMultiplier(player)<2) {clock.progress=1;return;}
            Mob chosen=choose(player,candidates);
            if(chosen==null) {clock.progress=1;return;}
            BodyTime.Hold hold=time.hold(COST);
            if(hold==null) {clock.progress=1;return;}
            count++;
            Invocation previous=CURRENT.get();
            CURRENT.set(new Invocation(player,chosen,multiplier,now+at,clock));
            try {player.attack(chosen);}
            finally {
                if(previous==null)CURRENT.remove();else CURRENT.set(previous);
                hold.finish(true);
            }
        }
        // Carry only the fractional charge. Excess beyond the safety cap is not banked.
        clock.progress=count==MAX_ATTACKS_PER_TICK && at<1?0:Math.clamp(1-(at-1)/delay,0,1);
    }
    private static final class Invocation {
        final ServerPlayer player;
        final Mob target;
        final int multiplier;
        final double at;
        final Clock clock;
        boolean ritualContact;
        Invocation(ServerPlayer player,Mob target,int multiplier,double at,Clock clock) {
            this.player=player;this.target=target;this.multiplier=multiplier;this.at=at;this.clock=clock;
        }
    }
    private record Impact(long serial,double at) {}
    private static final class Clock {
        private ResourceLocation dimension;
        private long lastSeen=Long.MIN_VALUE;
        private double progress=1;
        private final Map<LivingEntity,Impact> impacts=new WeakHashMap<>();
        private Clock(ResourceLocation dimension) {this.dimension=dimension;}
    }
}
