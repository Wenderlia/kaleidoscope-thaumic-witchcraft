package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.ritual.RitualOfferingArea;
import dev.senluo.witchcraft.ritual.RitualPlane;
import dev.senluo.witchcraft.ritual.RitualShape;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Loaded, placed stabilizers only. Cache scores, never block entities or persistent ownership. */
final class CrucibleStabilizers {
    private record Key(BlockPos anchor, int ring) {}
    private record Entry(int points, long tick, Set<Long> chunks,
                         List<dev.senluo.witchcraft.time.ConnectedTreeWood.Connection> wood) {}
    private static final class Index {
        final Map<Key, Entry> entries = new LinkedHashMap<>(32, .75F, true);
        final Map<Long, Set<Key>> watchers = new HashMap<>();
        void remove(Key key) {
            Entry old = entries.remove(key);
            if (old == null) return;
            for (long chunk : old.chunks) {
                var bucket = watchers.get(chunk);
                if (bucket != null && bucket.remove(key) && bucket.isEmpty()) watchers.remove(chunk);
            }
        }
    }
    private static final Map<ServerLevel, Index> LEVELS = new WeakHashMap<>();
    private static final int MAX_RADIUS = RitualShape.LARGE.marks().stream()
            .mapToInt(pos -> Math.max(Math.abs(pos.getX()), Math.abs(pos.getZ()))).max().orElseThrow();
    private CrucibleStabilizers() {}

    static boolean matches(BlockState state) {
        return InfusionStabilizers.ordinary(state);
    }
    private static boolean loaded(ServerLevel level, long chunk) {
        ChunkPos pos = new ChunkPos(chunk);
        return level.getChunkSource().getChunkNow(pos.x, pos.z) != null && level.shouldTickBlocksAt(pos.getWorldPosition());
    }
    /** A missing part of a possible ring must not be mistaken for a broken ring and lower safe capacity. */
    static boolean canInspect(ServerLevel level, BlockPos axis) {
        for (int x = (axis.getX() - MAX_RADIUS) >> 4; x <= (axis.getX() + MAX_RADIUS) >> 4; x++)
            for (int z = (axis.getZ() - MAX_RADIUS) >> 4; z <= (axis.getZ() + MAX_RADIUS) >> 4; z++)
                if (!loaded(level, ChunkPos.asLong(x, z))) return false;
        return true;
    }
    static OptionalInt score(ServerLevel level, RitualPlane plane) {
        Index index = LEVELS.computeIfAbsent(level, unused -> new Index());
        Key key = new Key(plane.anchor(), plane.outermostRing());
        long now = level.getGameTime();
        var temporal = dev.senluo.witchcraft.time.TemporalWorld.get(level);
        Entry old = index.entries.get(key);
        if (old != null && now >= old.tick && now - old.tick < 20 && old.wood.stream().allMatch(proof -> proof.current(now))
                && old.chunks.stream().allMatch(c -> loaded(level, c)))
            return OptionalInt.of(old.points);
        index.remove(key);
        var area = RitualOfferingArea.outermost(plane);
        var bounds = area.bounds();
        Set<BlockPos> positions = new HashSet<>();
        Set<Long> chunks = new HashSet<>();
        Set<dev.senluo.witchcraft.time.ConnectedTreeWood.Connection> wood = new HashSet<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = (int) bounds.minX; x < bounds.maxX; x++) {
            for (int z = (int) bounds.minZ; z < bounds.maxZ; z++) {
                if (!area.containsHorizontal(new Vec3(x + .5, 0, z + .5))) continue;
                long chunkKey = ChunkPos.asLong(x >> 4, z >> 4);
                if (chunks.add(chunkKey) && !loaded(level, chunkKey)) return OptionalInt.empty();
                var chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                for (int y = Math.max(level.getMinBuildHeight(), (int) bounds.minY);
                        y < Math.min(level.getMaxBuildHeight(), (int) bounds.maxY); y++) {
                    cursor.set(x, y, z);
                    BlockState state = chunk.getBlockState(cursor);
                    if (state.is(dev.senluo.witchcraft.time.TreeWood.LOGS)) {
                        var proof = temporal.treeWoodConnection(cursor);
                        wood.add(proof);
                        if (proof.connected()) positions.add(cursor.immutable());
                    } else if (matches(state)) positions.add(cursor.immutable());
                }
            }
        }
        int points = 0;
        BlockPos anchor = plane.anchor();
        for (BlockPos pos : positions) {
            BlockPos mirror = new BlockPos(2 * anchor.getX() - pos.getX(), pos.getY(), 2 * anchor.getZ() - pos.getZ());
            points += !mirror.equals(pos) && positions.contains(mirror) ? 2 : 1;
        }
        if (index.entries.size() >= 1024) index.remove(index.entries.keySet().iterator().next());
        index.entries.put(key, new Entry(points, now, Set.copyOf(chunks), List.copyOf(wood)));
        for (long chunk : chunks) index.watchers.computeIfAbsent(chunk, unused -> new HashSet<>()).add(key);
        return OptionalInt.of(points);
    }
    static void changed(ServerLevel level, long chunk) {
        Index index = LEVELS.get(level);
        if (index == null) return;
        var keys = index.watchers.get(chunk);
        if (keys != null) for (Key key : List.copyOf(keys)) index.remove(key);
    }
    static void load(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) changed(level, event.getChunk().getPos().toLong());
    }
    static void unload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) changed(level, event.getChunk().getPos().toLong());
    }
    static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) LEVELS.remove(level);
    }
    static void tags(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) LEVELS.clear();
    }
}
