package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.worldgen.LocalGeneration;
import dev.tc4port.thaumcraft.api.machine.ArcaneBoreView;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/** One run owns supply, reservations and retirement. Excavation cursors belong to MixedMine. */
final class MineSupply {
    private static final int BASE = 5, CANDIDATES = 5, PLAN_CHUNKS = MineReadQueue.OWNER_CHUNKS - 16;
    private final long seed;
    final MineReadQueue.Owner reads = new MineReadQueue.Owner(this);
    private ServerLevel level;
    private LocalGeneration.Context generation;
    private MineSamplePool memory;
    private MineSamplePool.Pool pool;
    private final Map<BlockEntity, Integer> consumers = new WeakHashMap<>();
    private final Map<Long, Integer> columns = new LinkedHashMap<>();
    private final Set<Long> waitingColumns = new LinkedHashSet<>();
    private final Map<Integer, Band> bands = new LinkedHashMap<>();
    private Set<Integer> fronts = Set.of();
    private Set<Long> targetOffsets = Set.of();
    private int radius = -1, footprint, sampleTop, sampleFloor, forecastRebuilds;
    private boolean forecastDirty = true, planDirty = true, preparing, closed;

    private final double[] latencies = new double[32];
    private int latencyCount, latencyCursor, target = BASE, readyFuture, lastWindowTick;
    private int highBand, consumed, lowWindows, waitingTick = Integer.MIN_VALUE, waitingSince;
    private double fastRate, slowRate;
    private long windowStart;
    private boolean statisticsStarted;

    private static final class Fresh { MineSample sample; boolean mined, settled; }
    private final Map<Integer, Fresh> fresh = new LinkedHashMap<>();
    private final Deque<MineSample> candidates = new ArrayDeque<>();
    private final Set<UUID> used = new HashSet<>();
    private final Set<Replay> active = new LinkedHashSet<>();
    private final Map<Long, Replay> bindings = new HashMap<>();
    private final List<MineWorkAreas.Area> areas = new ArrayList<>();
    private final BooleanSupplier eviction = this::evict;
    private long candidateRevision, missedPool = -1, missedMemory = -1, missedCandidates = -1;
    private final Set<Long> missedColumns = new HashSet<>();
    private long warmedPool = -1, warmedMemory = -1, warmedColumn;
    private Replay latest;
    private MineSample selected;
    private boolean resetWhenIdle;

    private record Dependencies(Set<Long> chunks, Long transientKey) {}
    private final Map<Dependencies, Integer> heldDependencies = new IdentityHashMap<>();
    private final Map<Long, Dependencies> transientDependencies = new HashMap<>();
    private final Set<Lease> leases = new LinkedHashSet<>();
    private final ReferenceQueue<MixedMine.Target> abandoned = new ReferenceQueue<>();

    MineSupply(long seed) { this.seed = seed; }

    MineSupply atLevel(ServerLevel next) {
        if (closed || level != null && level != next) {
            MineSupply replacement = new MineSupply(seed);
            replacement.consumers.putAll(consumers);
            close();
            return replacement.atLevel(next);
        }
        if (level == null) {
            level = next; generation = LocalGeneration.open(next, seed);
            memory = MineSamplePool.get(next.getServer());
            pool = memory.source(MixedMine.source(next.dimension().location()).location());
            memory.registerEviction(eviction);
        }
        reads.bind(next);
        return this;
    }
    BlockPos origin(int index) {
        Band band = bands.get(index);
        return band == null ? generation.origin(index) : band.origin;
    }
    void bounds(int top, int floor) { sampleTop = top; sampleFloor = floor; }
    void maintain(BlockEntity bore, int radius) {
        if (closed || !(bore.getLevel() instanceof ServerLevel)) return;
        Integer before = consumers.put(bore, Math.max(0, radius));
        if (before == null || before != Math.max(0, radius)) forecastDirty = true;
        if (level != null) reads.bind(level);
    }
    void detach(BlockEntity bore) { if (consumers.remove(bore) != null) forecastDirty = true; }
    void column(long key, int band) {
        Integer before = columns.put(key, band);
        if (before == null || before != band) forecastDirty = true;
    }
    void waiting() {
        int tick = level.getServer().getTickCount();
        if (waitingTick == Integer.MIN_VALUE || tick - waitingTick > 1) waitingSince = tick;
        waitingTick = tick;
    }
    void await(long column, long chunk, boolean hasReplay) {
        waiting(); waitingColumns.add(column); request(chunk, hasReplay);
    }
    void freshAvailable(long column) {
        waitingColumns.remove(column); resetWhenIdle = true; resetRound();
    }
    Replay replay(long column) {
        Replay replay = bindings.get(column);
        if (replay != null && replay.copy.failed) {
            pool.reject(replay.copy.sample.description.id()); leave(column, replay); return null;
        }
        return replay;
    }
    boolean owns(long column, Replay replay) { return bindings.get(column) == replay && !replay.copy.failed; }
    void leave(long column, Replay replay) {
        if (bindings.remove(column, replay)) { replay.members.remove(column); replay.retireIfUnused(); }
    }

    private static final class Band {
        final BlockPos origin;
        final Set<Long> targetOffsets, targets = new LinkedHashSet<>(), expanded = new HashSet<>();
        final Set<Long> dependencies = new LinkedHashSet<>();
        Dependencies snapshot;
        int priority;
        long started = System.nanoTime();
        boolean wasReady, oversized;
        Band(BlockPos origin, Set<Long> targetOffsets, int priority) {
            this.origin = origin; this.priority = priority; this.targetOffsets = targetOffsets;
            for (long offset : targetOffsets) targets.add(ChunkPos.asLong(
                    (origin.getX() >> 4) + ChunkPos.getX(offset), (origin.getZ() >> 4) + ChunkPos.getZ(offset)));
            dependencies.addAll(targets);
            for (long key : targets) neighborhood(dependencies, key);
        }
        Dependencies snapshot() {
            if (snapshot == null) snapshot = new Dependencies(Set.copyOf(dependencies), null);
            return snapshot;
        }
        boolean discover(MineReadQueue.Owner reads) {
            if (oversized) return false;
            boolean changed = false;
            for (long key : List.copyOf(dependencies)) {
                if (expanded.contains(key) || !reads.prepared(key, true)) continue;
                LevelChunk chunk = reads.chunkNow(key);
                if (chunk == null) continue;
                references:
                for (var references : chunk.getAllReferences().values()) for (long start : references) {
                    if (dependencies.contains(start)) continue;
                    if (dependencies.size() >= PLAN_CHUNKS) { oversized = true; break references; }
                    dependencies.add(start); changed = true;
                }
                expanded.add(key);
                if (oversized) break;
            }
            if (changed) snapshot = null;
            return changed;
        }
        boolean ready(MineReadQueue.Owner reads) {
            if (oversized || expanded.size() != dependencies.size()) return false;
            for (long key : dependencies) if (!reads.prepared(key, true)) return false;
            return true;
        }
    }
    private static void neighborhood(Set<Long> into, long key) {
        int x = ChunkPos.getX(key), z = ChunkPos.getZ(key);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            into.add(ChunkPos.asLong(x + dx, z + dz));
    }
    private boolean covered(long key) {
        long x = ChunkPos.getX(key), z = ChunkPos.getZ(key);
        return radius >= 0 && x * x + z * z <= (long) radius * radius;
    }
    private void demand() {
        Set<Integer> current = new TreeSet<>(); Set<Long> offsets = new TreeSet<>();
        columns.forEach((key, band) -> {
            if (covered(key)) {
                current.add(band);
                offsets.add(ChunkPos.asLong((8 + ChunkPos.getX(key)) >> 4, (8 + ChunkPos.getZ(key)) >> 4));
            }
        });
        fronts = Collections.unmodifiableSet(current); targetOffsets = Collections.unmodifiableSet(offsets);
        Set<Long> footprintChunks = new HashSet<>();
        for (long offset : offsets) neighborhood(footprintChunks, offset);
        footprint = footprintChunks.size();
        int nextBand = current.stream().mapToInt(Integer::intValue).max().orElse(0);
        if (!statisticsStarted) highBand = nextBand;
        else if (nextBand > highBand) { consumed += nextBand - highBand; highBand = nextBand; }
        for (long key : List.copyOf(bindings.keySet())) if (!covered(key)) leave(key, bindings.get(key));
        waitingColumns.removeIf(key -> !covered(key));
    }

    void prepare(int tick) {
        if (preparing || level == null) return;
        preparing = true;
        try {
            // Observe only registered consumers: native tools/redstone do not notify every transition.
            if (consumers.keySet().removeIf(bore -> !(bore.getLevel() instanceof ServerLevel host)
                    || !(bore instanceof ArcaneBoreView view) || !BoreExcavation.eligible(host, bore, view))) forecastDirty = true;
            if (closed || consumers.isEmpty()) {
                if (radius >= 0) suspend();
                return;
            }
            int nextRadius = consumers.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            if (radius != nextRadius) { radius = nextRadius; forecastDirty = true; }
            if (forecastDirty) demand();
            if (fronts.isEmpty() || footprint > PLAN_CHUNKS) {
                forecastDirty = false;
                if (!bands.isEmpty()) { bands.clear(); planDirty = true; }
                readyFuture = 0; retainSamples(); prepareSupplies(); return;
            }
            if (!statisticsStarted) {
                statisticsStarted = true; windowStart = System.nanoTime(); lastWindowTick = tick;
            }
            int capacity = Math.max(BASE, PLAN_CHUNKS / footprint - fronts.size());
            if (tick - lastWindowTick >= 20) adapt(tick, capacity);
            int waitSeconds = waitingTick != Integer.MIN_VALUE && tick - waitingTick <= 1
                    ? Math.max(0, (tick - waitingSince) / 20) : 0;
            reads.allowance = Math.min(16, 4 + Math.max(0, target - BASE) * 2 + Math.min(12, waitSeconds * 2));
            if (forecastDirty) rebuild();
            retainSamples(); readyFuture = 0;
            for (Band band : bands.values()) {
                planDirty |= band.discover(reads);
                boolean ready = band.ready(reads);
                if (ready && !band.wasReady) {
                    latencies[latencyCursor] = Math.max(0.05, (System.nanoTime() - band.started) / 1_000_000_000.0);
                    latencyCursor = (latencyCursor + 1) % latencies.length;
                    latencyCount = Math.min(latencies.length, latencyCount + 1);
                } else if (!ready && band.wasReady) band.started = System.nanoTime();
                band.wasReady = ready;
                if (ready && band.priority == 2) readyFuture++;
            }
            prepareSupplies();
        } finally {
            // Callbacks may have changed demand or closed this run; publish the final retained uses only once.
            if (planDirty) publishPlan();
            areas.removeIf(area -> area.closing && area.copy == null);
            if ((closed || consumers.isEmpty()) && leases.isEmpty() && areas.isEmpty()) reads.release();
            finishClosing();
            preparing = false;
        }
    }
    private void adapt(int tick, int capacity) {
        long now = System.nanoTime();
        double seconds = Math.max(0.05, (now - windowStart) / 1_000_000_000.0);
        double observed = consumed / seconds;
        fastRate += 0.35 * (observed - fastRate); slowRate += 0.10 * (observed - slowRate);
        int wanted = (int) Math.min(capacity, Math.max(BASE, Math.ceil(Math.max(fastRate, slowRate) * (latency() + .5)) + 1));
        if (wanted > target) { target = wanted; lowWindows = 0; forecastDirty = true; }
        else if (target > capacity) { target = capacity; lowWindows = 0; forecastDirty = true; }
        else if (wanted <= target - 2 && (waitingTick == Integer.MIN_VALUE || tick - waitingTick > 20)) {
            if (++lowWindows >= 5) { target--; lowWindows = 0; forecastDirty = true; }
        } else lowWindows = 0;
        consumed = 0; windowStart = now; lastWindowTick = tick;
    }
    private double latency() {
        if (latencyCount == 0) return 2;
        double[] sorted = Arrays.copyOf(latencies, latencyCount); Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * .95) - 1)];
    }
    private void rebuild() {
        forecastDirty = false; forecastRebuilds++;
        var wanted = new LinkedHashMap<Integer, Integer>();
        int slots = PLAN_CHUNKS / footprint;
        for (int band : fronts) if (wanted.size() < slots) wanted.put(band, 1);
        int future = 0;
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
            Band band = bands.get(index);
            if (band == null || !band.targetOffsets.equals(targetOffsets)) {
                band = new Band(origin(index), targetOffsets, priority); bands.put(index, band);
            }
            band.priority = priority;
        });
        planDirty = true;
    }
    private void publishPlan() {
        planDirty = false;
        var wanted = new LinkedHashMap<Long, MineReadQueue.Need>();
        for (Replay replay : active) replay.copy.needs(wanted);
        for (Dependencies snapshot : heldDependencies.keySet()) for (long key : snapshot.chunks)
            wanted.put(key, new MineReadQueue.Need(true, 0));
        for (int priority = 1; priority <= 2; priority++) for (Band band : bands.values()) {
            if (band.priority != priority) continue;
            for (long key : band.dependencies) if (!wanted.containsKey(key) && wanted.size() < PLAN_CHUNKS)
                wanted.put(key, new MineReadQueue.Need(true, priority));
        }
        reads.plan(wanted);
    }
    boolean available(long chunk) {
        int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            if (!reads.ready(ChunkPos.asLong(x + dx, z + dz), true)) return false;
        return true;
    }
    private void request(long chunk, boolean unplannedOnly) {
        int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            long key = ChunkPos.asLong(x + dx, z + dz);
            if (!unplannedOnly || !reads.planned(key)) reads.require(key, true);
        }
    }
    MixedMine.Preparation snapshot() {
        return new MixedMine.Preparation(target, readyFuture, reads.pending, reads.held,
                Math.max(fastRate, slowRate), latency(), columns.size(), forecastRebuilds);
    }

    private void prepareSupplies() {
        for (int band : List.copyOf(bands.keySet())) { if (closed) return; capture(band); }
        if (closed) return;
        boolean warmed = false;
        for (long key : List.copyOf(waitingColumns)) {
            if (closed) return;
            Integer band = columns.get(key);
            if (band == null || !covered(key)) { waitingColumns.remove(key); continue; }
            int dx = ChunkPos.getX(key), dz = ChunkPos.getZ(key);
            if (!warmed) { warm(dx, dz); warmed = true; }
            if (available(new ChunkPos(origin(band).offset(dx, 0, dz)).toLong())) {
                waitingColumns.remove(key); continue;
            }
            if (bindings.containsKey(key) || acquire(key, dx, dz) != null) waitingColumns.remove(key);
        }
        if (!warmed && !columns.isEmpty()) {
            long key = columns.keySet().iterator().next(); warm(ChunkPos.getX(key), ChunkPos.getZ(key));
        }
    }
    private void capture(int index) {
        Fresh entry = fresh.computeIfAbsent(index, ignored -> new Fresh());
        Band band = bands.get(index);
        if (entry.mined || entry.settled || band == null || !band.wasReady || !memory.beginCapture()) return;
        try {
            var snapshot = MineSample.capture(memory, level, origin(index), radius, sampleTop, sampleFloor,
                    band.targets, band.snapshot().chunks);
            MineSample sample = snapshot.sample(); entry.settled = !snapshot.retry();
            if (sample != null && (closed || fresh.get(index) != entry || entry.mined || !MineSamplePool.restorable(sample))) {
                sample.release(); return;
            }
            entry.sample = sample;
        } finally { memory.endOperation(); }
    }
    void started(int band) { if (!closed) fresh.computeIfAbsent(band, ignored -> new Fresh()).mined = true; }
    void produced(int band, int dx, int dz) {
        Fresh entry = fresh.get(band);
        if (closed || entry == null || entry.sample == null || !entry.sample.description.accepts(dx, dz)) return;
        candidates.addLast(entry.sample); entry.sample = null; candidateRevision++;
        while (candidates.size() > CANDIDATES) candidates.removeFirst().release();
    }
    private void retainSamples() {
        var it = fresh.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (!bands.containsKey(entry.getKey()) && !fronts.contains(entry.getKey())) {
                if (entry.getValue().sample != null) entry.getValue().sample.release();
                it.remove();
            }
        }
    }
    private boolean evict() {
        if (!candidates.isEmpty()) { candidates.removeFirst().release(); candidateRevision++; return true; }
        for (Fresh entry : fresh.values()) if (entry.sample != null) { entry.sample.release(); entry.sample = null; return true; }
        return false;
    }
    private void warm(int dx, int dz) {
        long column = ChunkPos.asLong(dx, dz);
        if (column == warmedColumn && warmedPool == pool.revision() && warmedMemory == memory.availability()) return;
        pool.warm(dx, dz, used);
        warmedColumn = column; warmedPool = pool.revision(); warmedMemory = memory.availability();
    }
    private MineSample choose(int dx, int dz) {
        var lookup = pool.request(dx, dz, used);
        used.retainAll(pool.ids());
        if (lookup.status() != MineSamplePool.Status.ABSENT) return lookup.sample();
        for (var it = candidates.descendingIterator(); it.hasNext();) {
            MineSample candidate = it.next();
            if (!candidate.description.accepts(dx, dz)) continue;
            if (pool.admit(candidate)) {
                it.remove(); candidateRevision++;
                // Pool acquired its reference before this run hands off the candidate.
                return candidate;
            }
            break;
        }
        lookup = pool.request(dx, dz, Set.of());
        if (lookup.status() != MineSamplePool.Status.ABSENT && lookup.status() != MineSamplePool.Status.CLOSED) {
            used.clear(); warmedPool = -1;
        }
        return lookup.sample();
    }
    private Replay acquire(long column, int dx, int dz) {
        if (latest != null && !latest.copy.failed && !latest.visited.contains(column)
                && latest.copy.sample.description.accepts(dx, dz)) { latest.join(column); return latest; }
        if (selected != null && !selected.description.accepts(dx, dz)) { selected.release(); selected = null; }
        if (selected == null) {
            if (missedPool != pool.revision() || missedMemory != memory.availability() || missedCandidates != candidateRevision) {
                missedColumns.clear(); missedPool = pool.revision(); missedMemory = memory.availability(); missedCandidates = candidateRevision;
            }
            if (missedColumns.contains(column)) return null;
            selected = choose(dx, dz);
            if (selected == null) { missedColumns.add(column); return null; }
            if (!MineSamplePool.restorable(selected) || !selected.compatible(level)) {
                pool.reject(selected.description.id()); selected.release(); selected = null; return null;
            }
        }
        int retained = 0; MineWorkAreas.Area idle = null;
        areas.removeIf(area -> area.closing && area.copy == null);
        for (var it = areas.iterator(); it.hasNext();) {
            var area = it.next();
            if (area.idle() && area.context.seed() != selected.seed) { area.close(); it.remove(); continue; }
            if (area.idle()) idle = area;
            else if (area.copy != null) retained += area.copy.sample.description.workChunks().size();
        }
        if (retained + selected.description.workChunks().size() > MineReadQueue.OWNER_CHUNKS) return null;
        if (idle == null) { idle = MineWorkAreas.get(level).area(selected.seed, () -> planDirty = true, reads); areas.add(idle); }
        MineWorkAreas.Copy copy = idle.restore(selected, memory);
        if (copy == null) return null;
        selected.release(); selected = null;
        latest = new Replay(copy); latest.join(column); active.add(latest); planDirty = true;
        return latest;
    }
    final class Replay {
        final MineWorkAreas.Copy copy;
        private final Set<Long> visited = new HashSet<>(), members = new HashSet<>();
        private int requests;
        private boolean begun, retired;
        Replay(MineWorkAreas.Copy copy) { this.copy = copy; }
        private void join(long column) { visited.add(column); members.add(column); bindings.put(column, this); }
        boolean ready() {
            if (!copy.ready()) return false;
            if (!begun) { begun = true; used.add(copy.sample.description.id()); warmedPool = -1; }
            return true;
        }
        private void retireIfUnused() {
            if (retired || !members.isEmpty() || requests != 0) return;
            retired = true; active.remove(this); if (latest == this) latest = null;
            copy.retire(); planDirty = true; resetRound();
        }
    }
    private void resetRound() {
        if (resetWhenIdle && active.isEmpty()) {
            used.clear(); resetWhenIdle = false; missedPool = warmedPool = -1;
            if (selected != null) { selected.release(); selected = null; }
        }
    }

    Lease hold(int band, long chunk, Replay replay) {
        Dependencies snapshot = null;
        if (replay == null) {
            Band current = bands.get(band);
            snapshot = current != null && !current.oversized ? current.snapshot()
                    : transientDependencies.computeIfAbsent(chunk, key -> {
                        Set<Long> neighbors = new LinkedHashSet<>(); neighborhood(neighbors, key);
                        return new Dependencies(Set.copyOf(neighbors), key);
                    });
            if (heldDependencies.merge(snapshot, 1, Integer::sum) == 1) planDirty = true;
        } else replay.requests++;
        Lease lease = new Lease(snapshot, replay); leases.add(lease); return lease;
    }
    final class Lease {
        final Replay replay;
        private final Dependencies dependencies;
        private TargetReference reference;
        boolean released;
        Lease(Dependencies dependencies, Replay replay) { this.dependencies = dependencies; this.replay = replay; }
        void attach(MixedMine.Target target) { reference = new TargetReference(target, this); }
        boolean current(MineSupply supply) { return supply == MineSupply.this && !closed && !released; }
        void close() {
            if (released) return;
            released = true; if (reference != null) reference.clear(); leases.remove(this);
            if (dependencies != null) {
                Integer count = heldDependencies.computeIfPresent(dependencies, (unused, value) -> value == 1 ? null : value - 1);
                if (count == null) {
                    planDirty = true;
                    if (dependencies.transientKey != null) transientDependencies.remove(dependencies.transientKey, dependencies);
                }
            } else { replay.requests--; replay.retireIfUnused(); }
            finishClosing();
        }
    }
    private final class TargetReference extends WeakReference<MixedMine.Target> {
        final Lease lease;
        TargetReference(MixedMine.Target target, Lease lease) { super(target, abandoned); this.lease = lease; }
    }
    void ticketsChanged() {
        TargetReference reference;
        while ((reference = (TargetReference) abandoned.poll()) != null) reference.lease.close();
        for (var area : List.copyOf(areas)) if (area.copy != null) area.copy.ticketsChanged();
    }
    private void suspend() {
        bands.clear(); fronts = Set.of(); targetOffsets = Set.of(); waitingColumns.clear();
        while (evict()) {}
        if (selected != null) { selected.release(); selected = null; }
        for (Replay replay : List.copyOf(active)) { replay.members.clear(); replay.retireIfUnused(); }
        bindings.clear(); for (var area : areas) area.close();
        resetWhenIdle = true; resetRound();
        radius = -1; readyFuture = 0; forecastDirty = planDirty = true;
        statisticsStarted = false; fastRate = slowRate = 0; consumed = lowWindows = 0;
        latencyCount = latencyCursor = 0; waitingTick = Integer.MIN_VALUE; target = BASE;
    }
    void close() {
        if (closed) return;
        closed = true; consumers.clear();
        if (memory != null) memory.removeEviction(eviction);
        suspend(); fresh.clear(); columns.clear(); finishClosing();
    }
    private void finishClosing() {
        if (closed && leases.isEmpty() && reads.level == null && generation != null) { generation.close(); generation = null; }
    }
    void unload() {
        close(); for (Lease lease : List.copyOf(leases)) lease.close();
        reads.release(); finishClosing(); ticketsChanged();
    }
}
