package dev.senluo.witchcraft.slash;

import dev.senluo.witchcraft.accessory.ProjectileBarrier;
import dev.senluo.witchcraft.motion.TravelStep;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Traveling fragments share one native carrier and collision budget. Large multiplicities are
 * grouped on at most 64 paths, but every accepted contact consumes exactly one logical blade.
 * No recursive splitting, damage aggregation, or permanently registered world tasks. */
public final class SlashFragments {
    public static final double HALF_LENGTH = .35;
    private static final int MAX_PATHS = 64;
    private final Vec3 origin;
    private final Vec3[] directions;
    private final double range, speed, cuttingPower;
    private final double[] radius, work, cost;
    private final long[] count;
    private final int[] activeTicks;
    private final byte[] phase, moving;
    private int cursor;
    private RadialSlashView view;

    SlashFragments(Vec3 origin, Vec3 forward, Vec3 blade, SlashFission.Partition partition,
                   double cuttingPower, double range, double speed) {
        this(origin, directions(forward, blade, (int)Math.min(MAX_PATHS, partition.count())),
                cuttingPower, range, speed);
        int fullPaths = directions.length - (partition.tail() > 0 ? 1 : 0);
        for (int i = 0; i < fullPaths; i++) {
            count[i] = partition.fullBlades() / fullPaths + (i < partition.fullBlades() % fullPaths ? 1 : 0);
            cost[i] = partition.cost();
        }
        if (partition.tail() > 0) {
            int last = count.length - 1; count[last] = 1; cost[last] = partition.tail();
        }
    }

    private SlashFragments(Vec3 origin, Vec3[] directions, double cuttingPower, double range, double speed) {
        if (directions.length < 1 || directions.length > MAX_PATHS
                || !finite(origin) || !Double.isFinite(cuttingPower) || cuttingPower <= 0
                || cuttingPower > SlashCritical.MAX_POTENTIAL || !Double.isFinite(range)
                || range <= 0 || range > SlashShape.MAX_RANGE || !Double.isFinite(speed) || speed <= 0 || speed > 16384)
            throw new IllegalArgumentException("Invalid fragment geometry");
        this.origin = origin; this.directions = directions;
        this.cuttingPower = cuttingPower; this.range = range; this.speed = speed;
        int size = directions.length;
        radius = new double[size]; work = new double[size]; cost = new double[size]; count = new long[size];
        activeTicks = new int[size]; phase = new byte[size]; moving = new byte[size];
        Arrays.fill(phase, (byte)1);
    }
    private static Vec3[] directions(Vec3 forward, Vec3 blade, int size) {
        Vec3 side = forward.cross(blade).normalize();
        Vec3[] axes = {forward, forward.scale(-1), blade, blade.scale(-1), side, side.scale(-1)};
        Vec3[] result = new Vec3[size];
        for (int i = 0; i < size; i++) {
            if (i < 6) { result[i] = axes[i]; continue; }
            double y = 1 - 2 * (i - 6 + .5) / (size - 6), r = Math.sqrt(1 - y*y);
            double angle = (i - 6) * Math.PI * (3 - Math.sqrt(5));
            result[i] = forward.scale(y).add(blade.scale(r * Math.cos(angle))).add(side.scale(r * Math.sin(angle))).normalize();
        }
        return result;
    }

    public int size() { return directions.length; }
    public Vec3 origin() { return origin; }
    public Vec3 direction(int i) { return directions[i]; }
    public Vec3 blade(int i) { return SlashGeometry.blade(directions[i], (float)(i * .7)); }
    public double maximumRadius() { return Arrays.stream(radius).max().orElse(0); }
    public Vec3 renderPosition(int i, float partial) {
        return origin.add(directions[i].scale(view == null ? radius[i] : view.radius(i, partial)));
    }
    public float opacity(int i, float partial) { return view == null ? 0 : view.opacity(i, partial); }
    public void clientTick() { if (view != null) view.tick(); }
    void pause() { Arrays.fill(moving, (byte)0); }
    boolean cuts(double hardness) { return Double.isFinite(hardness) && hardness >= 0 && hardness <= cuttingPower * 3; }
    private record Step(int index, TravelStep travel, SlashGeometry.Sweep sweep) {}

    boolean tick(SlashEntity slash, ServerPlayer player) {
        pause();
        if (slash.potential().remaining() <= 0) { Arrays.fill(phase, (byte)2); return false; }
        var level = player.serverLevel();
        var space = ProjectileBarrier.space(slash);
        var budget = new SlashCollision.Budget(space);
        List<Step> steps = new ArrayList<>();
        int start = cursor; cursor = (cursor + 1) % size();
        for (int n = 0; n < size(); n++) {
            int i = (start + n) % size();
            if (phase[i] == 2) continue;
            if (count[i] == 0 || activeTicks[i] >= 160 || work[i] >= range - 1.0E-7) { phase[i] = 2; continue; }
            Vec3 from = origin.add(directions[i].scale(radius[i]));
            double allowance = Math.min(speed, range - work[i]), cap = allowance;
            TravelStep travel = null; SlashGeometry.Sweep sweep = null;
            boolean ready = false;
            for (int attempt = 0; attempt < 16; attempt++) {
                travel = space.quotePoint(from, directions[i], allowance, cap);
                sweep = new SlashGeometry.Sweep(from, travel.to(), blade(i), HALF_LENGTH, HALF_LENGTH);
                var cover = SlashQueries.cover(sweep);
                if (!cover.isEmpty() && cover.stream().mapToDouble(SlashQueries::volume).sum() <= SlashCollision.MAX_CELLS) {
                    if (!SlashQueries.loaded(level, cover)) break; // Pause without loading chunks.
                    var box = sweep.bounds();
                    if (box.minY < level.getMinBuildHeight() || box.maxY >= level.getMaxBuildHeight()
                            || !level.getWorldBorder().isWithinBounds(BlockPos.containing(box.minX, box.minY, box.minZ))
                            || !level.getWorldBorder().isWithinBounds(BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
                        phase[i] = 2; break;
                    }
                    ready = true; break;
                }
                cap *= .5;
            }
            if (ready) { activeTicks[i]++; steps.add(new Step(i, travel, sweep)); }
        }
        if (!steps.isEmpty()) {
            double[] costs = steps.stream().mapToDouble(step -> cost[step.index]).toArray();
            var result = SlashCollision.resolveFragments(slash, player, steps.stream().map(Step::sweep).toList(), budget, costs);
            for (int n = 0; n < steps.size(); n++) {
                Step step = steps.get(n); int i = step.index;
                if (!result.accepted()[n]) { cursor = i; continue; }
                double fraction = result.fractions()[n];
                radius[i] += step.travel.distance() * fraction;
                work[i] = Math.min(range, work[i] + step.travel.spentAt(fraction));
                moving[i] = 1;
                ProjectileBarrier.observeTravel(slash, step.travel, fraction);
                if (result.consumed()[n]) {
                    // The block/target has already changed. Other paths re-read that state and
                    // continue freely if there is no longer anything to hit.
                    if (--count[i] == 0) phase[i] = 2;
                } else if (fraction < 1) phase[i] = 2;
                if (work[i] >= range - 1.0E-7) phase[i] = 2;
            }
        }
        if (slash.isRemoved() || !player.isAlive() || player.level() != slash.level()
                || slash.potential().remaining() <= 0 || slash.hitBlocks.size() >= SlashEntity.MAX_BLOCKS) {
            Arrays.fill(phase, (byte)2); return false;
        }
        for (byte state : phase) if (state != 2) return true;
        return false;
    }

    public CompoundTag geometry() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", 1); putVector(tag, "Origin", origin);
        tag.putDouble("Power", cuttingPower); tag.putDouble("Range", range); tag.putDouble("Speed", speed);
        ListTag vectors = new ListTag();
        for (Vec3 direction : directions) { CompoundTag vector = new CompoundTag(); putVector(vector, "V", direction); vectors.add(vector); }
        tag.put("Directions", vectors); return tag;
    }
    public static SlashFragments fromGeometry(CompoundTag tag) {
        if (tag.getInt("Version") != 1) throw new IllegalArgumentException("Unknown fragments");
        ListTag vectors = tag.getList("Directions", Tag.TAG_COMPOUND);
        if (vectors.isEmpty() || vectors.size() > MAX_PATHS) throw new IllegalArgumentException("Fragment count");
        Vec3[] directions = new Vec3[vectors.size()];
        for (int i = 0; i < vectors.size(); i++) {
            directions[i] = vector(vectors.getCompound(i), "V");
            if (Math.abs(directions[i].lengthSqr() - 1) > 1.0E-6) throw new IllegalArgumentException("Fragment direction");
        }
        return new SlashFragments(vector(tag, "Origin"), directions, tag.getDouble("Power"), tag.getDouble("Range"), tag.getDouble("Speed"));
    }
    public CompoundTag snapshot() {
        CompoundTag tag = new CompoundTag(); int[] radii = new int[size()];
        for (int i = 0; i < size(); i++) radii[i] = Float.floatToRawIntBits((float)radius[i]);
        tag.putIntArray("Radii", radii); tag.putByteArray("Phases", phase.clone()); tag.putByteArray("Moving", moving.clone());
        return tag;
    }
    public void acceptSnapshot(CompoundTag tag) {
        int[] radii = tag.getIntArray("Radii"); byte[] states = tag.getByteArray("Phases"), activity = tag.getByteArray("Moving");
        if (radii.length != size() || states.length != size() || activity.length != size()) throw new IllegalArgumentException("Fragment snapshot size");
        for (int i = 0; i < size(); i++) {
            double r = Float.intBitsToFloat(radii[i]);
            if (!Double.isFinite(r) || r < 0 || r > range + .01 || states[i] < 1 || states[i] > 2 || activity[i] < 0 || activity[i] > 1)
                throw new IllegalArgumentException("Fragment snapshot value");
            radius[i] = r; phase[i] = states[i]; moving[i] = activity[i];
        }
        if (view == null) view = new RadialSlashView(size());
        view.accept(radius, phase, moving);
    }
    public CompoundTag save() {
        CompoundTag tag = geometry();
        tag.putLongArray("Radius", encode(radius)); tag.putLongArray("Work", encode(work)); tag.putLongArray("Cost", encode(cost));
        tag.putLongArray("Count", count.clone()); tag.putIntArray("Ticks", activeTicks.clone());
        tag.putByteArray("Phases", phase.clone()); tag.putInt("Cursor", cursor); return tag;
    }
    public static SlashFragments load(CompoundTag tag) {
        SlashFragments result = fromGeometry(tag); int size = result.size();
        long[] radii = tag.getLongArray("Radius"), work = tag.getLongArray("Work"), cost = tag.getLongArray("Cost"), count = tag.getLongArray("Count");
        int[] ticks = tag.getIntArray("Ticks"); byte[] phases = tag.getByteArray("Phases");
        if (radii.length != size || work.length != size || cost.length != size || count.length != size || ticks.length != size || phases.length != size)
            throw new IllegalArgumentException("Fragment save size");
        for (int i = 0; i < size; i++) {
            double r = Double.longBitsToDouble(radii[i]), w = Double.longBitsToDouble(work[i]), c = Double.longBitsToDouble(cost[i]);
            if (!Double.isFinite(r) || !Double.isFinite(w) || !Double.isFinite(c) || r < 0 || w < r - 1.0E-6 || w > result.range + 1.0E-6
                    || c <= 0 || c > SlashPotential.BLOCK_COST || count[i] < 0 || count[i] > SlashCritical.MAX_POTENTIAL / c
                    || ticks[i] < 0 || ticks[i] > 160 || phases[i] < 1 || phases[i] > 2 || phases[i] == 1 && count[i] == 0)
                throw new IllegalArgumentException("Fragment save value");
            result.radius[i] = r; result.work[i] = w; result.cost[i] = c; result.count[i] = count[i];
            result.activeTicks[i] = ticks[i]; result.phase[i] = phases[i];
        }
        result.cursor = Math.floorMod(tag.getInt("Cursor"), size); return result;
    }
    private static long[] encode(double[] values) { return Arrays.stream(values).mapToLong(Double::doubleToRawLongBits).toArray(); }
    private static void putVector(CompoundTag tag, String key, Vec3 value) {
        tag.putLongArray(key, new long[]{Double.doubleToRawLongBits(value.x), Double.doubleToRawLongBits(value.y), Double.doubleToRawLongBits(value.z)});
    }
    private static Vec3 vector(CompoundTag tag, String key) {
        long[] values = tag.getLongArray(key);
        if (values.length != 3) throw new IllegalArgumentException("Fragment vector size");
        Vec3 result = new Vec3(Double.longBitsToDouble(values[0]), Double.longBitsToDouble(values[1]), Double.longBitsToDouble(values[2]));
        if (!finite(result)) throw new IllegalArgumentException("Fragment vector value"); return result;
    }
    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.lengthSqr()) && Math.abs(value.x) <= 60_000_000 && Math.abs(value.y) <= 60_000_000 && Math.abs(value.z) <= 60_000_000;
    }
}
