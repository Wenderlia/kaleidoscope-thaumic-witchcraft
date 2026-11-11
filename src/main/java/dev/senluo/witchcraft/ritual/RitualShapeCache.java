package dev.senluo.witchcraft.ritual;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Loaded geometry only. Mark writes and block changes invalidate the affected chunk, not every ritual. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID)
public final class RitualShapeCache {
    private record Key(RitualShape shape, BlockPos anchor) {}
    private record Entry(RitualShape.Match result, long tick, List<BlockPos> chunks) {}
    private static final class Index {
        final Map<Key, Entry> entries = new LinkedHashMap<>(32, .75F, true);
        final Map<Long, Set<Key>> watchers = new HashMap<>();
        void remove(Key key) {
            Entry old = entries.remove(key);
            if (old == null) return;
            for (BlockPos pos : old.chunks) {
                long chunk = ChunkPos.asLong(pos);
                Set<Key> bucket = watchers.get(chunk);
                if (bucket != null && bucket.remove(key) && bucket.isEmpty()) watchers.remove(chunk);
            }
        }
    }
    private static final Map<ServerLevel, Index> LEVELS = new WeakHashMap<>();
    private RitualShapeCache() {}

    static RitualShape.Match match(RitualShape shape, ServerLevel level, BlockPos anchor) {
        Index index = LEVELS.computeIfAbsent(level, unused -> new Index());
        Key key = new Key(shape, anchor.immutable());
        Entry entry = index.entries.get(key);
        long now = level.getGameTime();
        // Tick eligibility can change without a block/load event. Unloaded results are never cached.
        if (entry != null && now >= entry.tick && now - entry.tick < 20
                && entry.chunks.stream().allMatch(pos -> level.hasChunkAt(pos) && level.shouldTickBlocksAt(pos)))
            return entry.result;
        index.remove(key);
        RitualShape.Match result = shape.inspect(level, anchor);
        if (result == RitualShape.Match.UNLOADED) return result;
        List<BlockPos> chunks = shape.marks().stream().map(anchor::offset)
                .map(pos -> new ChunkPos(pos).getWorldPosition()).distinct().toList();
        // Never reuse a partly unloaded INVALID result when another missing chunk becomes eligible.
        if (chunks.stream().anyMatch(pos -> !level.hasChunkAt(pos) || !level.shouldTickBlocksAt(pos))) return result;
        if (index.entries.size() >= 1024) index.remove(index.entries.keySet().iterator().next());
        index.entries.put(key, new Entry(result, now, chunks));
        for (BlockPos pos : chunks) index.watchers.computeIfAbsent(ChunkPos.asLong(pos), unused -> new HashSet<>()).add(key);
        return result;
    }
    static void changed(ServerLevel level, long chunk) {
        Index index = LEVELS.get(level);
        if (index == null) return;
        Set<Key> keys = index.watchers.get(chunk);
        if (keys != null) for (Key key : List.copyOf(keys)) index.remove(key);
    }
    static void invalidateColumn(ServerLevel level, BlockPos axis, int radius) {
        Index index = LEVELS.get(level);
        if (index != null) for (Key key : List.copyOf(index.entries.keySet()))
            if (key.anchor.getX() == axis.getX() && key.anchor.getZ() == axis.getZ()
                    && Math.abs((long) key.anchor.getY() - axis.getY()) <= radius) index.remove(key);
    }
    @SubscribeEvent public static void loaded(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) changed(level, event.getChunk().getPos().toLong());
    }
    @SubscribeEvent public static void unloaded(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) changed(level, event.getChunk().getPos().toLong());
    }
    @SubscribeEvent public static void unloaded(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) LEVELS.remove(level);
    }
    @SubscribeEvent public static void tags(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) LEVELS.clear();
    }
}
