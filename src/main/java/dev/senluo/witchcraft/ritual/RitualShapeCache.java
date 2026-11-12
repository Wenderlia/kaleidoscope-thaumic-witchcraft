package dev.senluo.witchcraft.ritual;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Geometry evidence only. Source registrations outlive this bounded calculation cache. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID)
public final class RitualShapeCache {
    private record Key(RitualShape shape, BlockPos anchor) {
        boolean uses(BlockPos support) {
            return anchor.getY() == support.getY()
                    && shape.containsOffset(support.getX() - anchor.getX(), support.getZ() - anchor.getZ());
        }
    }
    private record Entry(RitualShape.Match result, long activity, List<BlockPos> chunks) {}
    private record Selection(Optional<RitualPlane> result, long activity, List<BlockPos> chunks) {}
    private static final Set<BlockPos> RING_OFFSETS = RitualRings.DEFINITIONS.stream()
            .flatMap(ring -> ring.shape().marks().stream()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final int RADIUS = RING_OFFSETS.stream()
            .mapToInt(pos -> Math.max(Math.abs(pos.getX()), Math.abs(pos.getZ()))).max().orElseThrow();
    private static final class Index {
        final Map<Key, Entry> entries = new LinkedHashMap<>(32, .75F, true);
        final Map<Long, Set<Key>> watchers = new HashMap<>();
        final Map<Key, Boolean> computing = new HashMap<>();
        final Map<BlockPos, Selection> selections = new LinkedHashMap<>(32, .75F, true);
        final Map<Long, Set<BlockPos>> selectionWatchers = new HashMap<>();
        final Map<BlockPos, Boolean> selecting = new HashMap<>();
        void remove(Key key) {
            Entry old = entries.remove(key);
            if (old == null) return;
            for (BlockPos pos : old.chunks) {
                long chunk = ChunkPos.asLong(pos);
                Set<Key> bucket = watchers.get(chunk);
                if (bucket != null && bucket.remove(key) && bucket.isEmpty()) watchers.remove(chunk);
            }
        }
        void removeSelection(BlockPos axis) {
            Selection old = selections.remove(axis);
            if (old == null) return;
            for (BlockPos pos : old.chunks) {
                long chunk = ChunkPos.asLong(pos);
                Set<BlockPos> bucket = selectionWatchers.get(chunk);
                if (bucket != null && bucket.remove(axis) && bucket.isEmpty()) selectionWatchers.remove(chunk);
            }
        }
    }
    private static final Map<ServerLevel, Index> LEVELS = new WeakHashMap<>();
    private RitualShapeCache() {}

    /** One derived choice, including the nearer empty candidates; independent of point allocation. */
    static Optional<RitualPlane> find(ServerLevel level, BlockPos position) {
        Index index = LEVELS.computeIfAbsent(level, unused -> new Index());
        BlockPos axis = position.immutable();
        Selection previous = index.selections.get(axis);
        List<BlockPos> chunks;
        if (previous != null) chunks = previous.chunks;
        else {
            var observed = new ArrayList<BlockPos>();
            for (int x = (axis.getX() - RADIUS) >> 4; x <= (axis.getX() + RADIUS) >> 4; x++)
                for (int z = (axis.getZ() - RADIUS) >> 4; z <= (axis.getZ() + RADIUS) >> 4; z++)
                    observed.add(new ChunkPos(x, z).getWorldPosition());
            chunks = List.copyOf(observed);
        }
        long activity = activity(level, chunks);
        if (previous != null && previous.activity == activity) return previous.result;
        if (index.selecting.putIfAbsent(axis, false) != null) return Optional.empty();
        index.removeSelection(axis);
        try {
            var result = RitualPlane.inspect(level, axis);
            if (Boolean.TRUE.equals(index.selecting.get(axis)) || LEVELS.get(level) != index
                    || activity != activity(level, chunks)) return Optional.empty();
            if (index.selections.size() >= 1024) index.removeSelection(index.selections.keySet().iterator().next());
            index.selections.put(axis, new Selection(result, activity, chunks));
            for (BlockPos pos : chunks) index.selectionWatchers
                    .computeIfAbsent(ChunkPos.asLong(pos), unused -> new HashSet<>()).add(axis);
            return result;
        } finally { index.selecting.remove(axis); }
    }

    private static boolean selectionUses(BlockPos axis, BlockPos support) {
        return Math.abs((long) support.getY() - axis.getY()) <= RitualPlane.HEIGHT_RADIUS
                && RING_OFFSETS.contains(new BlockPos(support.getX() - axis.getX(), 0, support.getZ() - axis.getZ()));
    }
    private static boolean selectionTouches(BlockPos axis, long chunk) {
        int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
        return x >= ((axis.getX() - RADIUS) >> 4) && x <= ((axis.getX() + RADIUS) >> 4)
                && z >= ((axis.getZ() - RADIUS) >> 4) && z <= ((axis.getZ() + RADIUS) >> 4);
    }
    private static void invalidateSelections(Index index, Collection<BlockPos> candidates,
                                             java.util.function.Predicate<BlockPos> affected) {
        for (BlockPos axis : List.copyOf(index.selecting.keySet()))
            if (affected.test(axis)) index.selecting.put(axis, true);
        if (candidates != null) for (BlockPos axis : List.copyOf(candidates))
            if (affected.test(axis)) index.removeSelection(axis);
    }

    /** Templates span at most five chunks per horizontal axis. No block or entity scans. */
    static long activity(ServerLevel level, List<BlockPos> chunks) {
        long result = 0;
        for (int i = 0; i < chunks.size(); i++) {
            BlockPos pos = chunks.get(i);
            if (level.hasChunkAt(pos) && level.shouldTickBlocksAt(pos)) result |= 1L << i;
        }
        return result;
    }
    static RitualShape.Match match(RitualShape shape, ServerLevel level, BlockPos anchor) {
        Index index = LEVELS.computeIfAbsent(level, unused -> new Index());
        Key key = new Key(shape, anchor.immutable());
        Entry entry = index.entries.get(key);
        List<BlockPos> chunks = entry != null ? entry.chunks : shape.marks().stream().map(anchor::offset)
                .map(pos -> new ChunkPos(pos).getWorldPosition()).distinct().toList();
        long activity = activity(level, chunks);
        if (entry != null && entry.activity == activity) return entry.result;
        if (index.computing.putIfAbsent(key, false) != null) return RitualShape.Match.UNLOADED;
        index.remove(key);
        try {
            RitualShape.Match result = shape.inspect(level, anchor);
            // A world callback can change even a key which has no cache entry yet.
            if (Boolean.TRUE.equals(index.computing.get(key)) || LEVELS.get(level) != index
                    || activity != activity(level, chunks)) return RitualShape.Match.UNLOADED;
            if (index.entries.size() >= 1024) index.remove(index.entries.keySet().iterator().next());
            index.entries.put(key, new Entry(result, activity, chunks));
            for (BlockPos pos : chunks) index.watchers.computeIfAbsent(ChunkPos.asLong(pos), unused -> new HashSet<>()).add(key);
            return result;
        } finally { index.computing.remove(key); }
    }
    static void blockChanged(ServerLevel level, BlockPos pos, BlockState before, BlockState after) {
        if (before == after) return;
        Index index = LEVELS.get(level);
        if (index != null) {
            for (Key key : List.copyOf(index.computing.keySet())) {
                BlockPos support = pos.getY() == key.anchor.getY() ? pos : pos.below();
                if (key.uses(support) && RitualChalk.affectsGeometry(level, support, pos, before, after)) index.computing.put(key, true);
            }
            Set<Key> keys = index.watchers.get(ChunkPos.asLong(pos));
            if (keys != null) for (Key key : List.copyOf(keys)) {
                BlockPos support = pos.getY() == key.anchor.getY() ? pos : pos.below();
                if (key.uses(support) && RitualChalk.affectsGeometry(level, support, pos, before, after)) index.remove(key);
            }
            invalidateSelections(index, index.selectionWatchers.get(ChunkPos.asLong(pos)), axis ->
                    selectionUses(axis, pos) && RitualChalk.affectsGeometry(level, pos, pos, before, after)
                    || selectionUses(axis, pos.below()) && RitualChalk.affectsGeometry(level, pos.below(), pos, before, after));
        }
        RitualPoints.blockChanged(level, pos, before, after);
    }
    static void markChanged(ServerLevel level, BlockPos support) {
        Index index = LEVELS.get(level);
        if (index != null) {
            for (Key key : List.copyOf(index.computing.keySet())) if (key.uses(support)) index.computing.put(key, true);
            Set<Key> keys = index.watchers.get(ChunkPos.asLong(support));
            if (keys != null) for (Key key : List.copyOf(keys)) if (key.uses(support)) index.remove(key);
            invalidateSelections(index, index.selectionWatchers.get(ChunkPos.asLong(support)), axis -> selectionUses(axis, support));
        }
        RitualPoints.markChanged(level, support);
    }
    static void changed(ServerLevel level, long chunk) {
        Index index = LEVELS.get(level);
        Set<Key> keys = index == null ? null : index.watchers.get(chunk);
        if (index != null) for (Key key : List.copyOf(index.computing.keySet()))
            if (key.shape.marks().stream().map(key.anchor::offset).anyMatch(pos -> ChunkPos.asLong(pos) == chunk)) index.computing.put(key, true);
        if (keys != null) for (Key key : List.copyOf(keys)) index.remove(key);
        if (index != null) invalidateSelections(index, index.selectionWatchers.get(chunk), axis -> selectionTouches(axis, chunk));
        RitualCenters.changed(level, chunk);
        RitualPoints.changed(level, chunk);
    }
    static void invalidateColumn(ServerLevel level, BlockPos axis, int radius) {
        Index index = LEVELS.get(level);
        if (index != null) {
            for (Key key : List.copyOf(index.computing.keySet()))
                if (key.anchor.getX() == axis.getX() && key.anchor.getZ() == axis.getZ()
                        && Math.abs((long) key.anchor.getY() - axis.getY()) <= radius) index.computing.put(key, true);
            for (Key key : List.copyOf(index.entries.keySet()))
                if (key.anchor.getX() == axis.getX() && key.anchor.getZ() == axis.getZ()
                        && Math.abs((long) key.anchor.getY() - axis.getY()) <= radius) index.remove(key);
            invalidateSelections(index, index.selections.keySet(), selected ->
                    selected.getX() == axis.getX() && selected.getZ() == axis.getZ()
                    && Math.abs((long) selected.getY() - axis.getY()) <= radius + (long) RitualPlane.HEIGHT_RADIUS);
        }
        RitualPoints.changed(level, axis);
    }
    @SubscribeEvent public static void loaded(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) changed(level, event.getChunk().getPos().toLong());
    }
    @SubscribeEvent public static void unloaded(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) changed(level, event.getChunk().getPos().toLong());
    }
    @SubscribeEvent public static void unloaded(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) { LEVELS.remove(level); RitualPoints.clear(level); RitualCenters.clear(level); }
    }
    @SubscribeEvent public static void tags(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) { LEVELS.clear(); RitualPoints.clear(); }
    }
}
