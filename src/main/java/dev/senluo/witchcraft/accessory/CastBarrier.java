package dev.senluo.witchcraft.accessory;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import java.util.function.Supplier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;

/** Synchronous propagation admission. No TC classes, target scans or persistent immunity. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID)
public final class CastBarrier {
    private static final ThreadLocal<Cast> CURRENT = new ThreadLocal<>();
    private static final class Cast {
        final Entity emitter, caster;
        final PropagationField field;
        Vec3 origin;
        Cast(Entity emitter, Entity caster, Vec3 origin, PropagationField field) {
            this.emitter = emitter; this.caster = caster; this.origin = origin; this.field = field;
        }
    }

    public static boolean active(Entity emitter) {
        var cast = CURRENT.get();
        return cast != null && cast.emitter == emitter;
    }

    public static <T> T apply(LivingEntity caster, Supplier<T> action) {
        Cast previous = CURRENT.get();
        PropagationField field = caster.level().isClientSide ? PropagationField.EMPTY
                : TurtleField.propagation(caster.level(), caster.getUUID());
        if (field.isEmpty()) CURRENT.remove();
        else CURRENT.set(new Cast(caster, caster, caster.getBoundingBox().getCenter(), field));
        try { return action.get(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }

    public static <T> T impact(Entity projectile, Entity owner, Supplier<T> action) {
        Cast previous = CURRENT.get();
        // A burst, like an explosion, does not inherit the owner's flight exemption.
        PropagationField field = projectile.level().isClientSide ? PropagationField.EMPTY
                : TurtleField.propagation(projectile.level());
        if (field.isEmpty()) CURRENT.remove();
        else CURRENT.set(new Cast(projectile, owner, projectile.position(), field));
        try { return action.get(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }

    /** Called by a host's resolved-origin hook, or by an explicit chain-hop adapter. */
    public static void origin(LivingEntity caster, Vec3 origin) {
        Cast cast = CURRENT.get();
        if (cast != null && cast.emitter == caster && Double.isFinite(origin.lengthSqr())) cast.origin = origin;
    }

    public static boolean blocks(Entity target, boolean feedback) {
        Cast cast = CURRENT.get();
        if (cast == null || target == cast.emitter || target.level() != cast.emitter.level()
                || !(target instanceof LivingEntity)) return false;
        return blocksPoint(target, target.getBoundingBox().getCenter(), feedback);
    }

    public static boolean blocksPoint(Entity context, Vec3 target, boolean feedback) {
        PropagationField.Hit hit = contact(context, target);
        if (hit == null) return false;
        if (feedback) BarrierVisuals.propagation(context.level(), hit, .7F);
        return true;
    }

    public static PropagationField.Hit contact(Entity context, Vec3 target) {
        Cast cast = CURRENT.get();
        // Native explosions already evaluate all body samples. Do not replace that with one center ray.
        if (cast == null || context.level() != cast.emitter.level() || ExplosionBarrier.applying()) return null;
        return cast.field.hit(cast.origin, target);
    }

    /** Preserve momentum that existed before the spell, rather than freezing the target. */
    public static Vec3 velocity(Entity target, Vec3 proposed) {
        return CURRENT.get() != null && !proposed.equals(target.getDeltaMovement()) && blocks(target, true)
                ? target.getDeltaMovement() : proposed;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void damage(LivingIncomingDamageEvent event) {
        Cast cast = CURRENT.get();
        // An unrelated/nested hit must not inherit the caster's propagation origin.
        if (cast != null && (event.getSource().getDirectEntity() == cast.emitter
                || cast.caster != null && event.getSource().getEntity() == cast.caster)
                && blocks(event.getEntity(), true))
            event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void effect(MobEffectEvent.Applicable event) {
        if (blocks(event.getEntity(), true)) event.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
    }

    private CastBarrier() {}
}
