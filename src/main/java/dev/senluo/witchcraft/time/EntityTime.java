package dev.senluo.witchcraft.time;

import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.phys.Vec3;
import dev.senluo.witchcraft.mixin.time.EntityMotionAccess;

/** Native local simulation steps retain physical velocity; environmental/safety base ticks continue. */
public final class EntityTime {
    private record Scope(Entity entity, boolean held) {}
    private static final ThreadLocal<Scope> CURRENT=new ThreadLocal<>();
    private static final Map<Entity,Long> LAST=new WeakHashMap<>();
    private EntityTime() {}
    public static boolean protectedEntity(Entity e) {
        return e instanceof Player || e.getRootVehicle().getSelfAndPassengers().anyMatch(p->p instanceof Player);
    }
    public static boolean supported(Entity e) {
        return !protectedEntity(e) && !dev.senluo.witchcraft.ritual.OwnershipOfferings.held(e)
                && (e instanceof Mob || e instanceof ItemEntity || e instanceof Projectile
                || e instanceof Boat || e instanceof AbstractMinecart || e instanceof FallingBlockEntity || e instanceof PrimedTnt);
    }
    public static boolean held(Entity e) { Scope scope=CURRENT.get();return scope!=null&&scope.entity==e&&scope.held; }
    public static boolean inStep(Entity e) { Scope scope=CURRENT.get();return scope!=null&&scope.entity==e; }
    public static long localTime(Entity entity,long worldTime) {
        return worldTime-Math.max(0,Math.min(1_000_000_000_000L,entity.getPersistentData().getLong("WitchcraftHeldTicks")));
    }
    private static double volume(Entity e) {
        var b=e.getBoundingBox();return Math.max(0,b.getXsize()*b.getYsize()*b.getZsize());
    }
    private static boolean hasClock(Entity e) {
        if(e instanceof Mob m&&!m.isNoAi()&&m.isAlive())return true;
        if(e instanceof ItemEntity||e instanceof PrimedTnt)return true;
        if(e instanceof net.minecraft.world.entity.LivingEntity l&&(!l.getActiveEffects().isEmpty()||l.getAirSupply()<l.getMaxAirSupply()||l.isInWater()))return true;
        return e.getRemainingFireTicks()>0;
    }
    public static void tick(Entity entity,Runnable original) {
        if (!(entity instanceof Player) && dev.senluo.witchcraft.ritual.OwnershipRitual.frozen(entity)) {
            entity.tickCount--; // ServerLevel already advanced this before invoking the entity tick.
            return;
        }
        if (entity instanceof ItemEntity item && !supported(item) && !entity.level().isClientSide) ItemTime.setSlow(item, false);
        if(!(entity.level() instanceof ServerLevel level)||!supported(entity)||!entity.isAlive()) {original.run();return;}
        long now=level.getGameTime();
        if(Objects.equals(LAST.put(entity,now),now)){original.run();return;}
        TemporalWorld world=TemporalWorld.get(level);
        LivingEntity receiver=world.receiver(entity.blockPosition(),4);
        dev.senluo.witchcraft.accessory.ProjectileBarrier.simulationRate(entity, receiver == null ? 1 : .5);
        if (entity instanceof ItemEntity item) {
            boolean slow = ItemTime.begin(item, receiver);
            Scope previous = CURRENT.get();
            CURRENT.set(new Scope(item, slow && ItemTime.pauseClock(item)));
            try { original.run(); }
            finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
            return;
        }
        boolean held=receiver!=null&&((now+entity.getId())&1)==0;
        Scope previous=CURRENT.get();CURRENT.set(new Scope(entity,held));
        try {
            if(!held){original.run();return;}
            Vec3 velocity=entity.getDeltaMovement();
            Vec3 available=dev.senluo.witchcraft.accessory.TurtleField.limitMotion(entity,velocity);
            Vec3 possible=((EntityMotionAccess)entity).senluo$collide(available);
            possible=dev.senluo.witchcraft.accessory.TurtleField.hardMotion(entity,possible);
            // Stable fluid pushing has its own surface ledger. Do not sell that component twice.
            Vec3 independent=FluidTime.independentMotion(level,entity,possible);
            double volume=volume(entity);
            double price=SourceIncomeRates.motion(volume,independent.length())
                    + (hasClock(entity)?SourceIncomeRates.clock(volume,1.0/20):0);
            if(price>0&&!world.credit(receiver,4,price)){
                CURRENT.set(new Scope(entity,false));
                dev.senluo.witchcraft.accessory.ProjectileBarrier.simulationRate(entity, 1);
                original.run();return;
            }
            var data=entity.getPersistentData();
            data.putLong("WitchcraftHeldTicks",Math.max(0,Math.min(999_999_999_999L,data.getLong("WitchcraftHeldTicks")))+1);
            entity.tickCount--;
            entity.baseTick();
            // Base ticks keep portals, void checks, extinguishing, synchronization and hurt immunity;
            // impulses from a held physical step must not accumulate extra acceleration.
            entity.setDeltaMovement(velocity);
        } finally {if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }
    public static Vec3 externalMove(Entity entity,Vec3 movement) {
        if(inStep(entity)||!(entity.level() instanceof ServerLevel level)||!supported(entity)||movement.lengthSqr()<1.0E-12)return movement;
        TemporalWorld world=TemporalWorld.get(level);LivingEntity receiver=world.receiver(entity.blockPosition(),4);
        if(receiver==null)return movement;
        Vec3 possible=((EntityMotionAccess)entity).senluo$collide(movement);
        possible=dev.senluo.witchcraft.accessory.TurtleField.hardMotion(entity,possible);
        double amount=SourceIncomeRates.motion(volume(entity),FluidTime.independentMotion(level,entity,possible).length()*.5);
        Vec3 independent=FluidTime.independentMotion(level,entity,movement);
        return amount>0&&world.credit(receiver,4,amount)?movement.subtract(independent.scale(.5)):movement;
    }
}
