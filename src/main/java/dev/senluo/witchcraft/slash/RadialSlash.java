package dev.senluo.witchcraft.slash;

import dev.senluo.witchcraft.accessory.ProjectileBarrier;
import dev.senluo.witchcraft.motion.TravelStep;
import dev.senluo.witchcraft.slash.drawing.RadialOutline;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** One spell ledger, independent radial fronts. The entity stays at the frozen release centre. */
public final class RadialSlash {
    private static final byte ENTERING = 0, FLYING = 1, ENDED = 2;
    private static final int MAX_ROUNDS = 32;
    private final RadialOutline outline;
    private static final int MAX_FRONTS = RadialOutline.MAX_PARTS + SlashEntity.MAX_TARGETS * 2;
    private final List<Front> fronts = new ArrayList<>();
    private int cursor, topology;
    private RadialSlashView view;
    private CompoundTag geometry;

    /** Each surviving interval owns its movement; siblings do not share a blocking decision. */
    private final class Front {
        final int part;
        final double from, to;
        final RadialOutline.Edge edge;
        final Vec3[] directions;
        double radius, work, clock;
        int activeTicks;
        byte phase, moving;
        double allowance, frameBudget, elapsed;
        boolean visited, waiting;
        RadialChunkWait blocked;

        void releaseWait() {
            if (blocked != null) {
                blocked.release();
                blocked = null;
            }
        }

        Front(int part, double from, double to) {
            this.part = part; this.from = from; this.to = to;
            var source = outline.edges().get(part);
            edge = from == 0 && to == 1 ? source : source.slice(from, to);
            directions = new Vec3[]{direction(edge.first()), direction(edge.middle()), direction(edge.last())};
        }
        void retain(List<Front> survivors, double first, double last) {
            first = Math.clamp(first, from, to); last = Math.clamp(last, from, to);
            // Mapping world length back to parameters can round a boundary onto the other endpoint.
            if (first >= last) return;
            if (first == from && last == to) { survivors.add(this); return; }
            Front next = new Front(part, first, last);
            next.radius = radius; next.work = work; next.clock = clock;
            next.activeTicks = activeTicks; next.phase = phase; next.moving = moving;
            next.allowance = allowance; next.frameBudget = frameBudget; next.elapsed = elapsed;
            next.visited = visited; next.waiting = waiting;
            survivors.add(next);
        }
        SlashQueries.Coverage coverage(Vec3 center, TravelStep step) {
            return new SlashQueries.Coverage(edge.sweep(center, radius, radius + step.distance()));
        }
        double length() { return edge.half() * 2 * Math.max(radius, outline.initialRadius()); }
    }

    public RadialSlash(RadialOutline outline) {
        this.outline = outline;
        for (int i = 0; i < outline.edges().size(); i++) fronts.add(new Front(i, 0, 1));
    }
    public RadialOutline outline() { return outline; }
    public int size() { return fronts.size(); }
    public RadialOutline.Edge edge(int part) { return fronts.get(part).edge; }
    public double maximumRadius() { return fronts.stream().mapToDouble(front -> front.radius).max().orElse(outline.initialRadius()); }
    public double renderRadius(int part, float partial) { return view == null ? fronts.get(part).radius : view.radius(part, partial); }
    public float opacity(int part, float partial) { return view == null ? 0 : view.opacity(part, partial); }
    public void clientTick() { if (view != null) view.tick(); }
    void pause() { for (Front front : fronts) front.moving = 0; }

    private record Step(Front front, TravelStep travel, SlashQueries.Coverage sweep, boolean entering) {}

    /** Returns whether a live or unloaded front remains. No offline or unloaded progress is invented. */
    boolean tick(SlashEntity slash, ServerPlayer player, SlashShape shape) {
        pause();
        if (slash.potential().remaining() <= 0) { end(); return false; }
        if (fronts.isEmpty()) return false;
        Vec3 center = slash.position(); var level = player.serverLevel();
        var space = ProjectileBarrier.space(slash); var budget = new SlashCollision.Budget(space);
        boolean yielded = false;
        for (Front front : fronts) {
            front.frameBudget = slash.flightTravel(front.clock, front.clock + 1);
            front.allowance = Math.min(front.frameBudget, shape.range() - front.work);
            front.elapsed = 0; front.visited = front.waiting = false;
        }
        int start = cursor; cursor = (cursor + 1) % fronts.size();
        for (int round = 0; round < MAX_ROUNDS && budget.intersections > 0 && !fronts.isEmpty(); round++) {
            List<Step> steps = new ArrayList<>();
            for (int n = 0; n < fronts.size(); n++) {
                Front front = fronts.get((start + n) % fronts.size());
                if (front.phase == ENDED || front.waiting) continue;
                if (front.activeTicks >= 160 || front.work >= shape.range() - 1.0E-7) {
                    front.releaseWait();
                    front.phase = ENDED;
                    continue;
                }
                boolean entering = front.phase == ENTERING;
                double remaining = entering ? outline.initialRadius() - front.radius : front.allowance;
                if (remaining <= 1.0E-8) continue;
                Step step = probe(level, space, center, front, entering, remaining, slash.passesBlocks());
                if (step != null) {
                    if (!front.visited) { front.activeTicks++; front.visited = true; }
                    steps.add(step);
                }
            }
            if (steps.isEmpty()) break;
            // Formation happens at time zero. Moving fronts share one chronological horizon.
            if (steps.stream().anyMatch(Step::entering)) steps.removeIf(step -> !step.entering);
            else {
                double duration = steps.stream().mapToDouble(step -> step.travel.work()
                        / Math.max(1.0E-8, step.front.frameBudget)).min().orElse(0);
                for (var it = steps.listIterator(); it.hasNext();) {
                    Step step = it.next(); Front front = step.front;
                    TravelStep prefix = step.travel.limitWork(duration * front.frameBudget);
                    if (prefix == step.travel) continue;
                    var sweep = step.sweep.forSweep(front.edge.sweep(center, front.radius, front.radius + prefix.distance()));
                    if (sweep != step.sweep) {
                        var status = bounds(level, sweep, slash.passesBlocks());
                        if (status != SweepStatus.PRECISE) {
                            if (status == SweepStatus.UNLOADED) front.waiting = true; else front.phase = ENDED;
                            it.remove();
                            continue;
                        }
                    }
                    it.set(new Step(front, prefix, sweep, false));
                }
                if (steps.isEmpty()) break;
            }
            List<SlashCollision.Timing> timeline = new ArrayList<>();
            for (Step step : steps) {
                double startTime = step.front.elapsed, rate = Math.max(1.0E-8, step.front.frameBudget);
                timeline.add(new SlashCollision.Timing(step.travel, step.entering ? 0 : startTime, step.entering ? 0 : rate));
            }
            var resolution = SlashCollision.resolveParts(slash, player, steps.stream().map(Step::sweep).toList(), budget, timeline);
            boolean advanced = false;
            for (int n = 0; n < steps.size(); n++) {
                Step step = steps.get(n); Front front = step.front;
                if (!resolution.accepted()[n]) {
                    if (!yielded) { cursor = fronts.indexOf(front); yielded = true; }
                    front.waiting = true; continue;
                }
                double fraction = resolution.fractions()[n];
                double paidWork = step.travel.spentAt(fraction);
                if (paidWork > 0) front.moving = 1;
                ProjectileBarrier.observeTravel(slash, step.travel, fraction);
                front.radius += step.travel.distance() * fraction;
                if (step.entering) {
                    if (front.radius >= outline.initialRadius() - 1.0E-7) {
                        front.radius = outline.initialRadius(); front.phase = FLYING;
                    } else if (step.travel.distance() <= 1.0E-8) front.phase = ENDED;
                } else {
                    front.work = Math.min(shape.range(), front.work + paidWork);
                    front.clock = Math.min(160, front.clock + paidWork / Math.max(1.0E-8, front.frameBudget));
                    front.allowance = Math.max(0, front.allowance - paidWork);
                    front.elapsed += paidWork / Math.max(1.0E-8, front.frameBudget);
                }
                // A cut yields at a common event time; only real stops end a surviving interval.
                if (resolution.stopped()[n] || front.work >= shape.range() - 1.0E-7) front.phase = ENDED;
                advanced |= paidWork > 0 || front.phase == ENDED;
            }
            if (resolution.cut() != null) {
                var cut = resolution.cut();
                trim(steps.get(cut.part()).front, center, cut.point(), cut.proportion());
                start = fronts.isEmpty() ? 0 : Math.floorMod(start, fronts.size());
                advanced = true;
            }
            if (slash.isRemoved() || !player.isAlive() || player.level() != slash.level()
                    || slash.position().distanceToSqr(center) > 1.0E-8
                    || slash.potential().remaining() <= 0 || slash.hitBlocks.size() >= SlashEntity.MAX_BLOCKS
                    || slash.hitEntities.size() >= SlashEntity.MAX_TARGETS) {
                end(); return false;
            }
            if (!advanced || !slash.passesBlocks() && budget.cells <= 0 || budget.targets <= 0
                    || budget.queries <= 0 || budget.radialVisits <= 0) break;
        }
        return fronts.stream().anyMatch(front -> front.phase != ENDED);
    }

    void releaseWaits() { for (Front front : fronts) front.releaseWait(); }

    private void end() {
        releaseWaits();
        for (Front front : fronts) front.phase = ENDED;
    }

    /** Consume a length quota around the contact. Gaps cost no length and never reconnect. */
    private void trim(Front hit, Vec3 center, Vec3 point, double proportion) {
        double total = 0, at = 0;
        for (Front front : fronts) {
            if (front.phase == ENDED && front != hit) continue;
            double length = front.length();
            if (front == hit) {
                double local = front.edge.fractionAt(point.subtract(center));
                at = total + length * local;
            }
            total += length;
        }
        double amount = total * Math.clamp(proportion, 0, 1);
        if (amount <= 0) return;
        double low = Math.clamp(at - amount * .5, 0, total - amount), high = low + amount;
        var survivors = new ArrayList<Front>(fronts.size() + 1);
        double offset = 0;
        for (Front front : fronts) {
            if (front.phase == ENDED && front != hit) { survivors.add(front); continue; }
            double length = front.length(), stop = offset + length;
            double first = Math.max(offset, low), last = Math.min(stop, high);
            if (first >= last) survivors.add(front);
            else {
                front.releaseWait();
                double span = front.to - front.from;
                if (first > offset) front.retain(survivors, front.from, front.from + span * ((first - offset) / length));
                if (last < stop) front.retain(survivors, front.from + span * ((last - offset) / length), front.to);
            }
            offset = stop;
        }
        fronts.clear(); fronts.addAll(survivors);
        cursor = fronts.isEmpty() ? 0 : Math.floorMod(cursor, fronts.size());
        topology++; geometry = null;
    }

    private Step probe(ServerLevel level, ProjectileBarrier.FlightSpace space, Vec3 center, Front front,
                       boolean entering, double remaining, boolean phasing) {
        if (front.blocked != null) {
            if (!space.hasFields() && front.blocked.stillBlocked(level, center, remaining, phasing)) {
                front.waiting = true;
                return null;
            }
            front.releaseWait();
        }
        Vec3 direction = front.directions[0];
        TravelStep first, shortest;
        try {
            first = space.quotePoint(center.add(direction.scale(front.radius)), direction, remaining, remaining);
            shortest = space.hasFields() ? shortestTrack(space, center, front, first, remaining) : first;
        } catch (IllegalArgumentException invalidQuote) {
            front.phase = ENDED;
            return null;
        }
        var initial = new Step(front, shortest, front.coverage(center, shortest), entering);
        var status = bounds(level, initial.sweep(), phasing);
        if (status == SweepStatus.PRECISE) return initial;
        return retry(level, center, remaining, phasing, !space.hasFields(), first, initial, status);
    }

    private Step retry(ServerLevel level, Vec3 center, double remaining, boolean phasing, boolean emptyField,
                       TravelStep first, Step initial, SweepStatus status) {
        Front front = initial.front();
        var sweep = initial.sweep();
        RadialChunkWait witnesses = null;
        boolean canWait = emptyField && RadialChunkWait.supported(level);
        double cap = remaining;
        for (int attempts = 1; ; attempts++) {
            if (status == SweepStatus.UNLOADED && canWait) {
                Long missing = SlashQueries.missingAfterRejection(level, sweep.boxes());
                if (missing == null) canWait = false;
                else if (witnesses == null) witnesses = new RadialChunkWait(level, center, remaining, phasing, missing);
                else witnesses.record(missing);
            }
            if (attempts == 16 || cap <= .125) break;
            cap *= .5;
            TravelStep travel;
            try {
                travel = quoteAtLimit(first, initial.travel(), remaining, cap);
            } catch (IllegalArgumentException invalidQuote) {
                front.phase = ENDED;
                return null;
            }
            sweep = front.coverage(center, travel);
            status = bounds(level, sweep, phasing);
            if (status == SweepStatus.PRECISE) return new Step(front, travel, sweep, initial.entering());
        }
        if (status == SweepStatus.UNLOADED) {
            front.waiting = true;
            if (canWait && witnesses != null) {
                witnesses.register();
                front.blocked = witnesses;
            }
        } else front.phase = ENDED;
        return null;
    }

    private static TravelStep shortestTrack(ProjectileBarrier.FlightSpace space, Vec3 center, Front front,
                                            TravelStep first, double budget) {
        TravelStep selected = first;
        for (int i = 1; i < front.directions.length; i++) {
            Vec3 direction = front.directions[i];
            var step = space.quotePoint(center.add(direction.scale(front.radius)), direction, budget, budget);
            if (step.distance() < selected.distance()) selected = step;
        }
        return selected;
    }

    /** A smaller cap can tie all rays. The original first ray then owns the work receipt. */
    static TravelStep quoteAtLimit(TravelStep first, TravelStep shortest, double budget, double cap) {
        if (shortest.distance() < cap) return shortest;
        if (first.distance() == cap) return first;
        double gap = first.width() == 0 ? Double.POSITIVE_INFINITY : first.gap();
        return TravelStep.quote(first.from(), first.direction(), budget, cap, gap, first.width());
    }

    // Precision is decided per sweep. A statistical front ends after the allowed step reductions.
    private enum SweepStatus { PRECISE, STATISTICAL, UNLOADED, INVALID }
    private static SweepStatus bounds(ServerLevel level, SlashQueries.Coverage sweep, boolean phasing) {
        var box = sweep.bounds();
        if (box.minY < level.getMinBuildHeight() || box.maxY >= level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(BlockPos.containing(box.minX, box.minY, box.minZ))
                || !level.getWorldBorder().isWithinBounds(BlockPos.containing(box.maxX, box.maxY, box.maxZ))) return SweepStatus.INVALID;
        var cover = sweep.boxes();
        if (cover.isEmpty() || !phasing && sweep.exceedsCellLimit(SlashCollision.MAX_CELLS))
            return SweepStatus.STATISTICAL;
        return SlashQueries.loaded(level, cover) ? SweepStatus.PRECISE : SweepStatus.UNLOADED;
    }

    private static Vec3 direction(Vec3 raw) {
        if (!finite(raw) || raw.lengthSqr() < 1.0E-12) throw new IllegalArgumentException("Invalid radial direction");
        return raw.normalize();
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z)
                && Double.isFinite(value.lengthSqr());
    }

    /** Angular intervals only change on a cut; their packet is separate from moving radii. */
    public CompoundTag geometry() {
        if (geometry != null) return geometry;
        CompoundTag tag = outline.save();
        tag.putInt("Topology", topology);
        if (topology != 0) {
            ListTag intervals = new ListTag();
            for (Front front : fronts) {
                CompoundTag row = new CompoundTag(); row.putInt("Part", front.part);
                row.putDouble("From", front.from); row.putDouble("To", front.to); intervals.add(row);
            }
            tag.put("Intervals", intervals);
        }
        geometry = tag;
        return tag;
    }
    public static RadialSlash fromGeometry(CompoundTag tag, RadialSlash previous) {
        RadialSlash radial = new RadialSlash(RadialOutline.load(tag));
        radial.topology = tag.getInt("Topology");
        if (radial.topology < 0 || radial.topology > SlashEntity.MAX_TARGETS)
            throw new IllegalArgumentException("Invalid radial topology");
        if (radial.topology != 0) {
            if (!tag.contains("Intervals", Tag.TAG_LIST)) throw new IllegalArgumentException("Missing radial intervals");
            ListTag intervals = tag.getList("Intervals", Tag.TAG_COMPOUND);
            if (intervals.size() > MAX_FRONTS) throw new IllegalArgumentException("Too many radial intervals");
            radial.fronts.clear();
            int previousPart = -1; double previousEnd = 0;
            for (int i = 0; i < intervals.size(); i++) {
                CompoundTag row = intervals.getCompound(i);
                if (!row.contains("Part", Tag.TAG_INT) || !row.contains("From", Tag.TAG_DOUBLE)
                        || !row.contains("To", Tag.TAG_DOUBLE)) throw new IllegalArgumentException("Incomplete radial interval");
                int part = row.getInt("Part"); double from = row.getDouble("From"), to = row.getDouble("To");
                if (part < 0 || part >= radial.outline.edges().size() || part < previousPart
                        || part == previousPart && from < previousEnd)
                    throw new IllegalArgumentException("Invalid radial interval order");
                radial.fronts.add(radial.new Front(part, from, to)); previousPart = part; previousEnd = to;
            }
        } else if (tag.contains("Intervals")) throw new IllegalArgumentException("Unexpected radial intervals");
        if (previous != null && previous.view != null) {
            int[] parents = new int[radial.fronts.size()]; Arrays.fill(parents, -1);
            for (int i = 0, old = 0; i < radial.fronts.size(); i++) {
                Front child = radial.fronts.get(i); double midpoint = (child.from + child.to) * .5;
                while (old < previous.fronts.size()) {
                    Front parent = previous.fronts.get(old);
                    if (parent.part < child.part || parent.part == child.part && parent.to < midpoint) old++;
                    else {
                        if (parent.part == child.part && parent.from <= midpoint && parent.to >= midpoint) parents[i] = old;
                        break;
                    }
                }
            }
            radial.view = previous.view.remap(parents);
        }
        return radial;
    }

    /** Compare the wire fields with the last published frame before allocating its replacement. */
    boolean matchesSnapshot(CompoundTag published) {
        if (!published.contains("Topology", Tag.TAG_INT) || published.getInt("Topology") != topology) return false;
        int[] radii = published.getIntArray("Radii");
        byte[] phases = published.getByteArray("Phases"), moving = published.getByteArray("Moving");
        int count = fronts.size();
        if (radii.length != count || phases.length != count || moving.length != count) return false;
        for (int i = 0; i < count; i++) {
            Front front = fronts.get(i);
            if (radii[i] != Float.floatToRawIntBits((float) front.radius)
                    || phases[i] != front.phase || moving[i] != front.moving) return false;
        }
        return true;
    }

    /** Frequent sync contains only current motion, with a topology receipt against mixed packets. */
    public CompoundTag snapshot() {
        CompoundTag tag = new CompoundTag(); int[] radii = new int[fronts.size()];
        byte[] phases = new byte[fronts.size()], moving = new byte[fronts.size()];
        for (int i = 0; i < fronts.size(); i++) {
            Front front = fronts.get(i); radii[i] = Float.floatToRawIntBits((float) front.radius);
            phases[i] = front.phase; moving[i] = front.moving;
        }
        tag.putInt("Topology", topology); tag.putIntArray("Radii", radii); tag.putByteArray("Phases", phases);
        tag.putByteArray("Moving", moving); return tag;
    }
    public void acceptSnapshot(CompoundTag tag, double range) {
        int[] radii = tag.getIntArray("Radii"); byte[] phases = tag.getByteArray("Phases"), activity = tag.getByteArray("Moving");
        if (tag.getInt("Topology") != topology || radii.length != fronts.size()
                || phases.length != fronts.size() || activity.length != fronts.size())
            throw new IllegalArgumentException("Invalid radial snapshot topology/count");
        double[] values = new double[radii.length];
        for (int i = 0; i < radii.length; i++) {
            double r = Float.intBitsToFloat(radii[i]);
            if (!Double.isFinite(r) || r < 0 || r > range + outline.initialRadius() + .01
                    || phases[i] < 0 || phases[i] > ENDED || activity[i] < 0 || activity[i] > 1)
                throw new IllegalArgumentException("Invalid radial snapshot");
            values[i] = r;
        }
        for (int i = 0; i < values.length; i++) { fronts.get(i).radius = values[i]; fronts.get(i).phase = phases[i]; }
        if (view == null) view = new RadialSlashView(fronts.size());
        view.accept(values, phases, activity);
    }

    public CompoundTag save() {
        CompoundTag tag = geometry().copy();
        double[] radii = new double[fronts.size()], work = new double[fronts.size()], clock = new double[fronts.size()];
        byte[] phases = new byte[fronts.size()]; int[] ticks = new int[fronts.size()];
        for (int i = 0; i < fronts.size(); i++) {
            Front front = fronts.get(i); radii[i] = front.radius; work[i] = front.work; clock[i] = front.clock;
            phases[i] = front.phase; ticks[i] = front.activeTicks;
        }
        tag.putLongArray("Radii", encode(radii)); tag.putLongArray("Work", encode(work)); tag.putLongArray("Clocks", encode(clock));
        tag.putByteArray("Phases", phases); tag.putIntArray("ActiveTicks", ticks); tag.putInt("Cursor", cursor); return tag;
    }
    public static RadialSlash load(CompoundTag tag, double range) {
        RadialSlash radial = fromGeometry(tag, null);
        double[] radius = new double[radial.fronts.size()], work = new double[radius.length], clock = new double[radius.length];
        decode(tag, "Radii", radius); decode(tag, "Work", work); decode(tag, "Clocks", clock);
        byte[] phases = tag.getByteArray("Phases"); int[] ticks = tag.getIntArray("ActiveTicks");
        if (phases.length != radius.length || ticks.length != radius.length) throw new IllegalArgumentException("Incomplete radial save");
        for (int i = 0; i < phases.length; i++) {
            double r = radius[i], w = work[i], t = clock[i];
            if (phases[i] < ENTERING || phases[i] > ENDED || r < 0 || r > radial.outline.initialRadius() + w + 1.0E-6
                    || w < 0 || w > range || t < 0 || t > 160 || ticks[i] < 0 || ticks[i] > 160
                    || phases[i] == ENTERING && (r >= radial.outline.initialRadius() || w != 0 || t != 0)
                    || phases[i] == FLYING && r < radial.outline.initialRadius()) throw new IllegalArgumentException("Invalid radial progress");
            Front front = radial.fronts.get(i); front.radius = r; front.work = w; front.clock = t;
            front.phase = phases[i]; front.activeTicks = ticks[i];
        }
        radial.cursor = tag.getInt("Cursor");
        if (radial.cursor < 0 || radial.cursor >= Math.max(1, phases.length)) throw new IllegalArgumentException("Invalid radial cursor");
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
