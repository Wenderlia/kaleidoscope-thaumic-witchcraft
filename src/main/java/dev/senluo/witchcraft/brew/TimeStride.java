package dev.senluo.witchcraft.brew;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.particles.DustParticleOptions;
import org.joml.Vector3f;
import dev.senluo.witchcraft.mixin.brew.StrideTeleportAccess;

public final class TimeStride {
    private static final ResourceLocation SPEED=ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft","time_stride");
    private static final int RAMP_TICKS=60;
    private static final DustParticleOptions GOLD=new DustParticleOptions(new Vector3f(.94F,.70F,.25F),.65F);
    private static final ResourceLocation STEP=ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft","time_stride_step");
    private static final int INTERRUPTION_GRACE_TICKS=20;
    private static final Map<ServerPlayer,State> STATES=new WeakHashMap<>();
    private TimeStride() {}
    public static boolean flying(Player player) {
        return !player.onGround() && (player.getAbilities().flying || player.isFallFlying());
    }
    /** Only an authorized flight uses the synchronized ramp; vanilla owns its underlying velocity. */
    public static double flightMultiplier(Player player) {
        return flying(player) ? movementMultiplier(player) : 1;
    }
    /** Ground and flight checks consume the same paid modifier, never a client-supplied speed. */
    public static double movementMultiplier(Player player) {
        if(!player.isSprinting() || !player.isAlive() || player.isSpectator()
                || player.isPassenger() || (player.isShiftKeyDown() && !player.getAbilities().flying)
                || player.isInWaterOrBubble() || player.isInLava())return 1;
        int grade;
        if(player.level().isClientSide) {
            var effect=player.getEffect(BrewContent.YEARS_ABUNDANCE);
            grade=effect==null?0:effect.getAmplifier()+1;
        } else {
            BodyTime time=ExtraTime.existing(player);
            if(time==null || !time.active() || !time.canSpend(time.compression()*2))return 1;
            grade=time.grade();
        }
        if(grade<3)return 1;
        var speed=player.getAttribute(Attributes.MOVEMENT_SPEED);
        var modifier=speed==null?null:speed.getModifier(SPEED);
        double factor=modifier==null?1:1+modifier.amount();
        return Double.isFinite(factor)?Math.clamp(factor,1,grade>=6?16:grade>=5?8:grade>=4?4:2):1;
    }
    /** Completed speed tiers only; reads the active, client-synchronized modifier, not cached ramp progress. */
    public static int completedMultiplier(Player player) {
        var speed=player.getAttribute(Attributes.MOVEMENT_SPEED);
        var modifier=speed==null?null:speed.getModifier(SPEED);
        double factor=modifier==null?1:1+modifier.amount();
        int tier=1;
        while(tier<16 && factor+1.0E-7>=tier*2)tier*=2;
        return tier;
    }
    /** Suppress only the attack's sprint reset; ordinary input and eligibility still end striding. */
    public static boolean keepSprintAfterAttack(Player player) {
        if(!player.isSprinting() || !player.isAlive() || player.isSpectator()
                || (player.isShiftKeyDown() && !player.getAbilities().flying) || player.isPassenger()
                || player.isInWaterOrBubble() || player.isInLava())return false;
        if(player.level().isClientSide) {
            var effect=player.getEffect(BrewContent.YEARS_ABUNDANCE);
            return effect!=null && effect.getAmplifier()>=2;
        }
        BodyTime time=ExtraTime.existing(player);
        return time!=null && time.active() && time.grade()>=3 && time.canSpend(time.compression()*2);
    }
    /** Observed movement determines whether this tick receives the double maintenance price. */
    public static boolean update(ServerPlayer player,BodyTime time) {
        if(time==null || !time.active() || time.grade()<3 || !player.isAlive() || player.isSpectator()
                || (player.isShiftKeyDown() && !player.getAbilities().flying) || player.isPassenger()
                || player.isInWaterOrBubble() || player.isInLava()) {
            clear(player);return false;
        }
        State state=STATES.get(player);
        if(state==null) {
            if(player.isSprinting())STATES.put(player,new State(player));
            return false;
        }
        Vec3 delta=player.position().subtract(state.position);
        long tick=player.level().getGameTime();
        int teleport=((StrideTeleportAccess)player.connection).witchcraft$teleportSequence();
        boolean continuous=state.dimension.equals(player.level().dimension().location()) && tick-state.tick==1;
        state.position=player.position();state.tick=tick;state.dimension=player.level().dimension().location();
        // Position packets can arrive in batches at high speed. Distance alone cannot identify teleportation.
        boolean relocated=state.teleport!=teleport;
        state.teleport=teleport;
        if(!time.free() && !time.canSpend(time.compression()*2)) {
            clear(player);return false;
        }
        boolean flight=flying(player);
        boolean climbing=!flight && player.onClimbable();
        int limit=time.grade()>=6?330:time.grade()>=5?240:time.grade()>=4?150:RAMP_TICKS;
        state.progress=Math.min(limit,state.progress);
        // Relocation changes the observation origin, not earned acceleration. Includes corrections,
        // intentional teleports, dimension changes and resuming after a suspended simulation.
        boolean awaiting=((StrideTeleportAccess)player.connection).witchcraft$awaitingPosition()!=null;
        if(!continuous || relocated || awaiting) {
            state.interruptedTicks=0;
            if(!player.isSprinting()) {pause(player);return false;}
            setSpeed(player,speedBonus(state.progress));
            setStep(player,flight ? 0 : state.progress>0 ? stepHeight(time.grade()) : 0);
            return state.progress>0; // No displacement, growth or idle timeout while reanchoring/waiting.
        }
        // A short sprint interruption must not discard several seconds of earned acceleration.
        // Actual stopping removes the bonus immediately; only the ramp is cached briefly.
        if(!player.isSprinting()) {
            if(climbing)state.interruptedTicks=0;
            else state.interruptedTicks++;
            if(state.interruptedTicks>INTERRUPTION_GRACE_TICKS)clear(player);
            else pause(player);
            return false;
        }
        // Ordinary airborne motion retains the ramp even during a vertical fall with no horizontal
        // samples. Landing is a mode transition, not a timeout; it earns no extra airborne progress.
        if(!player.onGround() && !flight && !climbing) {
            state.interruptedTicks=0;
            setSpeed(player,speedBonus(state.progress));
            setStep(player,state.progress>0 ? stepHeight(time.grade()) : 0);
            return state.progress>0;
        }
        // A server tick without a movement packet, a landing or a step collision is not proof
        // that the player stopped running. Keep an existing bonus briefly while sprint stays on.
        if((flight ? delta.lengthSqr() : delta.horizontalDistanceSqr())<1.0e-10) {
            if(climbing) {state.interruptedTicks=0;pause(player);return false;}
            if(++state.interruptedTicks>INTERRUPTION_GRACE_TICKS) {clear(player);return false;}
            var speed=player.getAttribute(Attributes.MOVEMENT_SPEED);
            boolean boosted=speed!=null && speed.getModifier(SPEED)!=null;
            setStep(player,boosted && !flight ? stepHeight(time.grade()) : 0);
            return boosted;
        }
        state.interruptedTicks=0;
        // Flight builds the same ramp; an ordinary sprint jump still only retains it.
        if((player.onGround() || flight) && !climbing)state.progress=Math.min(limit,state.progress+1);
        if(state.progress==0)return false;
        double bonus=speedBonus(state.progress);
        setSpeed(player,bonus);
        setStep(player,flight ? 0 : stepHeight(time.grade()));
        if(time.grade()>=6 && state.progress>180 && player.tickCount%3==0) {
            double angle=Math.toRadians(player.getYRot());
            double x=Math.cos(angle)*.48,z=Math.sin(angle)*.48;
            // Directed packets retain a little forward drift without changing the player's movement.
            Vec3 drift=delta.scale(.08);
            for(int sign:new int[]{-1,1})player.serverLevel().sendParticles(StrideParticles.RUNE.get(),
                    player.getX()+sign*x,player.getY()+player.getBbHeight()*.55,player.getZ()+sign*z,
                    0,drift.x,.012,drift.z,1);
        } else if(time.grade()==5 && state.progress>180 && player.tickCount%5==0) {
            double angle=Math.toRadians(player.getYRot());
            double x=Math.cos(angle)*.42,z=Math.sin(angle)*.42;
            for(int sign:new int[]{-1,1})player.serverLevel().sendParticles(GOLD,
                    player.getX()+sign*x,player.getY()+player.getBbHeight()*.55,player.getZ()+sign*z,
                    1,.035,.12,.035,0);
        }
        return true;
    }
    public static double speedBonus(int progress) {
        return Math.min(1,Math.max(0,progress)/(double)RAMP_TICKS)
                +2*smooth((progress-90)/60.0)+4*smooth((progress-180)/60.0)
                +8*smooth((progress-270)/60.0);
    }
    private static double smooth(double t) {t=Math.clamp(t,0,1);return t*t*(3-2*t);}
    private static double stepHeight(int grade) {return grade>=6?2:grade>=5?1.5:grade>=4?1:0;}
    public static void clear(ServerPlayer player) {STATES.remove(player);pause(player);}
    private static void pause(ServerPlayer player) {setSpeed(player,0);setStep(player,0);}
    private static void setStep(ServerPlayer player,double height) {
        // Two native floor providers must not repeatedly pull each other down to different floors.
        if(height>0)height=Math.max(height,DawnWalk.minimumStepHeight(player));
        StepHeightFloor.apply(player.getAttribute(Attributes.STEP_HEIGHT),STEP,height);
    }
    private static void setSpeed(ServerPlayer player,double bonus) {
        var attribute=player.getAttribute(Attributes.MOVEMENT_SPEED);
        if(attribute==null)return;
        var previous=attribute.getModifier(SPEED);
        if(previous!=null && previous.amount()==bonus)return;
        if(previous!=null)attribute.removeModifier(SPEED);
        if(bonus>0)attribute.addTransientModifier(new AttributeModifier(SPEED,bonus,AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }
    private static final class State {
        private Vec3 position;
        private ResourceLocation dimension;
        private long tick;
        private int progress;
        private int interruptedTicks;
        private int teleport;
        private State(ServerPlayer player) {
            position=player.position();dimension=player.level().dimension().location();tick=player.level().getGameTime();
            teleport=((StrideTeleportAccess)player.connection).witchcraft$teleportSequence();
        }
    }
}
