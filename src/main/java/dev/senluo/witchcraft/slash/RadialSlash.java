package dev.senluo.witchcraft.slash;

import dev.senluo.witchcraft.accessory.ProjectileBarrier;
import dev.senluo.witchcraft.motion.TravelStep;
import dev.senluo.witchcraft.slash.drawing.RadialOutline;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** One spell ledger, independent radial fronts. The entity stays at the frozen release centre. */
public final class RadialSlash {
    private static final byte ENTERING = 0, FLYING = 1, ENDED = 2;
    private static final int MAX_ROUNDS = 32;
    private final RadialOutline outline;
    private final double[] radius, work, clock;
    private final int[] activeTicks;
    private final byte[] phase, moving;
    private int cursor;
    private RadialSlashView view;

    public RadialSlash(RadialOutline outline) {
        this.outline = outline; int size = outline.edges().size();
        radius = new double[size]; work = new double[size]; clock = new double[size];
        activeTicks = new int[size]; phase = new byte[size]; moving = new byte[size];
    }
    public RadialOutline outline() { return outline; }
    public double maximumRadius() { return Arrays.stream(radius).max().orElse(outline.initialRadius()); }
    public double renderRadius(int part, float partial) { return view == null ? radius[part] : view.radius(part, partial); }
    public float opacity(int part, float partial) { return view == null ? 0 : view.opacity(part, partial); }
    public void clientTick() { if (view != null) view.tick(); }
    void pause() { Arrays.fill(moving, (byte) 0); }

    private record Step(int part, TravelStep travel, SlashGeometry.Sweep sweep, boolean entering) {}

    /** Returns whether a live or unloaded front remains. No offline or unloaded progress is invented. */
    boolean tick(SlashEntity slash, ServerPlayer player, SlashShape shape) {
        pause();
        if (slash.potential().remaining() <= 0) { Arrays.fill(phase, ENDED); return false; }
        Vec3 center = slash.position(); var level = player.serverLevel();
        var space = ProjectileBarrier.space(slash); var budget = new SlashCollision.Budget(space);
        double[] allowance = new double[radius.length], frameBudget = new double[radius.length];
        boolean[] visited = new boolean[radius.length], waiting = new boolean[radius.length]; boolean yielded = false;
        double[] elapsed = new double[radius.length];
        for (int i = 0; i < allowance.length; i++) {
            frameBudget[i] = slash.flightTravel(clock[i], clock[i] + 1);
            allowance[i] = Math.min(frameBudget[i], shape.range() - work[i]);
        }
        int start = cursor; cursor = (cursor + 1) % radius.length;
        for (int round = 0; round < MAX_ROUNDS && budget.intersections > 0; round++) {
            List<Step> steps = new ArrayList<>();
            for (int n = 0; n < radius.length; n++) {
                int i = (start + n) % radius.length;
                if (phase[i] == ENDED || waiting[i]) continue;
                if (activeTicks[i] >= 160 || work[i] >= shape.range() - 1.0E-7) { phase[i] = ENDED; continue; }
                boolean entering = phase[i] == ENTERING;
                double remaining = entering ? outline.initialRadius() - radius[i] : allowance[i];
                if (remaining <= 1.0E-8) continue;
                var edge = outline.edges().get(i);
                // Quote the requested continuous movement. Subdivide for spatial work, not an arbitrary speed cap.
                double cap = remaining;
                TravelStep quote = null; SlashGeometry.Sweep sweep = null;
                AabbStatus status = AabbStatus.TOO_LARGE;
                for (int attempt = 0; attempt < 16; attempt++) {
                    quote = quote(space, center, edge, radius[i], remaining, cap);
                    sweep = edge.sweep(center, radius[i], radius[i] + quote.distance());
                    status = bounds(level, sweep, slash.passesBlocks());
                    if (status == AabbStatus.READY || cap <= .125) break;
                    cap *= .5;
                }
                if (status == AabbStatus.INVALID || status == AabbStatus.TOO_LARGE) { phase[i] = ENDED; continue; }
                if (status == AabbStatus.UNLOADED) { waiting[i] = true; continue; }
                if (!visited[i]) { activeTicks[i]++; visited[i] = true; }
                steps.add(new Step(i, quote, sweep, entering));
            }
            if (steps.isEmpty()) break;
            // Formation happens at time zero. Flying fronts share a time horizon so a
            // later round cannot discover an earlier hit after another front has spent the ledger.
            if (steps.stream().anyMatch(Step::entering)) steps.removeIf(step -> !step.entering);
            else {
                double duration = steps.stream().mapToDouble(step -> step.travel.work()
                        / Math.max(1.0E-8, frameBudget[step.part])).min().orElse(0);
                for (int n = 0; n < steps.size(); n++) {
                    Step step = steps.get(n);
                    TravelStep prefix = prefixWork(step.travel, duration * frameBudget[step.part]);
                    steps.set(n, new Step(step.part, prefix, outline.edges().get(step.part)
                            .sweep(center, radius[step.part], radius[step.part] + prefix.distance()), false));
                }
            }
            List<java.util.function.DoubleUnaryOperator> timeline = new ArrayList<>();
            for (Step step : steps) {
                double startTime = elapsed[step.part], rate = Math.max(1.0E-8, frameBudget[step.part]);
                timeline.add(fraction -> step.entering ? 0 : startTime + step.travel.spentAt(fraction) / rate);
            }
            var resolution = SlashCollision.resolveParts(slash, player, steps.stream().map(Step::sweep).toList(), budget, timeline);
            if (slash.fragments() != null) return false; // The first impact now owns the remaining attack.
            boolean advanced = false;
            for (int n = 0; n < steps.size(); n++) {
                Step step = steps.get(n); int i = step.part;
                if (!resolution.accepted()[n]) {
                    if (!yielded) { cursor = i; yielded = true; }
                    waiting[i] = true;
                    continue;
                }
                double fraction = resolution.fractions()[n];
                double paidWork = step.travel.spentAt(fraction);
                if (paidWork > 0) moving[i] = 1;
                ProjectileBarrier.observeTravel(slash, step.travel, fraction);
                radius[i] += step.travel.distance() * fraction;
                if (step.entering) {
                    // Check the full body-to-release gap before this saved front can appear.
                    if (radius[i] >= outline.initialRadius() - 1.0E-7) { radius[i] = outline.initialRadius(); phase[i] = FLYING; }
                    else if (step.travel.distance() <= 1.0E-8) phase[i] = ENDED;
                } else {
                    work[i] = Math.min(shape.range(), work[i] + paidWork);
                    clock[i] = Math.min(160, clock[i] + paidWork / Math.max(1.0E-8, frameBudget[i]));
                    allowance[i] = Math.max(0, allowance[i] - paidWork);
                    elapsed[i] += paidWork / Math.max(1.0E-8, frameBudget[i]);
                }
                if (fraction < 1 || work[i] >= shape.range() - 1.0E-7) phase[i] = ENDED;
                advanced |= paidWork > 0 || phase[i] == ENDED;
            }
            if (slash.isRemoved() || !player.isAlive() || player.level() != slash.level()
                    || slash.position().distanceToSqr(center) > 1.0E-8
                    || slash.potential().remaining() <= 0 || slash.hitBlocks.size() >= SlashEntity.MAX_BLOCKS
                    || slash.hitEntities.size() >= SlashEntity.MAX_TARGETS) {
                Arrays.fill(phase, ENDED); return false;
            }
            if (!advanced || !slash.passesBlocks() && budget.cells <= 0 || budget.targets <= 0
                    || budget.queries <= 0 || budget.radialVisits <= 0) break;
        }
        for (byte state : phase) if (state != ENDED) return true;
        return false;
    }

    private static TravelStep prefixWork(TravelStep step, double work) {
        if (work >= step.work()) return step;
        double low = 0, high = 1;
        for (int i = 0; i < 40; i++) {
            double middle = (low + high) * .5;
            if (step.spentAt(middle) <= work) low = middle; else high = middle;
        }
        return new TravelStep(step.from(), step.direction(), step.distance() * low,
                step.spentAt(low), step.gap(), step.width());
    }

    private enum AabbStatus { READY, UNLOADED, INVALID, TOO_LARGE }
    private static AabbStatus bounds(net.minecraft.server.level.ServerLevel level, SlashGeometry.Sweep sweep, boolean phasing) {
        var box = sweep.bounds();
        if (box.minY < level.getMinBuildHeight() || box.maxY >= level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(BlockPos.containing(box.minX, box.minY, box.minZ))
                || !level.getWorldBorder().isWithinBounds(BlockPos.containing(box.maxX, box.maxY, box.maxZ))) return AabbStatus.INVALID;
        var cover = SlashQueries.cover(sweep);
        if (cover.isEmpty() || !phasing && cover.stream().mapToDouble(SlashQueries::volume).sum() > SlashCollision.MAX_CELLS)
            return AabbStatus.TOO_LARGE;
        return SlashQueries.loaded(level, cover) ? AabbStatus.READY : AabbStatus.UNLOADED;
    }

    private static TravelStep quote(ProjectileBarrier.FlightSpace space, Vec3 center, RadialOutline.Edge edge,
                                    double radius, double budget, double maximum) {
        // The most restricted sampled radial track controls this thin front; full swept shape still clips the boundary.
        TravelStep selected = null;
        for (Vec3 direction : List.of(edge.first(), edge.middle().normalize(), edge.last())) {
            var step = space.quotePoint(center.add(direction.scale(radius)), direction, budget, Math.min(maximum, budget));
            if (selected == null || step.distance() < selected.distance()) selected = step;
        }
        return selected;
    }

    /** Frequent sync is compact: geometry is sent separately and never repeated with each radius update. */
    public CompoundTag snapshot() {
        CompoundTag tag = new CompoundTag(); int[] radii = new int[radius.length];
        for (int i = 0; i < radii.length; i++) radii[i] = Float.floatToRawIntBits((float) radius[i]);
        tag.putIntArray("Radii", radii); tag.putByteArray("Phases", phase.clone());
        tag.putByteArray("Moving", moving.clone()); return tag;
    }
    public void acceptSnapshot(CompoundTag tag, double range) {
        int[] radii = tag.getIntArray("Radii"); byte[] phases = tag.getByteArray("Phases"), activity = tag.getByteArray("Moving");
        if (activity.length != phase.length) throw new IllegalArgumentException("Invalid radial activity count");
        if (radii.length != radius.length || phases.length != phase.length) throw new IllegalArgumentException("Invalid radial snapshot count");
        for (int i = 0; i < radii.length; i++) {
            double r = Float.intBitsToFloat(radii[i]);
            if (!Double.isFinite(r) || r < 0 || r > range + outline.initialRadius() + .01 || phases[i] < 0 || phases[i] > ENDED || activity[i] < 0 || activity[i] > 1)
                throw new IllegalArgumentException("Invalid radial snapshot");
        }
        for (int i = 0; i < radii.length; i++) {
            radius[i] = Float.intBitsToFloat(radii[i]); phase[i] = phases[i];
        }
        if (view == null) view = new RadialSlashView(radius.length);
        view.accept(radius, phase, activity);
    }

    public CompoundTag save() {
        CompoundTag tag = outline.save();
        tag.putLongArray("Radii", encode(radius)); tag.putLongArray("Work", encode(work)); tag.putLongArray("Clocks", encode(clock));
        tag.putByteArray("Phases", phase.clone()); tag.putIntArray("ActiveTicks", activeTicks.clone()); tag.putInt("Cursor", cursor); return tag;
    }
    public static RadialSlash load(CompoundTag tag, double range) {
        RadialSlash radial = new RadialSlash(RadialOutline.load(tag));
        decode(tag, "Radii", radial.radius); decode(tag, "Work", radial.work); decode(tag, "Clocks", radial.clock);
        byte[] phases = tag.getByteArray("Phases"); int[] ticks = tag.getIntArray("ActiveTicks");
        if (phases.length != radial.phase.length || ticks.length != radial.activeTicks.length) throw new IllegalArgumentException("Incomplete radial save");
        for (int i = 0; i < phases.length; i++) {
            double r = radial.radius[i], w = radial.work[i], t = radial.clock[i];
            if (phases[i] < ENTERING || phases[i] > ENDED || r < 0 || r > radial.outline.initialRadius() + w + 1.0E-6
                    || w < 0 || w > range || t < 0 || t > 160 || ticks[i] < 0 || ticks[i] > 160
                    || phases[i] == ENTERING && (r >= radial.outline.initialRadius() || w != 0 || t != 0)
                    || phases[i] == FLYING && r < radial.outline.initialRadius()) throw new IllegalArgumentException("Invalid radial progress");
            radial.phase[i] = phases[i]; radial.activeTicks[i] = ticks[i];
        }
        radial.cursor = tag.getInt("Cursor");
        if (radial.cursor < 0 || radial.cursor >= phases.length) throw new IllegalArgumentException("Invalid radial cursor");
        return radial;
    }
    private static long[] encode(double[] values) {
        long[] result = new long[values.length];
        for (int i = 0; i < result.length; i++) result[i] = Double.doubleToRawLongBits(values[i]);
        return result;
    }
    private static void decode(CompoundTag tag, String key, double[] into) {
        long[] values = tag.getLongArray(key);
        if (values.length != into.length) throw new IllegalArgumentException("Incomplete radial progress");
        for (int i = 0; i < into.length; i++) {
            into[i] = Double.longBitsToDouble(values[i]);
            if (!Double.isFinite(into[i])) throw new IllegalArgumentException("Nonfinite radial progress");
        }
    }
}
