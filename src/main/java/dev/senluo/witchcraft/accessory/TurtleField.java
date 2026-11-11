package dev.senluo.witchcraft.accessory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;

/** Equipment discovery and spatial adapters; geometry does not own world or player lifecycles. */
public final class TurtleField {
    private static final double PROJECTILE_RADIUS = 4;
    private static final Vec3 LOCK_PROBE = new Vec3(0, 1, 0);
    private record Sources(long tick, List<Player> wearers) {}
    private static final Map<Level, Sources> SOURCES = Collections.synchronizedMap(new WeakHashMap<>());
    private static Predicate<Player> equipment = ignored -> false;
    private static boolean enabled;
    private record MovementScope(Entity entity, List<MotionConstraints.Boundary> boundaries) {}
    private static final ThreadLocal<MovementScope> MOVEMENT = new ThreadLocal<>();

    public static void equipmentQuery(Predicate<Player> query) {
        equipment = query;
        enabled = true;
    }

    public static void invalidate(Level level) {
        SOURCES.remove(level);
    }

    public static void unload(Level level) {
        invalidate(level);
        BarrierVisuals.invalidate(level);
    }

    /** Client presentation can discard a stale contact without re-running any geometry. */
    public static boolean isSourceActive(Player player) {
        return enabled && active(player, player.level()) && wearers(player.level()).contains(player);
    }

    private static List<Player> wearers(Level level) {
        if (!enabled) return List.of();
        Sources cached = SOURCES.get(level);
        if (cached == null || cached.tick != level.getGameTime()) {
            List<Player> found = new ArrayList<>();
            for (Player player : level.players()) {
                if (active(player, level) && equipment.test(player)) found.add(player);
            }
            cached = new Sources(level.getGameTime(), List.copyOf(found));
            SOURCES.put(level, cached);
        }
        return cached.wearers;
    }

    private static boolean active(Player player, Level level) {
        return player.level() == level && player.isAlive() && !player.isRemoved() && !player.isSpectator();
    }

    private static boolean exempt(LivingEntity target, Player wearer) {
        return target instanceof Player || !target.isAlive() || target.isAlliedTo(wearer)
                || target instanceof OwnableEntity ownable && wearer.getUUID().equals(ownable.getOwnerUUID())
                || target.getRootVehicle().getSelfAndPassengers().anyMatch(passenger -> passenger instanceof Player);
    }

    /** Pure quote, also used before tree motion accounting so blocked travel is not sold as income. */
    public static Vec3 limitMotion(Entity entity, Vec3 requested) {
        return constrain(entity, requested, true);
    }

    /** Final native collision may slide/step in a different direction; apply the hard boundary again. */
    public static Vec3 hardMotion(Entity entity, Vec3 requested) {
        return constrain(entity, requested, false);
    }

    private static Vec3 constrain(Entity entity, Vec3 requested, boolean slowdown) {
        if (!(entity instanceof LivingEntity target) || entity instanceof Player
                || entity instanceof net.minecraft.world.entity.decoration.ArmorStand
                || !(entity.level() instanceof ServerLevel level) || !enabled) return requested;
        if (requested.lengthSqr() == 0) return requested;
        MovementScope scope = MOVEMENT.get();
        List<MotionConstraints.Boundary> boundaries = scope != null && scope.entity == entity
                ? scope.boundaries : boundaries(target, level);
        if (boundaries.isEmpty()) return requested;
        return MotionConstraints.apply(target.getBoundingBox().getCenter(), requested, boundaries, slowdown);
    }

    private static List<MotionConstraints.Boundary> boundaries(LivingEntity target, ServerLevel level) {
        List<Player> sources = wearers(level);
        if (sources.isEmpty()) return List.of();
        List<MotionConstraints.Boundary> boundaries = new ArrayList<>(sources.size() * 2);
        for (Player wearer : sources) {
            if (!active(wearer, level) || exempt(target, wearer)) continue;
            ReachBoundary.append(boundaries, target, wearer);
        }
        return boundaries;
    }

    /** One native move shares reach/field geometry across mapping, collision and final position checks. */
    public static void move(Entity entity, Vec3 requested, Consumer<Vec3> original) {
        MovementScope previous = MOVEMENT.get();
        if (!enabled || !(entity instanceof LivingEntity target) || entity instanceof Player
                || entity instanceof net.minecraft.world.entity.decoration.ArmorStand
                || !(entity.level() instanceof ServerLevel level) || requested.lengthSqr() == 0
                || ProjectileBarrier.accepts(entity)) {
            original.accept(movement(entity, requested));
            return;
        }
        List<MotionConstraints.Boundary> snapshot = boundaries(target, level);
        if (snapshot.isEmpty()) {
            original.accept(movement(entity, requested));
            return;
        }
        MOVEMENT.set(new MovementScope(entity, snapshot));
        try {
            // Only this committed move asks for diagnostic output; income quotes and collision probes stay pure.
            MotionConstraints.Contact contact = BarrierVisuals.canObserve(entity) ? new MotionConstraints.Contact() : null;
            Vec3 start = target.getBoundingBox().getCenter();
            Vec3 allowed = MotionConstraints.apply(start, requested, snapshot, true, contact);
            if (allowed.lengthSqr() == 0) entity.setDeltaMovement(Vec3.ZERO);
            original.accept(allowed);
            if (contact != null && entity.level() == level && !entity.isRemoved())
                BarrierVisuals.moved(entity, start, requested, allowed, contact);
        }
        finally {
            if (previous == null) MOVEMENT.remove(); else MOVEMENT.set(previous);
        }
    }

    public static Vec3 movement(Entity entity, Vec3 requested) {
        if (ProjectileBarrier.accepts(entity)) return ProjectileBarrier.movement(entity, requested);
        Vec3 result = limitMotion(entity, requested);
        if (result.lengthSqr() == 0 && requested.lengthSqr() > 0) entity.setDeltaMovement(Vec3.ZERO);
        return result;
    }

    static boolean hasFields(Level level) {
        return !wearers(level).isEmpty();
    }

    /** One short-lived quote for an explosion, splash or cloud application; never loads chunks. */
    public static PropagationField propagation(Level level) {
        return propagation(level, null);
    }

    static PropagationField flightField(Entity entity) {
        return propagation(entity.level(), ProjectileBarrier.owner(entity));
    }

    public static PropagationField propagation(Level level, UUID exemptOwner) {
        if (level == null) return PropagationField.EMPTY;
        List<PropagationField.Source> found = null;
        for (Player wearer : wearers(level)) {
            if (!active(wearer, level) || wearer.getUUID().equals(exemptOwner)) continue;
            Vec3 center = wearer.getBoundingBox().getCenter();
            if (found == null) found = new ArrayList<>();
            found.add(new PropagationField.Source(wearer.getId(), center, PROJECTILE_RADIUS));
        }
        return found == null ? PropagationField.EMPTY : new PropagationField(found);
    }

    static double projectileEntry(Entity projectile, Vec3 movement) {
        return rayEntry(projectile.level(), projectile.getBoundingBox().getCenter(), movement, ProjectileBarrier.owner(projectile));
    }

    /** Shared finite ray query. No world/entity scan: only the cached equipped players in this dimension. */
    public static double rayEntry(Level level, Vec3 start, Vec3 movement, UUID exemptOwner) {
        return rayEntry(level, start, movement, exemptOwner, null);
    }

    /** Presentation query sharing the same nearest-boundary calculation and equipment cache. */
    public static PropagationField.Hit rayContact(Level level, Vec3 start, Vec3 end, UUID exemptOwner) {
        Vec3 delta = end.subtract(start);
        RaySource source = new RaySource();
        double entry = rayEntry(level, start, delta, exemptOwner, source);
        if (!Double.isFinite(entry)) return null;
        Vec3 direction = delta.lengthSqr() > 1.0E-12 ? delta.normalize() : new Vec3(0, 1, 0);
        return new PropagationField.Hit(source.id, start.add(delta.scale(entry)), direction);
    }

    static final class RaySource { int id = -1; }

    static double rayEntry(Level level, Vec3 start, Vec3 movement, UUID exemptOwner, RaySource source) {
        double earliest = Double.POSITIVE_INFINITY;
        for (Player wearer : wearers(level)) {
            if (!active(wearer, level) || wearer.getUUID().equals(exemptOwner)) continue;
            Vec3 center = wearer.getBoundingBox().getCenter();
            double entry = PropagationField.entry(start, movement, center, PROJECTILE_RADIUS);
            if (entry < earliest) {
                earliest = entry;
                if (source != null) source.id = wearer.getId();
            }
        }
        return earliest;
    }

    public static void tick(Entity entity, Runnable original) {
        ProjectileBarrier.tick(entity, original);
    }

    /** Null is the allocation-free fast path for all unrelated setPos calls, including packet updates. */
    public static Vec3 position(Entity entity, double x, double y, double z) {
        if (ProjectileBarrier.accepts(entity)) return ProjectileBarrier.position(entity, x, y, z);
        if (!enabled || !(entity.level() instanceof ServerLevel) || !entity.isAddedToLevel()
                || !(entity instanceof LivingEntity) || entity instanceof Player) return null;
        Vec3 movement = new Vec3(x - entity.getX(), y - entity.getY(), z - entity.getZ());
        if (movement.lengthSqr() == 0) return null;
        Vec3 allowed = hardMotion(entity, movement);
        if (allowed.equals(movement)) return null;
        if (allowed.lengthSqr() == 0) entity.setDeltaMovement(Vec3.ZERO);
        return entity.position().add(allowed);
    }

    public static Vec3 projectileVelocity(Entity entity, Vec3 velocity) {
        if (ProjectileBarrier.accepts(entity)) return ProjectileBarrier.velocity(entity, velocity);
        // A collision/knockback impulse can be written after the creature's own tick.
        // Do not retain it while the whole entity is locked inside the inner boundary.
        if (velocity.lengthSqr() > 0 && entity.isAddedToLevel() && entity instanceof LivingEntity
                && hardMotion(entity, LOCK_PROBE).lengthSqr() == 0) return Vec3.ZERO;
        return velocity;
    }

    public static void impact(ProjectileImpactEvent event) {
        ProjectileBarrier.impact(event);
    }

    private TurtleField() {}
}
