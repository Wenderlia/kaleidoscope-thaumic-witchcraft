package dev.senluo.witchcraft.brew;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/** One synchronous melee attempt; escrow prevents callbacks from spending the same budget twice. */
public final class TimeMelee {
    private static final ThreadLocal<Attempt> CURRENT=new ThreadLocal<>();
    private TimeMelee() {}
    public static int multiplier(int grade) {return grade>=6?4:grade>=5?2:1;}
    public static Attempt begin(Player player,Entity target) {
        Attempt attempt=new Attempt(player,target,CURRENT.get());
        CURRENT.set(attempt);
        return attempt;
    }
    public static float strength(Player player,float normal) {
        if(AutomaticAttack.active(player)) {
            prepareFullStrength(player,1);
            return 1;
        }
        if(!Float.isFinite(normal) || normal<0 || normal>=1)return normal;
        if(player.level().isClientSide) {
            var effect=player.getEffect(BrewContent.YEARS_ABUNDANCE);
            return effect!=null?Math.min(1,normal*multiplier(effect.getAmplifier()+1)):normal;
        }
        Attempt attempt=CURRENT.get();
        if(attempt==null || attempt.player!=player)return normal;
        return attempt.prepare(normal)?Math.min(1,normal*attempt.multiplier):normal;
    }
    public static void prepareFullStrength(Player player,float normal) {
        Attempt attempt=CURRENT.get();
        if(attempt!=null && attempt.player==player && normal>=1)attempt.prepare(normal);
    }
    public static double bypassTicks(LivingEntity target) {
        return bypassTicks(target,2);
    }
    private static double bypassTicks(LivingEntity target,int multiplier) {
        int start=target instanceof MeleeWindow window?window.witchcraft$windowStart():20;
        int remaining=target.invulnerableTime-10;
        int length=Math.max(0,start-10);
        double automatic=AutomaticAttack.bypassTicks(target,length,remaining);
        if(!Double.isNaN(automatic))return automatic;
        return remaining>0 && length>0 && remaining<=length-Math.ceil(length/(double)multiplier)?remaining:0;
    }
    public static boolean bypass(LivingEntity target,net.minecraft.world.damagesource.DamageSource source) {
        Attempt attempt=CURRENT.get();
        return attempt!=null && attempt.insideHit && attempt.target==target && attempt.bypass>0
                && source.getDirectEntity()==attempt.player && (attempt.automatic || attempt.reservation!=null)
                && bypassTicks(target,attempt.multiplier)>0 && bypassTicks(target,attempt.multiplier)<=attempt.bypass;
    }
    public static void usedBypass(LivingEntity target) {
        Attempt attempt=CURRENT.get();
        if(attempt!=null)attempt.usedBypass=Math.max(attempt.usedBypass,bypassTicks(target,attempt.multiplier));
    }
    public static final class Attempt implements AutoCloseable {
        private final Player player;
        private final Entity target;
        private final Attempt previous;
        private AbilityCosts.Reservation reservation;
        private boolean prepared,boost,success,insideHit,automatic;
        private int multiplier=1;
        private double bypass,charge,usedBypass;
        private Attempt(Player player,Entity target,Attempt previous) {this.player=player;this.target=target;this.previous=previous;}
        private boolean prepare(float normal) {
            if(prepared)return boost;
            prepared=true;
            if(!(player instanceof ServerPlayer server) || !(target instanceof LivingEntity living)
                    || !player.isAlive() || player.isSpectator())return false;
            BodyTime time=ExtraTime.existing(player);
            automatic=AutomaticAttack.active(player);
            if(automatic) {
                multiplier=AutomaticAttack.multiplier(player);
                bypass=bypassTicks(living,multiplier);
                return boost=true; // This invocation is already paid by its independent clock.
            }
            if(time==null || !time.active() || time.grade()<5)return false;
            multiplier=TimeMelee.multiplier(time.grade());
            double delay=player.getCurrentItemAttackStrengthDelay();
            if(!Double.isFinite(delay) || delay<=0)return false;
            charge=(Math.min(1,normal*multiplier)-Math.min(1,normal))*delay;
            bypass=bypassTicks(living,multiplier);
            double ticks=Math.max(charge,bypass);
            if(ticks<=0)return false;
            reservation=AbilityCosts.reserve(server,5,ticks,100_000);
            boost=reservation!=null;
            return boost;
        }
        public boolean hit(Entity entity,java.util.function.BooleanSupplier original) {
            if(entity!=target)return original.getAsBoolean();
            insideHit=true;
            long serial=entity instanceof MeleeWindow window?window.witchcraft$windowSerial():0;
            try {
                boolean accepted=original.getAsBoolean();success|=accepted;
                if(accepted && automatic && entity instanceof LivingEntity living)
                    AutomaticAttack.recordHit(player,living,serial);
                return accepted;
            }
            finally {insideHit=false;}
        }
        @Override public void close() {
            try {if(reservation!=null)reservation.finish(success,Math.max(charge,usedBypass));}
            finally {if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
        }
    }
    public static boolean hit(Entity entity,java.util.function.BooleanSupplier original) {
        Attempt attempt=CURRENT.get();
        return attempt==null?original.getAsBoolean():attempt.hit(entity,original);
    }
}
