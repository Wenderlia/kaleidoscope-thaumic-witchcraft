package dev.senluo.witchcraft.accessory;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import dev.senluo.witchcraft.motion.PathTravel;
import dev.senluo.witchcraft.motion.TravelDriven;
import dev.senluo.witchcraft.motion.TravelStep;
import com.google.common.collect.MapMaker;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;

/** Entity flight adapter. Geometry and native updates are separate from interception lifetime policy. */
public final class ProjectileBarrier {
    /** AUTOMATIC retains recoverable arrows/hooks, dissipates momentum-driven attacks,
     * and bounds the stalled lifetime of other captured flight entities. */
    public enum InterceptionPolicy { AUTOMATIC, DISSIPATE, PRESERVE }
    private record Adapter(Function<Entity, UUID> owner, InterceptionPolicy policy) {}
    private static final Map<EntityType<?>, Adapter> ADAPTERS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Step> STEP = new ThreadLocal<>();
    private static final ThreadLocal<Entity> COMMIT = new ThreadLocal<>();
    // Identity-based weak keys: client/server copies can have the same numeric entity id.
    // Only explicit new requests need an entry, never each frame of ordinary constrained flight.
    private static final Map<Entity, Vec3> INPUTS = new MapMaker().weakKeys().makeMap();
    private static final Map<Entity, StalledFlight> STALLED = new MapMaker().weakKeys().makeMap();
    private static final int MAX_STALLED_TICKS = 200;
    // Accumulate progress from an anchor, so tiny real movement is not rounded
    // away every tick and trajectory retiming cannot repeatedly reset the clock.
    private static final double MIN_PROGRESS_SQUARED = 1.0 / (32 * 32);

    private static final class StalledFlight {
        final ResourceKey<Level> dimension;
        final UUID owner;
        Vec3 anchor;
        long lastTick = Long.MIN_VALUE;
        int ticks;
        StalledFlight(Entity entity) {
            dimension = entity.level().dimension();
            owner = ProjectileBarrier.owner(entity);
            anchor = entity.position();
        }
    }

    private static final class Step {
        final Entity entity;
        final Level level;
        FlightPath path;
        Vec3 start, destination, velocity;
        final long heldTicks;
        boolean impacted;
        Step(Entity entity, FlightPath path) {
            this.entity = entity; this.level = entity.level();
            heldTicks = entity.getPersistentData().getLong("WitchcraftHeldTicks");
            retime(path);
        }
        void retime(FlightPath path) {
            this.path = path;
            start = entity.position();
            destination = path.active() ? path.at(level.getGameTime() + 1) : start;
            velocity = path.active() ? destination.subtract(start) : entity.getDeltaMovement();
        }
    }

    /** Register non-Projectile flight entities during setup. Null owner means no owner exemption. */
    public static <T extends Entity> void registerFlightType(EntityType<T> type, Function<? super T, UUID> owner) {
        registerFlightType(type, owner, InterceptionPolicy.AUTOMATIC);
    }

    /** A custom recoverable/persistent projectile can opt out without changing the spatial solver. */
    @SuppressWarnings("unchecked")
    public static <T extends Entity> void registerFlightType(EntityType<T> type, Function<? super T, UUID> owner,
                                                          InterceptionPolicy policy) {
        Objects.requireNonNull(type); Objects.requireNonNull(owner); Objects.requireNonNull(policy);
        if (EntityType.PLAYER.equals(type)) throw new IllegalArgumentException("Players are not flight projectiles");
        ADAPTERS.put(type, new Adapter(entity -> owner.apply((T) entity), policy));
    }

    public static boolean accepts(Entity entity) {
        return entity instanceof Projectile || entity.getType() != null && ADAPTERS.containsKey(entity.getType());
    }

    /** Read the same native/registered owner identity used by spatial flight interception. */
    public static UUID owner(Entity entity) {
        Adapter adapter = ADAPTERS.get(entity.getType());
        if (adapter != null) return adapter.owner.apply(entity);
        Entity owner = entity instanceof Projectile projectile ? projectile.getOwner() : null;
        return owner == null ? null : owner.getUUID();
    }

    public static FlightPath path(Entity entity) {
        FlightPath state = entity.getExistingDataOrNull(AccessoryContent.FLIGHT_PATH);
        return state == null ? FlightPath.FREE : state;
    }

    private static void sync(Entity entity, FlightPath path) {
        // On clients this is short-lived prediction; the synced server attachment always replaces it.
        if (!path(entity).equals(path))
            entity.setData(AccessoryContent.FLIGHT_PATH, path);
        // Also observes authoritative attachment snapshots on the next real client tick.
        // Presentation keys an active trajectory by entity, never by its retiming epoch.
        BarrierVisuals.flight(entity, path);
    }

    private static Step step(Entity entity) {
        Step step = STEP.get();
        return step != null && step.entity == entity && step.level == entity.level() ? step : null;
    }

    public static void tick(Entity entity, Runnable original) {
        if (!accepts(entity)) { original.run(); return; }
        if (entity.isRemoved()) { INPUTS.remove(entity); STALLED.remove(entity); return; }
        if (entity instanceof TravelDriven) { original.run(); return; }
        boolean client = entity.level().isClientSide;
        Vec3 input = INPUTS.remove(entity);
        FlightPath old = path(entity), path = quote(entity, old, input);
        // Capture, late native movement and denied impacts use the same policy.
        // A disappearing field does not abandon an already stalled flight entity.
        if (path.active() && intercepted(entity)) return;
        sync(entity, path);
        if (!path.active() && old.active())
            entity.setDeltaMovement(input == null ? Vec3.ZERO : input);
        if (!TurtleField.hasFields(entity.level()) && (client || !STALLED.containsKey(entity))) {
            original.run();
            return;
        }
        Step previous = STEP.get(), current = new Step(entity, path);
        STEP.set(current);
        boolean completed = false;
        try {
            if (current.path.active()) entity.setDeltaMovement(current.velocity);
            original.run();
            completed = true;
            if (current.path.active() && !current.impacted && entity.level() == current.level) {
                // Native propulsion can first enter a field through setPos during this tick.
                if (intercepted(entity)) return;
                entity.setYRot(current.path.yaw()); entity.setXRot(current.path.pitch());
                entity.yRotO = current.path.yaw(); entity.xRotO = current.path.pitch();
            }
        } finally {
            if (previous == null) STEP.remove(); else STEP.set(previous);
            boolean clockPaused = entity.getPersistentData().getLong("WitchcraftHeldTicks") > current.heldTicks
                    && entity.position().distanceToSqr(current.start) < 1.0E-10;
            if (!client) {
                boolean nativeExit = entity.level() != current.level || entity.isRemoved() || current.impacted
                        || current.path.active() && !clockPaused
                        && entity.position().distanceToSqr(current.destination) > 1.0E-4;
                if (nativeExit) {
                    STALLED.remove(entity);
                    if (current.path.active()) sync(entity, FlightPath.FREE);
                } else if (completed && !clockPaused) {
                    finishFlightTick(entity, current.path.active());
                }
            }
        }
    }

    private static FlightPath quote(Entity entity, FlightPath path, Vec3 input) {
        if (!TurtleField.hasFields(entity.level())) return FlightPath.FREE;
        // A real teleport/native collision invalidates a trajectory; never pull an entity back to its old path.
        long now = entity.level().getGameTime();
        if (!entity.level().isClientSide && path.active() && entity.position().distanceToSqr(path.at(now)) > 1.0E-4
                && (path.rate() == 1 || entity.position().distanceToSqr(path.at(now - 1)) > 1.0E-4))
            path = FlightPath.FREE;
        if (input != null && path.active()) {
            if (input.lengthSqr() == 0)
                return new FlightPath(true, entity.position(), path.limit(), Vec3.ZERO, now,
                        path.yaw(), path.pitch(), path.rate());
            // A new direction must be quoted against the live field, never drag toward an old endpoint.
            Vec3 probe = input.normalize().scale(Math.max(input.length() + FlightPath.MAPPING_WIDTH,
                    entity.position().distanceTo(path.limit()) + MotionConstraints.EPSILON));
            double entry = TurtleField.projectileEntry(entity, probe);
            if (!Double.isFinite(entry)) return FlightPath.FREE;
            float yaw = (float) Math.toDegrees(Math.atan2(input.x, input.z));
            float pitch = (float) Math.toDegrees(Math.atan2(input.y, input.horizontalDistance()));
            return new FlightPath(true, entity.position(), entity.position().add(probe.scale(entry)), input,
                    now, yaw, pitch, path.rate());
        }
        Vec3 request = path.active() ? path.limit().subtract(entity.position())
                : input != null ? input : entity.getDeltaMovement();
        Vec3 probe = path.active() ? request : extend(request);
        double entry = TurtleField.projectileEntry(entity, probe);
        if (!Double.isFinite(entry)) return FlightPath.FREE;
        if (!path.active() || entry < 1 - MotionConstraints.EPSILON) {
            Vec3 limit = entity.position().add(probe.scale(entry));
            // Do not restart the clock for tiny floating-point noise at a stationary boundary.
            if (!path.active() || limit.distanceToSqr(path.limit()) > 1.0E-10)
                return FlightPath.begin(entity, limit, path.active() ? path.input() : request, path.active() ? path.rate() : 1);
        }
        return path;
    }

    /** Called by the existing tree clock decision, so flight never performs a second tree lookup. */
    public static void simulationRate(Entity entity, double rate) {
        Step current = step(entity);
        if (current == null || !current.path.active() || current.path.rate() == rate) return;
        FlightPath path = new FlightPath(true, entity.position(), current.path.limit(), current.path.input(), entity.level().getGameTime(),
                current.path.yaw(), current.path.pitch(), rate);
        current.retime(path);
        sync(entity, path);
        entity.setDeltaMovement(current.velocity);
    }

    public static Vec3 movement(Entity entity, Vec3 request) {
        if (COMMIT.get() == entity) return request;
        Step current = step(entity);
        if (current == null || !current.path.active() || current.impacted) return request;
        double remaining = Math.max(0, current.velocity.length() - entity.position().distanceTo(current.start));
        double distance = request.length();
        Vec3 clipped = distance > remaining && distance > 0 ? request.scale(remaining / distance) : request;
        if (!entity.level().isClientSide) {
            double entry = TurtleField.projectileEntry(entity, clipped);
            if (Double.isFinite(entry)) clipped = clipped.scale(Math.max(0, entry - MotionConstraints.EPSILON));
        }
        return clipped;
    }

    /** Packet corrections cannot compete with the coherent trajectory while it owns client flight. */
    public static Vec3 position(Entity entity, double x, double y, double z) {
        if (COMMIT.get() == entity) return null;
        Step current = step(entity);
        if (current == null) {
            if (entity.level() != null && entity.level().isClientSide && path(entity).active()) return entity.position();
            return null;
        }
        if (current.impacted) return null;
        Vec3 request = new Vec3(x - entity.getX(), y - entity.getY(), z - entity.getZ());
        if (!current.path.active()) {
            // Some flight entities commit positions directly or change their propulsion during tick.
            // Quote that actual segment before it is submitted, rather than guessing from the old velocity.
            Vec3 probe = extend(request);
            double entry = TurtleField.projectileEntry(entity, probe);
            if (!Double.isFinite(entry)) return null;
            current.retime(FlightPath.begin(entity, entity.position().add(probe.scale(entry)), request));
            sync(entity, current.path);
            entity.setDeltaMovement(current.velocity);
        }
        Vec3 allowed = movement(entity, request);
        return allowed.equals(request) ? null : entity.position().add(allowed);
    }

    public static Vec3 velocity(Entity entity, Vec3 requested) {
        if (COMMIT.get() == entity) return requested;
        Step current = step(entity);
        if (current != null && current.path.active() && !current.impacted) return current.velocity;
        if (entity.level() != null && entity.level().isClientSide && path(entity).active() && current == null)
            return entity.getDeltaMovement();
        if (current == null && path(entity).active() && Double.isFinite(requested.lengthSqr()))
            INPUTS.put(entity, requested);
        return requested;
    }

    /** Flight adapters with their own changing propulsion can explicitly submit a new virtual request. */
    public static void submitTravel(Entity entity, Vec3 requested) {
        if (!accepts(entity) || !Double.isFinite(requested.lengthSqr())) return;
        Step current = step(entity);
        if (current == null) {
            INPUTS.put(entity, requested);
            if (!path(entity).active()) entity.setDeltaMovement(requested);
            return;
        }
        FlightPath replacement = quote(entity, current.path, requested);
        current.retime(replacement);
        // An explicit subsequent flight segment starts a new movement quote after a native impact.
        current.impacted = false;
        sync(entity, replacement);
        entity.setDeltaMovement(replacement.active() ? current.velocity : requested);
    }

    /** Snapshot equipment once for a whole planned path. This method does not move or charge anything. */
    public static FlightSpace space(Entity entity) {
        return new FlightSpace(TurtleField.flightField(entity), entity.getBoundingBox().getCenter().subtract(entity.position()));
    }

    /** Contact presentation for committed procedural geometry whose carrier need not move with its front. */
    public static void observeTravel(Entity carrier, TravelStep quote, double fraction) {
        if (!carrier.level().isClientSide && quote.stretched() && fraction > 0 && quote.work() > 0)
            BarrierVisuals.travel(carrier, quote, quote.prefix(fraction).position());
    }

    public static final class FlightSpace implements PathTravel.Space {
        private final PropagationField field;
        private final Vec3 offset;
        private FlightSpace(PropagationField field, Vec3 offset) { this.field = field; this.offset = offset; }
        @Override public TravelStep quote(Vec3 from, Vec3 direction, double budget, double maximum) {
            return TravelStep.quote(from, direction, budget, maximum,
                    field.gap(from.add(offset), direction, budget + FlightPath.MAPPING_WIDTH), FlightPath.MAPPING_WIDTH);
        }
        /** Authored spatial geometry already specifies its actual world position; no entity-centre offset. */
        public TravelStep quotePoint(Vec3 from, Vec3 direction, double budget, double maximum) {
            return TravelStep.quote(from, direction, budget, maximum,
                    field.gap(from, direction, budget + FlightPath.MAPPING_WIDTH), FlightPath.MAPPING_WIDTH);
        }
        /** A finite, committable prefix in [0,1]. Raw geometry uses +infinity for no intersection. */
        public double segmentEntry(Vec3 from, Vec3 to, Vec3 axis, double halfBefore, double halfAfter, double padding) {
            double entry = field.segmentEntry(from, to, axis, halfBefore, halfAfter, padding);
            // Translate the geometry sentinel here, before any consumer measures a travel receipt.
            // Invalid geometry cannot grant unchecked movement; no hit permits the complete step.
            return Double.isNaN(entry) ? 0 : Math.clamp(entry, 0, 1);
        }
    }

    /** Execute one native move and native contact callback, then return only its committed prefix.
     * The callback receives the reachable quote and returns its collision fraction. It must not move
     * the entity. Consumers apply this receipt to their own range; cancelled previews never debit. */
    public static TravelStep.Receipt commitTravel(Entity entity, TravelStep quote, ToDoubleFunction<TravelStep> collision) {
        if (!accepts(entity) || entity.level().isClientSide) throw new IllegalArgumentException("Server flight required");
        if (entity.position().distanceToSqr(quote.from()) > 1.0E-8)
            throw new IllegalStateException("Stale flight quote");
        Entity previous = COMMIT.get();
        COMMIT.set(entity);
        try {
            nativeImpact(entity); INPUTS.remove(entity);
            if (path(entity).active()) sync(entity, FlightPath.FREE);
            entity.move(net.minecraft.world.entity.MoverType.SELF, quote.movement());
            Vec3 actual = entity.position().subtract(quote.from());
            double length = Math.clamp(actual.dot(quote.direction()), 0, quote.distance());
            double tolerance = Math.max(1.0E-8, 8 * Math.ulp(Math.max(Math.abs(entity.getX()), Math.abs(entity.getZ()))));
            if (actual.subtract(quote.direction().scale(length)).lengthSqr() > tolerance * tolerance) {
                // A normal native wall-slide is not a prefix of this authored path.
                entity.setPos(quote.from()); entity.setDeltaMovement(Vec3.ZERO);
                return new TravelStep.Receipt(quote.from(), 0, 0, false);
            }
            double nativeFraction = quote.distance() <= tolerance || actual.distanceToSqr(quote.movement()) <= tolerance * tolerance
                    ? 1 : length / quote.distance();
            TravelStep reachable = nativeFraction == 1 ? quote : new TravelStep(quote.from(), quote.direction(),
                    length, quote.spentAt(nativeFraction), quote.gap(), quote.width());
            Level originalLevel = entity.level();
            Vec3 movedPosition = entity.position();
            double fraction = collision.applyAsDouble(reachable);
            TravelStep.Receipt receipt = reachable.prefix(fraction);
            // Native impact hooks can teleport or remove a projectile. Never undo their transition.
            if (entity.level() != originalLevel || entity.position().distanceToSqr(movedPosition) > tolerance * tolerance)
                return new TravelStep.Receipt(entity.position(), 0, 0, false);
            if (!entity.isRemoved()) {
                entity.setPos(receipt.position());
                entity.setDeltaMovement(receipt.position().subtract(quote.from()));
                if (quote.stretched() && receipt.work() > 0) BarrierVisuals.travel(entity, quote);
            }
            return new TravelStep.Receipt(receipt.position(), receipt.distance(), receipt.work(), nativeFraction == 1 && receipt.complete());
        } finally {
            if (previous == null) COMMIT.remove(); else COMMIT.set(previous);
        }
    }

    private static Vec3 extend(Vec3 request) {
        double length = request.length();
        return length > 0 ? request.scale((length + FlightPath.MAPPING_WIDTH) / length) : request;
    }

    public static float rotation(Entity entity, float requested, boolean yaw) {
        if (!accepts(entity)) return requested;
        Step current = step(entity);
        FlightPath path = current != null && !current.impacted ? current.path
                : current == null && entity.level() != null && entity.level().isClientSide ? path(entity) : FlightPath.FREE;
        return path.active() ? yaw ? path.yaw() : path.pitch() : requested;
    }

    /** Custom entity adapters may use this at their pre-impact event; no blanket damage cancellation. */
    public static boolean blocksImpact(Entity entity, Vec3 hit) {
        Step current = step(entity);
        Vec3 delta = hit.subtract(entity.position());
        return current != null && current.path.active() && current.velocity.lengthSqr() < 1.0E-16
                || Double.isFinite(TurtleField.projectileEntry(entity, delta));
    }

    public static void impact(ProjectileImpactEvent event) {
        Entity entity = event.getProjectile();
        if (blocksImpact(entity, event.getRayTraceResult().getLocation())) {
            event.setCanceled(true);
            intercepted(entity);
        }
        else nativeImpact(entity);
    }

    private static InterceptionPolicy policy(Entity entity) {
        Adapter adapter = ADAPTERS.get(entity.getType());
        if (adapter != null && adapter.policy != InterceptionPolicy.AUTOMATIC) return adapter.policy;
        // Recoverable flight items keep their native lifecycle. A flight adapter
        // cannot implicitly turn a mob, dropped stack or vehicle into ammunition.
        if (entity instanceof AbstractArrow || entity instanceof FishingHook || entity instanceof LivingEntity
                || entity instanceof ItemEntity || entity instanceof VehicleEntity) return InterceptionPolicy.PRESERVE;
        // This native family propels itself using normalize(current velocity).
        // Zero momentum cannot restart it; it also has no general expiry timer.
        if (entity instanceof AbstractHurtingProjectile) return InterceptionPolicy.DISSIPATE;
        return InterceptionPolicy.AUTOMATIC;
    }

    /** Called only with proven field contact, never merely because an entity is stationary. */
    private static boolean intercepted(Entity entity) {
        if (entity.level().isClientSide || entity.isRemoved()) return false;
        switch (policy(entity)) {
            case DISSIPATE -> { discardFlight(entity); return true; }
            case PRESERVE -> STALLED.remove(entity);
            case AUTOMATIC -> STALLED.computeIfAbsent(entity, StalledFlight::new);
        }
        return false;
    }

    private static void finishFlightTick(Entity entity, boolean constrained) {
        StalledFlight stalled = STALLED.get(entity);
        if (stalled == null) return;
        if (policy(entity) == InterceptionPolicy.PRESERVE || !stalled.dimension.equals(entity.level().dimension())
                || !Objects.equals(stalled.owner, owner(entity))) { STALLED.remove(entity); return; }
        long tick = entity.level().getGameTime();
        if (stalled.lastTick == tick) return;
        stalled.lastTick = tick;
        double progress = entity.position().distanceToSqr(stalled.anchor);
        if (!Double.isFinite(progress)) { STALLED.remove(entity); return; }
        if (progress > MIN_PROGRESS_SQUARED) {
            if (!constrained) { STALLED.remove(entity); return; }
            stalled.anchor = entity.position();
            stalled.ticks = 0;
        } else if (++stalled.ticks >= MAX_STALLED_TICKS) {
            discardFlight(entity);
        }
    }

    /** Quiet server removal: no fabricated impact, explosion, cloud or item drop. */
    private static void discardFlight(Entity entity) {
        INPUTS.remove(entity);
        STALLED.remove(entity);
        nativeImpact(entity);
        sync(entity, FlightPath.FREE);
        entity.discard();
    }

    /** An allowed native hit/deflection ends trajectory ownership before the impact changes velocity. */
    public static void nativeImpact(Entity entity) {
        STALLED.remove(entity);
        Step current = step(entity);
        if (current != null) current.impacted = true;
    }

    private ProjectileBarrier() {}
}
