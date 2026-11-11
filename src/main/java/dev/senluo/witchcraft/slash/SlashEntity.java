package dev.senluo.witchcraft.slash;

import dev.senluo.witchcraft.ritual.OwnershipRitual;
import dev.senluo.witchcraft.accessory.ProjectileBarrier;
import dev.senluo.witchcraft.motion.BezierSpeed;
import dev.senluo.witchcraft.motion.MotionPath;
import dev.senluo.witchcraft.motion.PathTravel;
import dev.senluo.witchcraft.motion.TravelDriven;
import dev.senluo.witchcraft.slash.drawing.RadialOutline;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import org.joml.Vector3f;

/** Native projectile lifecycle/tracking, with a finite swept blade instead of point collision. */
public final class SlashEntity extends Projectile implements TravelDriven {
    private static final EntityDataAccessor<Vector3f> FORWARD = SynchedEntityData.defineId(SlashEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Vector3f> BLADE = SynchedEntityData.defineId(SlashEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Vector3f> SIZE = SynchedEntityData.defineId(SlashEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Boolean> HAND_RELEASE = SynchedEntityData.defineId(SlashEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> ROLL = SynchedEntityData.defineId(SlashEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DISTANCE = SynchedEntityData.defineId(SlashEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> STRENGTH = SynchedEntityData.defineId(SlashEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> FADING = SynchedEntityData.defineId(SlashEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Vector3f> CONTACT = SynchedEntityData.defineId(SlashEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Integer> CONTACT_SERIAL = SynchedEntityData.defineId(SlashEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> FISSION_RAYS = SynchedEntityData.defineId(SlashEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<CompoundTag> RADIAL = SynchedEntityData.defineId(SlashEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<CompoundTag> RADIAL_PROGRESS = SynchedEntityData.defineId(SlashEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<Integer> VISUAL = SynchedEntityData.defineId(SlashEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> VISIBILITY = SynchedEntityData.defineId(SlashEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Optional<UUID>> CASTER = SynchedEntityData.defineId(SlashEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<CompoundTag> FRAGMENTS = SynchedEntityData.defineId(SlashEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<CompoundTag> FRAGMENT_PROGRESS = SynchedEntityData.defineId(SlashEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<CompoundTag> LAUNCH_ORIGIN = SynchedEntityData.defineId(SlashEntity.class, EntityDataSerializers.COMPOUND_TAG);
    static final int MAX_TARGETS = 128, MAX_BLOCKS = 1024;
    final Set<UUID> hitEntities = new HashSet<>();
    final Set<Long> hitBlocks = new HashSet<>();
    private UUID casterId;
    private boolean armed;
    private boolean restrained;
    private boolean destructive, penetrating, fission;
    private double criticalBase;
    private SlashPotential potential = new SlashPotential(1, 1);
    private double damageMultiplier = 1, damageBonus;
    private ItemStack tool = ItemStack.EMPTY;
    private ItemStack weapon = ItemStack.EMPTY;
    private int flightTicks, fadeTicks, contactTicks;
    private final SlashMotionView motionView;
    private float previousDistance;
    private Vec3 previousBlade;
    private SlashRotation rotation;
    private RadialSlash radial;
    private SlashFragments fragments;
    private boolean reportedRadialData;
    private MotionPath path = MotionPath.line(new Vec3(0, 0, 1));
    private BezierSpeed speed = SlashFlight.SPEED;
    private BezierSpeed initialSpeed;
    private SlashShape shape = SlashShape.DEFAULT;
    private Vec3 routeOrigin = Vec3.ZERO;
    private double travel, spentTravel, motionTicks;

    public SlashEntity(EntityType<? extends SlashEntity> type, Level level) {
        super(type, level);
        motionView = level.isClientSide ? new SlashMotionView() : null;
        noPhysics = true;
        setNoGravity(true);
    }

    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(FORWARD, new Vector3f(0, 0, 1)); builder.define(BLADE, new Vector3f(-1, 0, 0)); builder.define(ROLL, 0F);
        builder.define(SIZE, new Vector3f(24, 1.5F, 8)); builder.define(HAND_RELEASE, false);
        builder.define(DISTANCE, 0F); builder.define(STRENGTH, 1F); builder.define(FADING, false);
        builder.define(CONTACT, new Vector3f()); builder.define(CONTACT_SERIAL, 0);
        builder.define(FISSION_RAYS, 0);
        builder.define(LAUNCH_ORIGIN, new CompoundTag());
        builder.define(FRAGMENTS, new CompoundTag()); builder.define(FRAGMENT_PROGRESS, new CompoundTag());
        builder.define(RADIAL, new CompoundTag()); builder.define(RADIAL_PROGRESS, new CompoundTag());
        builder.define(VISUAL, SlashVisual.NORMAL.wireId());
        // A spawn may render before its tracked data arrives. Never flash a private blade publicly.
        builder.define(VISIBILITY, SlashVisibility.HIDDEN.wireId());
        builder.define(CASTER, Optional.empty());
    }

    public void prepare(ServerPlayer player, double initial, double multiplier, double bonus, ItemStack tool, SlashFormation.Pose pose,
                        SlashShape shape, BezierSpeed speed, Vec3 origin) {
        casterId = player.getUUID(); setOwner(player);
        entityData.set(CASTER, Optional.of(casterId));
        entityData.set(VISIBILITY, SlashVisibility.EVERYONE.wireId());
        setPos(player.getEyePosition());
        var launch = new CompoundTag();
        putVector(launch, "Origin", origin);
        entityData.set(LAUNCH_ORIGIN, launch);
        setPos(SlashLaunch.position(this, player, origin));
        this.shape = shape; this.speed = speed; initialSpeed = null;
        entityData.set(SIZE, new Vector3f((float) shape.range(), (float) shape.initialLength(), (float) shape.finalLength()));
        entityData.set(HAND_RELEASE, true);
        entityData.set(FORWARD, pose.direction().toVector3f());
        entityData.set(ROLL, pose.roll());
        entityData.set(BLADE, SlashGeometry.blade(pose.direction(), pose.roll()).toVector3f());
        routeOrigin = position(); path = MotionPath.line(pose.direction());
        travel = 0; spentTravel = 0; motionTicks = 0;
        potential = new SlashPotential(initial, initial);
        criticalBase = 0;
        entityData.set(VISUAL, SlashVisual.NORMAL.wireId());
        damageMultiplier = multiplier; damageBonus = bonus; this.tool = tool;
        setDeltaMovement(forward().scale(speed.at(0)));
    }
    /** Plan only after join callbacks have been revalidated; do not reroll while quoting payment. */
    public void planFlight(ServerPlayer player, SlashFormation.Pose pose) {
        planFlight(player, pose, null);
    }
    public void planFlight(ServerPlayer player, SlashFormation.Pose pose, Vec3 target) {
        planFlight(player, pose, target, SlashFlight.Mode.STANDARD);
    }
    public void planFlight(ServerPlayer player, SlashFormation.Pose pose, Vec3 target, SlashFlight.Mode mode) {
        Objects.requireNonNull(mode);
        Vec3 aimPoint = target == null ? SlashFlight.target(player, pose.aim(), shape.range()) : target;
        path = SlashFlight.plan(player, pose, routeOrigin, shape.range(), aimPoint, mode);
        boolean curvedSpin = mode == SlashFlight.Mode.CURVED_SPIN && path.curved();
        // A block-phasing critical has no block clearance to optimize; retain the chosen blade angle.
        float roll = restrained && !passesBlocks() && !curvedSpin
                ? SlashAimRoll.select(this, player, path, routeOrigin, aimPoint, shape, pose.roll()) : pose.roll();
        entityData.set(FORWARD, path.direction(0).toVector3f());
        entityData.set(ROLL, roll);
        entityData.set(BLADE, SlashGeometry.blade(path.direction(0), roll).toVector3f());
        // Critical curves and spin share one choice; do not multiply the branch probability
        // by the ordinary curve/spin lotteries. Existing saves already store Route and Spin.
        rotation = path.curved() && (curvedSpin || mode == SlashFlight.Mode.STANDARD
                && !restrained && player.getRandom().nextFloat() < .15)
                ? new SlashRotation(path.direction(0), blade(),
                        player.getRandom().nextBoolean() ? SlashRotation.SLOW_RATE : -SlashRotation.SLOW_RATE)
                : null;
        setDeltaMovement(path.direction(0).scale((initialSpeed == null ? speed : initialSpeed).at(0)));
    }
    public void arm() { armed = true; }
    /** The paid cast owns its weapon snapshot; swapping/removing a focus cannot change a flying blade. */
    public void weapon(ItemStack stack) {
        if (armed) throw new IllegalStateException("Cannot change an emitted slash weapon");
        weapon = stack.copyWithCount(1);
    }
    public SlashVisual visual() { return SlashVisual.fromId(entityData.get(VISUAL)); }
    public SlashVisibility visibility() { return SlashVisibility.fromId(entityData.get(VISIBILITY)); }
    /** Set the audience on the server before arming; all blade styles and contact flashes share it. */
    public void visibility(SlashVisibility value) {
        if (level().isClientSide || armed) throw new IllegalStateException("Cannot change an emitted slash audience");
        entityData.set(VISIBILITY, Objects.requireNonNull(value).wireId());
    }
    public boolean visibleTo(UUID viewer) { return visibility().visibleTo(ownerId(), viewer); }
    private void visual(SlashVisual value) {
        if (armed) throw new IllegalStateException("Cannot change an emitted slash visual");
        entityData.set(VISUAL, Objects.requireNonNull(value).wireId());
    }
    /** One committed critical outcome; payment and further hits never reroll it. */
    public void critical(double base, BezierSpeed baseSpeed) {
        if (armed || base <= 0 || !SlashCritical.validSnapshot(base, potential.initial()))
            throw new IllegalStateException("Invalid critical slash snapshot");
        criticalBase = base;
        double start = Objects.requireNonNull(baseSpeed).start();
        initialSpeed = new BezierSpeed(start, start, start, start, SlashCritical.SPEED_SWITCH);
        visual(SlashVisual.BLACK_RED);
        setDeltaMovement(forward().scale(initialSpeed.at(0)));
    }
    @Override public ItemStack getWeaponItem() { return weapon; }
    /** Cast-local policy, fixed before payment/arming and serialized with the already quoted potential. */
    public void restrained(boolean value) {
        if (armed) throw new IllegalStateException("Cannot change an emitted slash binding");
        restrained = value;
    }
    boolean restrained() { return restrained; }
    public void upgrades(boolean destructive, boolean penetrating, boolean fission) {
        if (armed) throw new IllegalStateException("Cannot change an emitted slash policy");
        this.destructive = destructive;
        this.penetrating = penetrating;
        this.fission = fission;
    }
    boolean fission() { return fission; }
    public SlashFragments fragments() { return fragments; }
    public boolean hasFragments() { return !entityData.get(FRAGMENTS).isEmpty(); }
    boolean cuts(double hardness, boolean fragment) {
        return fragment && fragments != null ? fragments.cuts(hardness) : potential.cuts(hardness);
    }
    double fragmentRange() { return Math.max(.01, shape.range() - spentTravel); }
    double fragmentSpeed() { return Math.max(.01, flightTravel(motionTicks, motionTicks + 1)); }
    void split(SlashFragments value) {
        if (fragments != null) return;
        fragments = value;
        entityData.set(FRAGMENTS, value.geometry()); entityData.set(FRAGMENT_PROGRESS, value.snapshot());
        entityData.set(FISSION_RAYS, 0);
    }
    private void advanceFragments(ServerPlayer player) {
        boolean alive = fragments.tick(this, player);
        entityData.set(FRAGMENT_PROGRESS, fragments.snapshot());
        setDeltaMovement(Vec3.ZERO);
        if (!alive && !isRemoved()) finish(position());
    }
    public int fissionRays() { return Math.clamp(entityData.get(FISSION_RAYS), 0, 12); }
    public boolean penetrating() { return penetrating; }
    double blockCost() { return SlashPotential.blockCost(destructive); }
    boolean passesBlocks() { return penetrating || restrained && criticalBase > 0; }

    /** Piecewise integral, including fractional steps crossing the 40-tick switch.
     * Both curves use the same saved motion clock; the enhanced curve never restarts at zero. */
    double flightTravel(double from, double to) {
        return SlashCritical.travel(initialSpeed, speed, from, to);
    }
    public void radial(RadialOutline value) {
        radial = new RadialSlash(value); rotation = null;
        entityData.set(RADIAL, value.save());
        entityData.set(RADIAL_PROGRESS, radial.snapshot()); entityData.set(HAND_RELEASE, false);
        setDeltaMovement(Vec3.ZERO);
    }
    public RadialSlash radial() { return radial; }
    /** Preserve the server-authored geometry kind even when a client snapshot is rejected. */
    public boolean hasRadialGeometry() { return !entityData.get(RADIAL).isEmpty(); }
    public java.util.Optional<Vec3> launchOrigin() {
        var launch = entityData.get(LAUNCH_ORIGIN);
        return launch.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(readVector(launch, "Origin"));
    }
    public UUID ownerId() { return entityData.get(CASTER).orElse(casterId); }
    public Vec3 forward() { return new Vec3(entityData.get(FORWARD)).normalize(); }
    public Vec3 blade() { return new Vec3(entityData.get(BLADE)).normalize(); }
    public Vec3 renderBlade(float partial) {
        if (motionView != null) return motionView.blade(partial, blade());
        return previousBlade == null ? blade() : previousBlade.lerp(blade(), Math.clamp(partial, 0, 1)).normalize();
    }
    public Vec3 renderForward(float partial) { return motionView == null ? forward() : motionView.forward(partial, forward()); }
    public double renderHalfLength(float partial) {
        Vector3f size = entityData.get(SIZE);
        return (size.y + (size.z - size.y) * Math.clamp(renderDistance(partial) / size.x, 0, 1)) * .5;
    }
    private double renderDistance(float partial) {
        return motionView == null ? Mth.lerp(partial, previousDistance, entityData.get(DISTANCE))
                : motionView.distance(partial, entityData.get(DISTANCE));
    }
    public double handBlend(float partial) {
        if (!entityData.get(HAND_RELEASE)) return 0;
        double t = Math.clamp(renderDistance(partial) / (entityData.get(SIZE).x * .1), 0, 1);
        return 1 - t * t * (3 - 2 * t);
    }
    public float strength() { return entityData.get(STRENGTH); }
    public float fade(float partial) { return entityData.get(FADING) ? Math.max(0, 1 - (fadeTicks + partial) / 5F) : 1; }
    public float contactFlash(float partial) { return Math.max(0, (contactTicks - partial) / 4F); }
    public Vec3 contactPoint() { return new Vec3(entityData.get(CONTACT)); }
    SlashPotential potential() { return potential; }
    ItemStack tool() { return tool; }
    void potential(SlashPotential value) {
        potential = value;
        entityData.set(STRENGTH, (float) (value.remaining() / value.initial()));
    }
    net.minecraft.world.damagesource.DamageSource damageSource(ServerPlayer player) {
        return dev.senluo.witchcraft.compat.thaumcraft.SlashFocus.damageSource(this, player, damageMultiplier, damageBonus);
    }

    @Override public void tick() {
        previousDistance = entityData.get(DISTANCE);
        previousBlade = blade();
        super.tick();
        if (contactTicks > 0) contactTicks--;
        if (level().isClientSide) {
            if (radial != null) radial.clientTick();
            if (fragments != null) fragments.clientTick();
            if (entityData.get(FADING)) fadeTicks++;
            setPos(motionView.tick(position()));
            return;
        }
        if (!armed || casterId == null || !net.neoforged.fml.ModList.get().isLoaded("thaumcraft")) { discard(); return; }
        if (entityData.get(FADING)) { if (++fadeTicks >= 5) discard(); return; }
        ServerLevel level = (ServerLevel) level();
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(casterId);
        // Offline is a pause, never offline progress or a second payment after reloading.
        if (player == null) { pauseFlight(); return; }
        if (player.level() != level || !player.isAlive()) { finish(position()); return; }
        if (OwnershipRitual.frozen(player)) { pauseFlight(); return; }
        if (fragments != null) { advanceFragments(player); return; }
        if (radial != null) {
            if (position().distanceToSqr(routeOrigin) > 1.0E-4) { finish(position()); return; }
            boolean alive = radial.tick(this, player, shape);
            entityData.set(RADIAL_PROGRESS, radial.snapshot());
            if (isRemoved()) return;
            setDeltaMovement(Vec3.ZERO);
            if (fragments != null) { advanceFragments(player); return; }
            if (!alive) finish(position());
            return;
        }
        if (flightTicks >= 160 || potential.remaining() <= 0 || spentTravel >= shape.range()) { finish(position()); return; }
        // An external teleport must not pull a saved curve back through its original target.
        if (position().distanceToSqr(routeOrigin.add(path.at(travel))) > 1.0E-4) { finish(position()); return; }
        double budget = Math.min(flightTravel(motionTicks, motionTicks + 1), shape.range() - spentTravel);
        Vec3 frameStart = position();
        var space = ProjectileBarrier.space(this);
        PathTravel.Plan plan = PathTravel.plan(path, routeOrigin, travel, budget, space);
        double plannedWork = spentTravel;
        int queryBoxes = 0;
        for (PathTravel.Segment segment : plan.segments()) {
            var step = segment.travel();
            if (!level.getWorldBorder().isWithinBounds(net.minecraft.core.BlockPos.containing(step.to()))
                    || level.isOutsideBuildHeight(net.minecraft.core.BlockPos.containing(step.to()))) { finish(position()); return; }
            double before = motionTicks + (plannedWork - spentTravel) / budget;
            for (var sweep : sweeps(step.from(), step.to(), plannedWork, plannedWork + step.work(),
                    before, before + step.work() / budget)) {
                var cover = SlashQueries.cover(sweep);
                queryBoxes += cover.size();
                if (cover.isEmpty() || queryBoxes > SlashCollision.MAX_QUERIES) { queryLimit(); finish(position()); return; }
                if (!SlashQueries.loaded(level, cover)) { setDeltaMovement(Vec3.ZERO); return; }
            }
            plannedWork += step.work();
        }
        flightTicks++;
        double frameTime = motionTicks;
        SlashCollision.Budget collisionBudget = new SlashCollision.Budget(space);
        for (PathTravel.Segment segment : plan.segments()) {
            var step = segment.travel();
            entityData.set(FORWARD, step.direction().toVector3f());
            var receipt = ProjectileBarrier.commitTravel(this, step, reachable -> SlashCollision.resolve(this, player,
                    sweeps(reachable.from(), reachable.to(), spentTravel, spentTravel + reachable.work(),
                            motionTicks, motionTicks + reachable.work() / budget), collisionBudget));
            travel = segment.pathStart() + receipt.distance();
            spentTravel = Math.min(shape.range(), spentTravel + receipt.work());
            motionTicks = Math.min(frameTime + 1, motionTicks + receipt.work() / budget);
            if (rotation != null) entityData.set(BLADE, rotation.at(motionTicks).toVector3f());
            entityData.set(DISTANCE, (float) spentTravel);
            if (isRemoved()) return;
            if (fragments != null) { advanceFragments(player); return; }
            if (collisionBudget.exhausted) queryLimit();
            if (!receipt.complete() || potential.remaining() <= 0 || spentTravel >= shape.range() - 1.0E-7) {
                finish(position()); return;
            }
        }
        if (plan.limited()) finish(position()); else setDeltaMovement(position().subtract(frameStart));
    }
    private void pauseFlight() {
        setDeltaMovement(Vec3.ZERO);
        if (radial != null) { radial.pause(); entityData.set(RADIAL_PROGRESS, radial.snapshot()); }
        if (fragments != null) { fragments.pause(); entityData.set(FRAGMENT_PROGRESS, fragments.snapshot()); }
    }
    private void queryLimit() {
        org.slf4j.LoggerFactory.getLogger(SlashEntity.class).debug(
                "Slash {} stopped at actual collision query budget (critical={}, phasing={}, work={})",
                getId(), criticalBase > 0, passesBlocks(), spentTravel);
    }
    private List<SlashGeometry.Sweep> sweeps(Vec3 from, Vec3 to, double before, double after, double timeBefore, double timeAfter) {
        double halfBefore = shape.halfLength(before), halfAfter = shape.halfLength(after);
        return List.of(rotation == null ? new SlashGeometry.Sweep(from, to, blade(), halfBefore, halfAfter)
                : rotation.sweep(from, to, halfBefore, halfAfter, timeBefore, timeAfter));
    }
    private void finish(Vec3 point) {
        ProjectileBarrier.nativeImpact(this);
        setPos(point); setDeltaMovement(Vec3.ZERO); entityData.set(FADING, true);
    }
    void contact(Vec3 point) {
        showContact(point);
        SlashSounds.contact(this, point);
    }
    void fissionContact(Vec3 point, long blades) {
        entityData.set(FISSION_RAYS, Math.clamp(64 - Long.numberOfLeadingZeros(blades), 3, 12));
        showContact(point);
    }
    void blockedContact(Vec3 point, net.minecraft.world.level.block.state.BlockState state, float hardness) {
        showContact(point);
        SlashSounds.blocked(this, point, state, hardness);
    }
    private void showContact(Vec3 point) {
        entityData.set(CONTACT, point.toVector3f());
        entityData.set(CONTACT_SERIAL, entityData.get(CONTACT_SERIAL) + 1);
    }
    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (CONTACT_SERIAL.equals(key) && entityData.get(CONTACT_SERIAL) > 0) contactTicks = 4;
    }
    /** Native assignValues invokes this after every field in the packet has been installed.
     * Per-key callbacks can see new geometry with the previous progress array. */
    @Override public void onSyncedDataUpdated(List<SynchedEntityData.DataValue<?>> values) {
        super.onSyncedDataUpdated(values);
        if (!level().isClientSide) return;
        boolean geometryChanged = false, progressChanged = false, motionChanged = false;
        boolean fragmentGeometry = false, fragmentProgress = false;
        for (var value : values) {
            fragmentGeometry |= value.id() == FRAGMENTS.id();
            fragmentProgress |= value.id() == FRAGMENT_PROGRESS.id();
            geometryChanged |= value.id() == RADIAL.id();
            progressChanged |= value.id() == RADIAL_PROGRESS.id() || value.id() == SIZE.id();
            motionChanged |= value.id() == BLADE.id() || value.id() == FORWARD.id()
                    || value.id() == DISTANCE.id() || value.id() == VISIBILITY.id();
        }
        if (fragmentGeometry || fragmentProgress) {
            try {
                SlashFragments candidate = !hasFragments() ? null : fragmentGeometry || fragments == null
                        ? SlashFragments.fromGeometry(entityData.get(FRAGMENTS)) : fragments;
                if (candidate != null) candidate.acceptSnapshot(entityData.get(FRAGMENT_PROGRESS));
                fragments = candidate;
            } catch (IllegalArgumentException invalid) {
                fragments = null;
                org.slf4j.LoggerFactory.getLogger(SlashEntity.class).warn("Ignoring invalid fragment snapshot: {}", invalid.getMessage());
            }
        }
        if (motionChanged) motionView.snapshot(position(), blade(), forward(), entityData.get(DISTANCE));
        if (!geometryChanged && !progressChanged) return;
        if (!hasRadialGeometry()) { radial = null; return; }
        try {
            RadialSlash candidate = geometryChanged || radial == null
                    ? new RadialSlash(RadialOutline.load(entityData.get(RADIAL))) : radial;
            candidate.acceptSnapshot(entityData.get(RADIAL_PROGRESS), entityData.get(SIZE).x);
            radial = candidate;
        } catch (IllegalArgumentException invalid) {
            // Reject this view; a later valid packet can reconstruct it. Never draw a linear
            // blade at the stationary carrier as a fallback for missing radial data.
            radial = null;
            if (!reportedRadialData) {
                reportedRadialData = true;
                org.slf4j.LoggerFactory.getLogger(SlashEntity.class).warn(
                        "Ignoring malformed synced radial slash data for entity {}: {}", getId(), invalid.getMessage());
            }
        }
    }
    @Override public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps) {
        if (motionView == null) super.lerpTo(x, y, z, yaw, pitch, steps);
        else motionView.position(new Vec3(x, y, z));
    }
    @Override public double lerpTargetX() { return motionView == null ? getX() : motionView.targetPosition(position()).x; }
    @Override public double lerpTargetY() { return motionView == null ? getY() : motionView.targetPosition(position()).y; }
    @Override public double lerpTargetZ() { return motionView == null ? getZ() : motionView.targetPosition(position()).z; }
    @Override public AABB getBoundingBoxForCulling() {
        if (fragments != null) return new AABB(fragments.origin(), fragments.origin()).inflate(fragments.maximumRadius() + 2);
        return getBoundingBox().inflate(radial != null ? radial.maximumRadius() + 2 : entityData.get(SIZE).z * .5 + 2);
    }
    @Override public boolean shouldRenderAtSqrDistance(double distance) {
        if (fragments != null) { double reach = fragments.origin().distanceTo(position()) + fragments.maximumRadius() + 128; return distance < reach * reach; }
        if (radial != null) { double reach = radial.maximumRadius() + 128; return distance < reach * reach; }
        return super.shouldRenderAtSqrDistance(distance);
    }

    private static CompoundTag saveSpeed(BezierSpeed value) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("Start", value.start()); tag.putDouble("First", value.first());
        tag.putDouble("Second", value.second()); tag.putDouble("End", value.end());
        tag.putDouble("Duration", value.duration()); return tag;
    }

    private static BezierSpeed readSpeed(CompoundTag tag) {
        for (String key : new String[]{"Start", "First", "Second", "End", "Duration"})
            if (!tag.contains(key, Tag.TAG_ANY_NUMERIC)) throw new IllegalArgumentException("Incomplete speed profile");
        BezierSpeed value = new BezierSpeed(tag.getDouble("Start"), tag.getDouble("First"), tag.getDouble("Second"),
                tag.getDouble("End"), tag.getDouble("Duration"));
        if (Math.max(Math.max(value.start(), value.first()), Math.max(value.second(), value.end())) > 16384
                || value.duration() > 160) throw new IllegalArgumentException("Speed profile exceeds flight bounds");
        return value;
    }

    @Override protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.put("LaunchOrigin", entityData.get(LAUNCH_ORIGIN).copy());
        tag.putInt("SlashVersion", 14); tag.putBoolean("Paid", armed);
        tag.putBoolean("Destructive", destructive); tag.putBoolean("Penetrating", penetrating);
        tag.putBoolean("Fission", fission);
        tag.putDouble("CriticalBase", criticalBase);
        tag.putBoolean("Restrained", restrained);
        tag.putString("Visual", visual().key());
        tag.putString("Visibility", visibility().key());
        if (radial != null) tag.put("Radial", radial.save());
        if (fragments != null) tag.put("Fragments", fragments.save());
        CompoundTag dimensions = new CompoundTag();
        dimensions.putDouble("Range", shape.range()); dimensions.putDouble("InitialLength", shape.initialLength());
        dimensions.putDouble("FinalLength", shape.finalLength()); tag.put("Shape", dimensions);
        tag.putBoolean("HandRelease", entityData.get(HAND_RELEASE));
        if (casterId != null) tag.putUUID("Caster", casterId);
        tag.putDouble("ForwardX", forward().x); tag.putDouble("ForwardY", forward().y); tag.putDouble("ForwardZ", forward().z);
        tag.putFloat("Roll", entityData.get(ROLL)); tag.putFloat("Distance", entityData.get(DISTANCE));
        tag.putDouble("Travel", travel); tag.putDouble("SpentTravel", spentTravel); tag.putDouble("MotionTicks", motionTicks);
        putVector(tag, "Blade", blade()); putVector(tag, "RouteOrigin", routeOrigin);
        if (rotation != null) {
            CompoundTag spin = new CompoundTag();
            putVector(spin, "Axis", rotation.axis()); putVector(spin, "InitialBlade", rotation.initialBlade());
            spin.putDouble("Rate", rotation.radiansPerTick()); tag.put("Spin", spin);
        }
        CompoundTag route = new CompoundTag(); route.putBoolean("Curved", path.curved());
        putVector(route, "First", path.first()); putVector(route, "Second", path.second());
        putVector(route, "Arrival", path.arrival()); putVector(route, "Tail", path.tail()); tag.put("Route", route);
        tag.put("Speed", saveSpeed(speed));
        if (initialSpeed != null) tag.put("InitialSpeed", saveSpeed(initialSpeed));
        tag.putDouble("InitialPotential", potential.initial()); tag.putDouble("RemainingPotential", potential.remaining());
        tag.putDouble("DamageMultiplier", damageMultiplier); tag.putDouble("DamageBonus", damageBonus);
        tag.putInt("FlightTicks", flightTicks); tag.putBoolean("Fading", entityData.get(FADING)); tag.putInt("FadeTicks", fadeTicks);
        if (!tool.isEmpty()) tag.put("Tool", tool.save(registryAccess()));
        if (!weapon.isEmpty()) tag.put("Weapon", weapon.save(registryAccess()));
        ListTag hits = new ListTag();
        for (UUID id : hitEntities) { CompoundTag hit = new CompoundTag(); hit.putUUID("Id", id); hits.add(hit); }
        tag.put("HitEntities", hits); tag.putLongArray("HitBlocks", hitBlocks.stream().mapToLong(Long::longValue).toArray());
    }
    @Override protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(LAUNCH_ORIGIN, tag.getCompound("LaunchOrigin"));
        Vec3 direction = new Vec3(tag.getDouble("ForwardX"), tag.getDouble("ForwardY"), tag.getDouble("ForwardZ"));
        double initial = tag.getDouble("InitialPotential"), remaining = tag.getDouble("RemainingPotential");
        double multiplier = tag.getDouble("DamageMultiplier"), bonus = tag.getDouble("DamageBonus");
        float roll = tag.getFloat("Roll"), distance = tag.getFloat("Distance");
        ListTag hits = tag.getList("HitEntities", Tag.TAG_COMPOUND); long[] blocks = tag.getLongArray("HitBlocks");
        int version = tag.getInt("SlashVersion");
        double savedCriticalBase = tag.getDouble("CriticalBase");
        if (version != 14 || tag.contains("Outline") || !readShape(tag)) { armed = false; return; }
        armed = tag.getBoolean("Paid") && tag.hasUUID("Caster")
                && tag.contains("Fission", Tag.TAG_BYTE)
                && tag.contains("Destructive", Tag.TAG_BYTE) && tag.contains("Penetrating", Tag.TAG_BYTE)
                && tag.contains("CriticalBase", Tag.TAG_ANY_NUMERIC)
                && tag.contains("Restrained", Tag.TAG_BYTE)
                && Double.isFinite(direction.lengthSqr()) && Math.abs(direction.lengthSqr() - 1) < .01
                && SlashCritical.validSnapshot(savedCriticalBase, initial)
                && Double.isFinite(remaining) && remaining >= 0 && remaining <= initial
                && Float.isFinite(roll) && Float.isFinite(distance) && distance >= 0 && distance <= shape.range() + .01
                && Double.isFinite(multiplier) && multiplier >= 0 && multiplier <= 1024
                && Double.isFinite(bonus) && Math.abs(bonus) <= 1_000_000
                && hits.size() <= MAX_TARGETS && blocks.length <= MAX_BLOCKS;
        if (!armed) return; // Unknown/malformed attack data must never create fresh damage or another debit.
        restrained = tag.getBoolean("Restrained");
        destructive = tag.getBoolean("Destructive");
        penetrating = tag.getBoolean("Penetrating");
        fission = tag.getBoolean("Fission");
        criticalBase = savedCriticalBase;
        entityData.set(VISUAL, (criticalBase > 0 ? SlashVisual.BLACK_RED
                : SlashVisual.NORMAL).wireId());
        casterId = tag.getUUID("Caster"); potential = new SlashPotential(initial, remaining);
        entityData.set(CASTER, Optional.of(casterId));
        entityData.set(VISIBILITY, SlashVisibility.fromKey(tag.getString("Visibility")).wireId());
        damageMultiplier = multiplier; damageBonus = bonus;
        entityData.set(FORWARD, direction.normalize().toVector3f()); entityData.set(ROLL, roll);
        entityData.set(DISTANCE, distance); previousDistance = distance;
        entityData.set(STRENGTH, (float) (remaining / initial)); entityData.set(FADING, tag.getBoolean("Fading"));
        flightTicks = Math.clamp(tag.getInt("FlightTicks"), 0, 161); fadeTicks = Math.clamp(tag.getInt("FadeTicks"), 0, 5);
        if (!readMotion(tag)) { armed = false; return; }
        initialSpeed = null;
        if (criticalBase > 0) {
            try {
                if (!tag.contains("InitialSpeed", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Missing initial speed");
                initialSpeed = readSpeed(tag.getCompound("InitialSpeed"));
            } catch (IllegalArgumentException malformed) { armed = false; return; }
        }
        if (!readRotation(tag)) { armed = false; return; }
        if (tag.contains("Radial")) {
            try {
                if (!tag.contains("Radial", Tag.TAG_COMPOUND) || rotation != null || path.curved()
                        || travel != 0 || spentTravel != 0 || motionTicks != 0 || entityData.get(HAND_RELEASE))
                    throw new IllegalArgumentException("Inconsistent radial flight state");
                CompoundTag radialData = tag.getCompound("Radial");
                if (!radialData.contains("GeometryVersion", Tag.TAG_INT))
                    throw new IllegalArgumentException("Missing radial geometry version");
                radial = RadialSlash.load(radialData, shape.range());
                entityData.set(RADIAL, radial.outline().save()); entityData.set(RADIAL_PROGRESS, radial.snapshot());
            } catch (IllegalArgumentException invalid) { armed = false; return; }
        }
        if (tag.contains("Fragments")) {
            try {
                if (!fission || !tag.contains("Fragments", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Invalid fragment carrier");
                fragments = SlashFragments.load(tag.getCompound("Fragments"));
                entityData.set(FRAGMENTS, fragments.geometry()); entityData.set(FRAGMENT_PROGRESS, fragments.snapshot());
            } catch (IllegalArgumentException invalid) { armed = false; return; }
        }
        previousBlade = blade();
        tool = ItemStack.parseOptional(registryAccess(), tag.getCompound("Tool"));
        weapon = ItemStack.parseOptional(registryAccess(), tag.getCompound("Weapon"));
        hitEntities.clear(); hitBlocks.clear();
        for (int i = 0; i < hits.size(); i++) {
            if (!hits.getCompound(i).hasUUID("Id")) { armed = false; return; }
            hitEntities.add(hits.getCompound(i).getUUID("Id"));
        }
        for (long block : blocks) hitBlocks.add(block);
    }

    private boolean readRotation(CompoundTag tag) {
        rotation = null;
        if (!tag.contains("Spin")) return true;
        if (!tag.contains("Spin", Tag.TAG_COMPOUND) || !path.curved()) return false;
        try {
            CompoundTag spin = tag.getCompound("Spin");
            if (!spin.contains("Rate", Tag.TAG_ANY_NUMERIC)) return false;
            rotation = new SlashRotation(readVector(spin, "Axis"), readVector(spin, "InitialBlade"), spin.getDouble("Rate"));
            return rotation.at(motionTicks).distanceToSqr(blade()) < 1.0E-6;
        } catch (IllegalArgumentException malformed) {
            rotation = null;
            return false;
        }
    }

    private boolean readShape(CompoundTag tag) {
        try {
            if (!tag.contains("Shape", Tag.TAG_COMPOUND)) return false;
            CompoundTag dimensions = tag.getCompound("Shape");
            for (String key : new String[]{"Range", "InitialLength", "FinalLength"})
                if (!dimensions.contains(key, Tag.TAG_ANY_NUMERIC)) return false;
            shape = new SlashShape(dimensions.getDouble("Range"), dimensions.getDouble("InitialLength"), dimensions.getDouble("FinalLength"));
            entityData.set(SIZE, new Vector3f((float) shape.range(), (float) shape.initialLength(), (float) shape.finalLength()));
            entityData.set(HAND_RELEASE, tag.getBoolean("HandRelease"));
            return true;
        } catch (IllegalArgumentException malformed) { return false; }
    }

    private boolean readMotion(CompoundTag tag) {
        if (!tag.contains("Route", Tag.TAG_COMPOUND) || !tag.contains("Speed", Tag.TAG_COMPOUND)
                || !tag.contains("Travel", Tag.TAG_ANY_NUMERIC) || !tag.contains("MotionTicks", Tag.TAG_ANY_NUMERIC)) return false;
        try {
            Vec3 blade = readVector(tag, "Blade"); routeOrigin = readVector(tag, "RouteOrigin");
            travel = tag.getDouble("Travel"); motionTicks = tag.getDouble("MotionTicks");
            if (!tag.contains("SpentTravel", Tag.TAG_ANY_NUMERIC)) return false;
            spentTravel = tag.getDouble("SpentTravel");
            if (Math.abs(blade.lengthSqr() - 1) > .01 || !Double.isFinite(travel) || travel < 0 || travel > shape.range()
                    || !Double.isFinite(spentTravel) || spentTravel < travel - 1.0E-7 || spentTravel > shape.range()
                    || !Double.isFinite(motionTicks) || motionTicks < 0 || motionTicks > 160) return false;
            CompoundTag route = tag.getCompound("Route");
            if (!route.contains("Curved", Tag.TAG_BYTE)) return false;
            path = route.getBoolean("Curved")
                    ? MotionPath.bezier(readVector(route, "First"), readVector(route, "Second"), readVector(route, "Arrival"))
                    : MotionPath.line(readVector(route, "Tail"));
            speed = readSpeed(tag.getCompound("Speed"));
            if (path.arrivalDistance() > shape.range() + 1.0E-6
                    || position().distanceToSqr(routeOrigin.add(path.at(travel))) > 1.0E-4) return false;
            entityData.set(BLADE, blade.normalize().toVector3f()); entityData.set(DISTANCE, (float) spentTravel); previousDistance = (float) spentTravel;
            return true;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static void putVector(CompoundTag tag, String key, Vec3 value) {
        CompoundTag vector = new CompoundTag();
        vector.putDouble("X", value.x); vector.putDouble("Y", value.y); vector.putDouble("Z", value.z); tag.put(key, vector);
    }
    private static Vec3 readVector(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Missing motion vector");
        CompoundTag vector = tag.getCompound(key);
        for (String axis : new String[]{"X", "Y", "Z"})
            if (!vector.contains(axis, Tag.TAG_ANY_NUMERIC)) throw new IllegalArgumentException("Incomplete motion vector");
        Vec3 value = new Vec3(vector.getDouble("X"), vector.getDouble("Y"), vector.getDouble("Z"));
        if (!MotionPath.finite(value) || Math.abs(value.x) > 60_000_000 || Math.abs(value.y) > 60_000_000 || Math.abs(value.z) > 60_000_000)
            throw new IllegalArgumentException("Invalid motion vector");
        return value;
    }
}
