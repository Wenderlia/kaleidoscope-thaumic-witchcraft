package dev.senluo.witchcraft.ritual;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

/** Registered source spaces, shared quotes and lazy allocation. Never scans world machines. */
public final class RitualPoints {
    public enum Permission { ALLOWED, DENIED, PENDING }
    private static final Set<BlockPos> RING_OFFSETS = RitualRings.DEFINITIONS.stream()
            .flatMap(ring -> ring.shape().marks().stream()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final class Column {
        boolean dirty = true, resolving;
        long revision, activity = Long.MIN_VALUE;
        final List<BlockPos> chunks;
        Set<Integer> planes = Set.of();
        Map<UUID, Map<Long, Integer>> grants = Map.of();
        List<RitualPointAllocation.Request> requests = List.of();
        Map<Long, Integer> capacities = Map.of();
        Map<Long, Integer> possible = Map.of();
        Set<UUID> pending = Set.of();
        Column(BlockPos source) {
            var chunks = new ArrayList<BlockPos>();
            for (int x = (source.getX() - 8) >> 4; x <= (source.getX() + 8) >> 4; x++)
                for (int z = (source.getZ() - 8) >> 4; z <= (source.getZ() + 8) >> 4; z++)
                    chunks.add(new ChunkPos(x, z).getWorldPosition());
            this.chunks = List.copyOf(chunks);
        }
    }
    private static final class Index {
        final Map<BlockPos, Column> columns = new HashMap<>();
        final Map<Long, Set<BlockPos>> watchers = new HashMap<>();
        Column column(BlockPos source) {
            BlockPos key = key(source);
            return columns.computeIfAbsent(key, unused -> {
                Column column = new Column(key);
                for (BlockPos pos : column.chunks)
                    watchers.computeIfAbsent(ChunkPos.asLong(pos), ignored -> new HashSet<>()).add(key);
                return column;
            });
        }
    }
    private static final Map<ServerLevel, Index> LEVELS = new WeakHashMap<>();
    private static BlockPos key(BlockPos p) { return new BlockPos(p.getX(), 0, p.getZ()); }
    public static void changed(ServerLevel level, BlockPos source) {
        Index index = LEVELS.get(level); if (index == null) return;
        Column column = index.columns.get(key(source));
        if (column != null) invalidate(level, column);
    }
    public static void changed(ServerLevel level, long chunk) {
        Index index = LEVELS.get(level); if (index == null) return;
        var keys = index.watchers.get(chunk); if (keys == null) return;
        for (BlockPos key : List.copyOf(keys)) {
            Column column = index.columns.get(key);
            if (column != null) invalidate(level, column);
        }
    }
    static void blockChanged(ServerLevel level, BlockPos pos, BlockState before, BlockState after) {
        Index index = LEVELS.get(level); if (index == null) return;
        var keys = index.watchers.get(ChunkPos.asLong(pos)); if (keys == null) return;
        for (BlockPos key : List.copyOf(keys)) {
            Column column = index.columns.get(key);
            if (column == null || !RING_OFFSETS.contains(new BlockPos(pos.getX() - key.getX(), 0, pos.getZ() - key.getZ()))) continue;
            if (column.planes.contains(pos.getY()) && RitualChalk.affectsGeometry(level, pos, pos, before, after)
                    || column.planes.contains(pos.getY() - 1) && RitualChalk.affectsGeometry(level, pos.below(), pos, before, after))
                invalidate(level, column);
        }
    }
    static void markChanged(ServerLevel level, BlockPos support) {
        Index index = LEVELS.get(level); if (index == null) return;
        var keys = index.watchers.get(ChunkPos.asLong(support)); if (keys == null) return;
        for (BlockPos key : List.copyOf(keys)) {
            Column column = index.columns.get(key);
            if (column != null && column.planes.contains(support.getY())
                    && RING_OFFSETS.contains(new BlockPos(support.getX() - key.getX(), 0, support.getZ() - key.getZ())))
                invalidate(level, column);
        }
    }
    private static void invalidate(ServerLevel level, Column column) {
        column.revision++;
        column.dirty = true;
        var store = StructuralRitualStore.get(level);
        for (var request : column.requests) store.invalidatePermission(request.id());
    }
    /** Simulation eligibility has no reliable block-change event; compare only the registered chunk flags. */
    static void refreshActivity(ServerLevel level, BlockPos source) {
        Index index = LEVELS.get(level); if (index == null) return;
        Column column = index.columns.get(key(source));
        if (column != null) refreshActivity(level, column);
    }
    private static void refreshActivity(ServerLevel level, Column column) {
        long activity = RitualShapeCache.activity(level, column.chunks);
        if (column.activity != activity) {
            column.activity = activity;
            invalidate(level, column);
        }
    }
    static void forget(ServerLevel level, BlockPos source) {
        Index index = LEVELS.get(level); if (index == null) return;
        BlockPos key = key(source); index.columns.remove(key);
        index.watchers.values().removeIf(keys -> { keys.remove(key); return keys.isEmpty(); });
        if (index.columns.isEmpty()) LEVELS.remove(level);
    }
    public static void clear(ServerLevel level) {
        LEVELS.remove(level);
        var store = StructuralRitualStore.get(level);
        for (var handle : store.inDimension(level.dimension().location())) store.invalidateMembers(handle.id());
    }
    public static void clear() { for (ServerLevel level : List.copyOf(LEVELS.keySet())) clear(level); }
    public static boolean allowed(ServerLevel level, UUID id) { return permission(level, id) == Permission.ALLOWED; }
    static Permission permission(ServerLevel level, UUID id) {
        var store = StructuralRitualStore.get(level);
        if (store.handle(id).isEmpty()) return Permission.DENIED;
        Column column = resolve(level, store.source(id));
        if (column.resolving || column.dirty) return Permission.PENDING;
        if (column.pending.contains(id)) return Permission.PENDING;
        return column.grants.containsKey(id) ? Permission.ALLOWED : Permission.DENIED;
    }
    /** A new or resized demand may use free capacity and reroute, but cannot evict existing grants. */
    public static boolean quote(ServerLevel level, UUID id, int cost) {
        var store = StructuralRitualStore.get(level);
        if (store.handle(id).isEmpty()) return false;
        Column column = resolve(level, store.source(id));
        if (column.resolving || column.dirty) return false;
        var requests = new ArrayList<RitualPointAllocation.Request>();
        RitualPointAllocation.Request candidate = null;
        for (var r : column.requests) {
            if (r.id().equals(id)) candidate = new RitualPointAllocation.Request(id, cost, r.origin(), r.order(), r.pools(), false);
            else if (column.grants.containsKey(r.id())) requests.add(new RitualPointAllocation.Request(
                    r.id(), r.cost(), r.origin(), r.order(), r.pools(), true));
        }
        if (candidate == null) return false;
        requests.add(candidate);
        var quoted = RitualPointAllocation.allocate(column.capacities, requests, List.of()).grants().keySet();
        return quoted.contains(id) && quoted.containsAll(column.grants.keySet());
    }
    private static Column resolve(ServerLevel level, BlockPos source) {
        var store = StructuralRitualStore.get(level);
        Index index = LEVELS.computeIfAbsent(level, unused -> new Index());
        Column column = index.column(source);
        refreshActivity(level, column);
        if (column.resolving || !column.dirty) return column;
        column.resolving = true;
        List<StructuralRitualStore.Handle> revoked = List.of();
        try {
            // A member replacement can update its own dependencies once during observation.
            for (int attempt = 0; attempt < 2; attempt++) {
                long revision = column.revision;
                var demands = store.demands(level, key(source));
                boolean pending = false;
                for (var demand : demands) pending |= RitualCenters.state(level, demand.id()).pending();
                if (pending || revision != column.revision) continue;
                demands = store.demands(level, key(source));
                var planes = new HashSet<Integer>();
                for (var demand : demands) RitualPlane.supplyPositions(demand.recipient())
                        .forEach(pos -> planes.add(pos.getY()));
                // Includes missing recipients and empty planes; saving stations never extend this dependency space.
                column.planes = Set.copyOf(planes);
                var capacities = new LinkedHashMap<Long, Integer>();
                var possible = new LinkedHashMap<Long, Integer>();
                var observations = new LinkedHashMap<Long, RitualRings.Layout>();
                var requests = new ArrayList<RitualPointAllocation.Request>();
                for (var demand : demands) {
                    var pools = new ArrayList<Long>();
                    BlockPos recipient = demand.recipient();
                    if (RitualCenters.state(level, demand.id()).present()
                            && recipient.getX() == source.getX() && recipient.getZ() == source.getZ())
                        RitualPlane.supplyPositions(recipient).filter(pos -> !level.isOutsideBuildHeight(pos)).forEach(pos -> {
                            long pool = pos.asLong();
                            var layout = observations.computeIfAbsent(pool, unused -> RitualPlane.observe(level, pos));
                            capacities.put(pool, layout.points());
                            possible.put(pool, layout.possiblePoints()); pools.add(pool);
                        });
                    requests.add(new RitualPointAllocation.Request(demand.id(), demand.cost(), demand.origin().asLong(),
                            demand.order(), List.copyOf(pools), demand.funded()));
                }
                var losses = new ArrayList<RitualPointAllocation.Loss>();
                var masks = new LinkedHashMap<Long, Integer>();
                observations.forEach((pool, layout) -> {
                    int old = store.poolMask(level, BlockPos.of(pool), layout.completeMask()), known = old;
                    for (var ring : RitualRings.DEFINITIONS) {
                        int bit = 1 << (ring.id() - 1);
                        var state = layout.state(ring);
                        if (state == RitualShape.Match.VALID) known |= bit;
                        else if (state == RitualShape.Match.INVALID) {
                            if ((old & bit) != 0) losses.add(new RitualPointAllocation.Loss(pool, ring.id()));
                            known &= ~bit;
                        }
                    }
                    // Merge each ring's evidence: an unknown neighbor does not erase a newly observed ring.
                    masks.put(pool, known);
                });
                losses.sort(Comparator.comparingInt(RitualPointAllocation.Loss::points).thenComparingLong(RitualPointAllocation.Loss::pool));
                refreshActivity(level, column);
                if (revision != column.revision || LEVELS.get(level) != index) continue;
                masks.forEach((pool, mask) -> store.rememberPool(level, BlockPos.of(pool), mask));
                if (column.capacities.equals(capacities) && column.possible.equals(possible) && column.requests.equals(requests)) {
                    column.dirty = false;
                    break;
                }
                var result = RitualPointAllocation.observe(capacities, possible, requests, losses);
                column.grants = result.reservations(); column.pending = result.pending();
                column.capacities = Map.copyOf(capacities); column.possible = Map.copyOf(possible);
                column.requests = requests.stream().map(r -> new RitualPointAllocation.Request(r.id(), r.cost(), r.origin(),
                        r.order(), r.pools(), column.grants.containsKey(r.id()))).toList();
                column.dirty = false;
                // Commit all funding and closed permissions before any effect callback can query this column.
                revoked = store.publishFunding(level, demands, column.grants.keySet(), column.pending);
                break;
            }
        } finally { column.resolving = false; }
        for (var handle : revoked) if (store.revoked(handle.id())) RitualCenters.revoked(level, handle);
        return column;
    }
    private RitualPoints() {}
}
