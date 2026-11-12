package dev.senluo.witchcraft.compat.thaumcraft;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/** A bounded, disposable view of future bands. It never advances excavation cursors. */
final class MinePrefetch {
    private static final int BASE = 5, PLAN_CHUNKS = MineReadQueue.OWNER_CHUNKS - 16;
    private final MixedMine mine;
    final MineReadQueue.Owner reads;
    private static final class Consumer {
        int radius, tick, band;
        boolean sampling;
    }
    private final Map<BlockPos, Consumer> consumers = new HashMap<>();
    private final Map<Integer, Band> bands = new LinkedHashMap<>();
    private Set<Integer> fronts = Set.of();
    private final double[] latencies = new double[32];
    private int latencyCount, latencyCursor, target = BASE, readyFuture, lastWindowTick;
    private int radius = -1, highBand, consumed, lowWindows, waitingTick = Integer.MIN_VALUE, waitingSince;
    private double fastRate, slowRate;
    private long windowStart;
    private boolean dirty = true, statisticsStarted;

    MinePrefetch(MixedMine mine, UUID owner) { this.mine = mine; reads = new MineReadQueue.Owner(owner, this); }

    void maintain(BlockEntity bore, int radius) {
        if (!(bore.getLevel() instanceof ServerLevel host)) return;
        Consumer consumer = consumers.computeIfAbsent(bore.getBlockPos().immutable(), unused -> new Consumer());
        int nextRadius = Math.max(0, radius);
        if (consumer.radius != nextRadius) dirty = true;
        consumer.radius = nextRadius; consumer.tick = host.getServer().getTickCount();
        reads.touch();
    }
    void bind(ServerLevel level) {
        if (reads.level != level) { bands.clear(); dirty = true; }
        reads.bind(level);
    }
    void demand(BlockEntity bore, int band) {
        Consumer consumer = consumers.computeIfAbsent(bore.getBlockPos().immutable(), unused -> new Consumer());
        consumer.tick = reads.level.getServer().getTickCount();
        if (!consumer.sampling || consumer.band != band) dirty = true;
        consumer.sampling = true; consumer.band = band;
        if (statisticsStarted && band > highBand) { consumed += band - highBand; highBand = band; }
        reads.touch();
    }
    void changed() { dirty = true; }
    void waiting() {
        int tick = reads.level.getServer().getTickCount();
        if (waitingTick == Integer.MIN_VALUE || tick - waitingTick > 1) waitingSince = tick;
        waitingTick = tick;
    }
    void release() {
        reads.release(); consumers.clear(); bands.clear();
        dirty = true; radius = -1; readyFuture = 0; target = BASE; fronts = Set.of();
        statisticsStarted = false; fastRate = slowRate = 0; consumed = lowWindows = 0;
        latencyCount = latencyCursor = 0; waitingTick = Integer.MIN_VALUE;
    }

    private static final class Band {
        final int index;
        final Set<Long> targets = new LinkedHashSet<>(), expanded = new HashSet<>();
        final Map<Long, Boolean> dependencies = new LinkedHashMap<>();
        int priority;
        long started = System.nanoTime();
        boolean wasReady, oversized;
        Band(int index, BlockPos origin, int radius, int priority) {
            this.index = index; this.priority = priority;
            int minX = (origin.getX() - radius) >> 4, maxX = (origin.getX() + radius) >> 4;
            int minZ = (origin.getZ() - radius) >> 4, maxZ = (origin.getZ() + radius) >> 4;
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                targets.add(ChunkPos.asLong(x, z));
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                    dependencies.put(ChunkPos.asLong(x + dx, z + dz), true);
            }
        }
        boolean discover(MineReadQueue.Owner reads) {
            if (oversized) return false;
            boolean changed = false;
            for (long key : targets) {
                if (expanded.contains(key) || !reads.prepared(key, true)) continue;
                LevelChunk chunk = reads.chunkNow(key);
                if (chunk == null) continue;
                references:
                for (var references : chunk.getAllReferences().values()) for (long start : references) {
                    if (dependencies.containsKey(start)) continue;
                    if (dependencies.size() >= PLAN_CHUNKS) { oversized = true; break references; }
                    dependencies.put(start, false); changed = true;
                }
                expanded.add(key);
                if (oversized) break;
            }
            return changed;
        }
        boolean ready(MineReadQueue.Owner reads) {
            if (oversized || expanded.size() != targets.size()) return false;
            for (var entry : dependencies.entrySet()) if (!reads.prepared(entry.getKey(), entry.getValue())) return false;
            return true;
        }
    }

    void prepare(int tick) {
        boolean removed = consumers.values().removeIf(consumer -> consumer.tick != tick);
        if (removed) dirty = true;
        int nextRadius = 0;
        var current = new TreeSet<Integer>();
        for (Consumer consumer : consumers.values()) {
            nextRadius = Math.max(nextRadius, consumer.radius);
            if (consumer.sampling) current.add(consumer.band);
        }
        if (radius != nextRadius) { radius = nextRadius; bands.clear(); dirty = true; }
        // The origin is always at chunk-local (8,8); reject oversized plans before enumerating cells.
        long side = Math.floorDiv(8L + radius, 16) - Math.floorDiv(8L - radius, 16) + 3;
        long footprint = side * side;
        if (footprint > PLAN_CHUNKS || current.isEmpty()) {
            bands.clear(); reads.plan(Map.of()); return;
        }
        if (dirty || !statisticsStarted) fronts = mine.frontiers(radius);
        if (!statisticsStarted) {
            statisticsStarted = true; windowStart = System.nanoTime(); lastWindowTick = tick;
            highBand = fronts.stream().mapToInt(Integer::intValue).max().orElse(0);
        }
        int capacity = Math.max(BASE, PLAN_CHUNKS / (int) footprint - current.size());
        if (tick - lastWindowTick >= 20) adapt(tick, capacity);
        int waitSeconds = waitingTick != Integer.MIN_VALUE && tick - waitingTick <= 1
                ? Math.max(0, (tick - waitingSince) / 20) : 0;
        reads.allowance = Math.min(16, 4 + Math.max(0, target - BASE) * 2 + Math.min(12, waitSeconds * 2));
        if (dirty) rebuild(fronts, current, (int) footprint);

        boolean changed = false;
        readyFuture = 0;
        for (Band band : bands.values()) {
            changed |= band.discover(reads);
            boolean ready = band.ready(reads);
            if (ready && !band.wasReady) {
                latencies[latencyCursor] = Math.max(0.05, (System.nanoTime() - band.started) / 1_000_000_000.0);
                latencyCursor = (latencyCursor + 1) % latencies.length;
                latencyCount = Math.min(latencies.length, latencyCount + 1);
            } else if (!ready && band.wasReady) band.started = System.nanoTime();
            band.wasReady = ready;
            if (ready && band.priority == 2) readyFuture++;
        }
        if (changed) publishPlan();
    }

    private void adapt(int tick, int capacity) {
        long now = System.nanoTime();
        double seconds = Math.max(0.05, (now - windowStart) / 1_000_000_000.0);
        double observed = consumed / seconds;
        fastRate += 0.35 * (observed - fastRate);
        slowRate += 0.10 * (observed - slowRate);
        int wanted = (int) Math.min(capacity, Math.max(BASE,
                Math.ceil(Math.max(fastRate, slowRate) * (latency() + 0.5)) + 1));
        if (wanted > target) { target = wanted; lowWindows = 0; dirty = true; }
        else if (target > capacity) { target = capacity; lowWindows = 0; dirty = true; }
        else if (wanted <= target - 2 && (waitingTick == Integer.MIN_VALUE || tick - waitingTick > 20)) {
            if (++lowWindows >= 5) { target--; lowWindows = 0; dirty = true; }
        } else lowWindows = 0;
        consumed = 0; windowStart = now; lastWindowTick = tick;
    }
    private double latency() {
        if (latencyCount == 0) return 2;
        double[] sorted = Arrays.copyOf(latencies, latencyCount);
        Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * 0.95) - 1)];
    }
    private void rebuild(Set<Integer> fronts, Set<Integer> current, int footprint) {
        dirty = false;
        var wanted = new LinkedHashMap<Integer, Integer>();
        int slots = PLAN_CHUNKS / footprint;
        for (int band : current) if (wanted.size() < slots) wanted.put(band, 1);
        int future = 0;
        // Merge every column frontier. A fast column must not discard a slower column's successors.
        for (long distance = 1; future < target && distance <= target + (long) fronts.size(); distance++) {
            for (int front : fronts) {
                long next = front + distance;
                if (next > Integer.MAX_VALUE) continue;
                int band = (int) next;
                if (fronts.contains(band) || wanted.containsKey(band)) continue;
                wanted.put(band, 2); future++;
                if (future >= target) break;
            }
        }
        bands.keySet().retainAll(wanted.keySet());
        wanted.forEach((index, priority) -> {
            Band band = bands.computeIfAbsent(index, key -> new Band(key, mine.origin(key), radius, priority));
            band.priority = priority;
        });
        publishPlan();
    }
    private void publishPlan() {
        var wanted = new LinkedHashMap<Long, MineReadQueue.Need>();
        // Current band coverage precedes the five future slots. Actual target requests have priority zero.
        for (int priority = 1; priority <= 2; priority++) for (Band band : bands.values()) {
            if (band.priority != priority) continue;
            for (var dep : band.dependencies.entrySet()) {
                MineReadQueue.Need old = wanted.get(dep.getKey());
                if (old != null) wanted.put(dep.getKey(), new MineReadQueue.Need(old.entities() || dep.getValue(), Math.min(priority, old.priority())));
                else if (wanted.size() < PLAN_CHUNKS) wanted.put(dep.getKey(), new MineReadQueue.Need(dep.getValue(), priority));
            }
        }
        reads.plan(wanted);
    }

    MixedMine.Preparation snapshot() {
        return new MixedMine.Preparation(target, readyFuture, reads.pending, reads.held,
                Math.max(fastRate, slowRate), latency());
    }
}
