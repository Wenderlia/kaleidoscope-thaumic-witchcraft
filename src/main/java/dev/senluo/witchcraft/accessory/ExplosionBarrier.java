package dev.senluo.witchcraft.accessory;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/** Explosion adapter: keeps native exposure sampling, resistance, event and drop algorithms. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID)
public final class ExplosionBarrier {
    private static final int MAX_CACHED = 512;
    private record Exposure(float visible, boolean intercepted, PropagationField.Hit hit) {
        boolean denied() { return intercepted && visible <= 0; }
    }
    private static final class Blast {
        final Explosion explosion;
        final Level level;
        final Vec3 origin;
        final PropagationField field;
        final boolean nativeExposure;
        final Map<Entity, Exposure> exposures = new IdentityHashMap<>();
        Entity lastEntity;
        Exposure last;
        Blast(Level level, Explosion explosion) { this(level, explosion, false); }
        Blast(Level level, Explosion explosion, boolean nativeExposure) {
            this.explosion = explosion; this.level = level; origin = explosion.center();
            this.nativeExposure = nativeExposure;
            // Native exposure samples can extend beyond the blast's entity-search box on giant bodies.
            // Snapshot equipped wearers once; each geometric ray still performs its own cheap broad phase.
            field = TurtleField.propagation(level);
        }
        void remember(Entity entity, Exposure value) {
            lastEntity = entity; last = value;
            if (exposures.size() < MAX_CACHED) exposures.put(entity, value);
        }
        Exposure get(Entity entity) { return lastEntity == entity ? last : exposures.get(entity); }
    }
    private static final class Sampling {
        final Level level;
        final PropagationField field;
        boolean intercepted;
        PropagationField.Hit hit;
        Sampling(Level level, PropagationField field) { this.level = level; this.field = field; }
    }
    private static final ThreadLocal<Blast> BLAST = new ThreadLocal<>();
    private static final ThreadLocal<Sampling> SAMPLING = new ThreadLocal<>();

    static boolean applying() { return BLAST.get() != null; }

    public static void explode(Level level, Explosion explosion, Runnable original) {
        explode(level, explosion, false, original);
    }

    /** A reviewed custom explosion guards its own sample rays AND its unsampled targets.
     * Keep block filtering, but do not pre-filter its entities using vanilla's different hitbox samples. */
    public static void explodeWithNativeExposure(Level level, Explosion explosion, Runnable original) {
        explode(level, explosion, true, original);
    }

    private static void explode(Level level, Explosion explosion, boolean nativeExposure, Runnable original) {
        Blast previous = BLAST.get();
        if (!TurtleField.hasFields(level)) {
            if (previous == null) { original.run(); return; }
            BLAST.remove();
            try { original.run(); } finally { BLAST.set(previous); }
            return;
        }
        Blast current = new Blast(level, explosion, nativeExposure);
        BLAST.set(current);
        try { original.run(); }
        finally { if (previous == null) BLAST.remove(); else BLAST.set(previous); }
    }

    /** Called around the native method, so modified entity shapes retain their native sample positions. */
    public static float exposure(Vec3 origin, Entity entity, Supplier<Float> original) {
        Blast blast = BLAST.get();
        if (blast != null && (blast.level != entity.level() || !blast.origin.equals(origin))) blast = null;
        if (blast != null) {
            Exposure cached = blast.get(entity);
            if (cached != null) return cached.visible;
        }
        PropagationField field = blast != null ? blast.field : TurtleField.propagation(entity.level());
        if (field.isEmpty()) return original.get();
        Sampling previous = SAMPLING.get(), sampling = new Sampling(entity.level(), field);
        SAMPLING.set(sampling);
        try {
            float value = original.get();
            if (blast != null) blast.remember(entity, new Exposure(value, sampling.intercepted, sampling.hit));
            return value;
        } finally { if (previous == null) SAMPLING.remove(); else SAMPLING.set(previous); }
    }

    /** Only Explosion.getSeenPercent calls this; ordinary clip/AI/interaction paths are untouched. */
    public static BlockHitResult clip(Level level, ClipContext context, Supplier<BlockHitResult> original) {
        Sampling sampling = SAMPLING.get();
        if (sampling == null || sampling.level != level || !sampling.field.blocks(context.getTo(), context.getFrom()))
            return original.get();
        sampling.intercepted = true;
        if (sampling.hit == null) sampling.hit = sampling.field.hit(context.getTo(), context.getFrom());
        Vec3 position = context.getFrom();
        return new BlockHitResult(position, Direction.UP, BlockPos.containing(position), true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void detonate(ExplosionEvent.Detonate event) {
        Level level = event.getLevel();
        if (level.isClientSide || !TurtleField.hasFields(level)) return;
        Blast active = BLAST.get();
        Blast blast = active != null && active.explosion == event.getExplosion() ? active : new Blast(level, event.getExplosion());
        if (blast.field.isEmpty()) return;
        BLAST.set(blast);
        try {
            // The same list also controls subsequent fire placement; blocked blocks never enter either phase.
            PropagationField.Hit[] blockContact = { null };
            event.getAffectedBlocks().removeIf(pos -> {
                Vec3 point = Vec3.atCenterOf(pos);
                if (!blast.field.blocks(blast.origin, point)) return false;
                if (blockContact[0] == null) blockContact[0] = blast.field.hit(blast.origin, point);
                return true;
            });
            if (blockContact[0] != null) BarrierVisuals.propagation(level, blockContact[0], .9F);
            if (blast.nativeExposure) return;
            event.getAffectedEntities().removeIf(entity -> {
                if (entity.ignoreExplosion(blast.explosion) || entity.distanceToSqr(blast.origin)
                        > 4.0 * blast.explosion.radius() * blast.explosion.radius()) return false;
                Explosion.getSeenPercent(blast.origin, entity);
                Exposure exposure = blast.get(entity);
                if (exposure == null) return false;
                if (exposure.hit != null) BarrierVisuals.propagation(level, exposure.hit, .9F);
                // Native damage has a +1 baseline at zero exposure. Remove fully intercepted targets before
                // damage, knockback, player impulse packets and onExplosionHit; do not just return zero rays.
                return exposure.denied();
            });
        } finally { if (active == null) BLAST.remove(); else BLAST.set(active); }
    }

    private ExplosionBarrier() {}
}
