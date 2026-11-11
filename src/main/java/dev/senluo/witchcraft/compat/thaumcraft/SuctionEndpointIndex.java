package dev.senluo.witchcraft.compat.thaumcraft;

import dev.tc4port.thaumcraft.api.ThaumcraftContent;
import net.minecraft.world.level.block.entity.BlockEntity;
import dev.senluo.witchcraft.ritual.SuctionCharge;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Shared positions, not inventories/providers. Only queried chunks are inspected. */
final class SuctionEndpointIndex {
    private static final Map<ServerLevel, SuctionEndpointIndex> LEVELS = new WeakHashMap<>();
    private static final class Bucket {
        List<BlockPos> positions = List.of(), outlets = List.of();
        long checked = Long.MIN_VALUE, version;
        boolean dirty = true;
    }
    private record Neighbors(List<Long> chunks, long[] versions, List<BlockPos> positions) {}
    private final Map<Long, Bucket> chunks = new HashMap<>();
    private final Map<BlockPos, Neighbors> neighbors = new LinkedHashMap<>(64, .75F, true);
    private long version;
    private Bucket newBucket() {
        Bucket bucket = new Bucket();
        // Even an empty reloaded chunk is a new generation, never revision zero again.
        bucket.version = ++version;
        return bucket;
    }
    static void load(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk) {
            var index = get(level);
            index.chunks.put(chunk.getPos().toLong(), index.newBucket());
            level.getProfiler().incrementCounter("kw_suction_chunk_load");
        }
    }
    static void unload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            var index = LEVELS.get(level);
            if (index != null) {
                long key = event.getChunk().getPos().toLong();
                index.chunks.remove(key);
                int before = index.neighbors.size();
                // Dependencies include unavailable chunks too. Unrelated outlets retain their cache.
                index.neighbors.values().removeIf(value -> value.chunks.contains(key));
                level.getProfiler().incrementCounter("kw_suction_unload_checked", before);
                level.getProfiler().incrementCounter("kw_suction_unload_evicted", before - index.neighbors.size());
            }
        }
    }
    static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) { LEVELS.remove(level); SuctionChannels.unload(level); }
    }
    static void changed(ServerLevel level, BlockPos pos) {
        var index = LEVELS.get(level);
        if (index == null) return;
        Bucket bucket = index.chunks.get(ChunkPos.asLong(pos));
        if (bucket != null && !bucket.dirty) {
            bucket.dirty = true;
            level.getProfiler().incrementCounter("kw_suction_block_dirty");
        }
    }
    static SuctionEndpointIndex get(ServerLevel level) {
        return LEVELS.computeIfAbsent(level, ignored -> new SuctionEndpointIndex());
    }
    static boolean outlet(BlockEntity be) {
        return be != null && (be.getType() == ThaumcraftContent.blockEntity("arcane_alembic")
                || be.getType() == ThaumcraftContent.blockEntity("advanced_alchemy_furnace"));
    }
    private Bucket refresh(ServerLevel level, long key) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
        if (chunk == null || !level.shouldTickBlocksAt(chunk.getPos().getWorldPosition())) return null;
        Bucket bucket = chunks.computeIfAbsent(key, unused -> newBucket());
        long now = level.getGameTime();
        // Local fallback covers unusual direct BE replacement without a Level.setBlock callback.
        if (!bucket.dirty && now >= bucket.checked && now - bucket.checked < 40) return bucket;
        level.getProfiler().incrementCounter(bucket.dirty ? "kw_suction_refresh_dirty" : "kw_suction_refresh_fallback");
        level.getProfiler().incrementCounter("kw_suction_enumerated_be", chunk.getBlockEntities().size());
        var positions = new ArrayList<BlockPos>(); var outlets = new ArrayList<BlockPos>();
        for (var be : chunk.getBlockEntities().values()) {
            if (be.isRemoved()) continue;
            // Sided-only capability providers must not be rejected by null-face discovery.
            positions.add(be.getBlockPos().immutable());
            if (outlet(be))
                outlets.add(be.getBlockPos().immutable());
        }
        positions.sort(BlockPos::compareTo); outlets.sort(BlockPos::compareTo);
        if (!positions.equals(bucket.positions) || !outlets.equals(bucket.outlets)) {
            level.getProfiler().incrementCounter("kw_suction_positions_changed");
            bucket.positions = List.copyOf(positions); bucket.outlets = List.copyOf(outlets);
            bucket.version = ++version;
        }
        bucket.checked = now; bucket.dirty = false;
        return bucket;
    }
    List<BlockPos> sources(ServerLevel level, BlockPos center, SuctionCharge charge) {
        var result = new ArrayList<BlockPos>();
        if (charge.rangeGroups() <= 4) {
            int radius = 4 << charge.rangeGroups();
            for (int cx = (center.getX()-radius) >> 4; cx <= (center.getX()+radius) >> 4; cx++)
                for (int cz = (center.getZ()-radius) >> 4; cz <= (center.getZ()+radius) >> 4; cz++)
                    append(result, refresh(level, ChunkPos.asLong(cx, cz)), center, charge);
        } else {
            // Large logical ranges inspect only loaded columns that actually intersect the field.
            for (long key : List.copyOf(chunks.keySet())) {
                int x = ChunkPos.getX(key) << 4, z = ChunkPos.getZ(key) << 4;
                long dx = Math.max(0L, Math.max((long)x-center.getX(), (long)center.getX()-x-15));
                long dz = Math.max(0L, Math.max((long)z-center.getZ(), (long)center.getZ()-z-15));
                if (charge.contains((double)dx*dx+(double)dz*dz)) append(result, refresh(level, key), center, charge);
            }
        }
        result.sort(BlockPos::compareTo); return result;
    }
    private static void append(List<BlockPos> output, Bucket bucket, BlockPos center, SuctionCharge charge) {
        if (bucket != null) for (var pos : bucket.outlets) if (charge.contains(pos.distSqr(center))) output.add(pos);
    }
    List<BlockPos> destinations(ServerLevel level, BlockPos outlet) {
        Neighbors previous = neighbors.get(outlet);
        if (previous != null) {
            boolean valid = true;
            for (int i = 0; i < previous.chunks.size(); i++) {
                Bucket bucket = refresh(level, previous.chunks.get(i));
                if ((bucket == null ? -1 : bucket.version) != previous.versions[i]) valid = false;
            }
            if (valid) {
                level.getProfiler().incrementCounter("kw_suction_neighbors_hit");
                return previous.positions;
            }
        }
        level.getProfiler().incrementCounter(previous == null ? "kw_suction_neighbors_miss" : "kw_suction_neighbors_stale");
        level.getProfiler().incrementCounter("kw_suction_neighbors_rebuild");
        var nearby = new ArrayList<Long>(); var revisions = new ArrayList<Long>();
        var positions = new ArrayList<BlockPos>();
        for (int cx = (outlet.getX()-12) >> 4; cx <= (outlet.getX()+12) >> 4; cx++)
            for (int cz = (outlet.getZ()-12) >> 4; cz <= (outlet.getZ()+12) >> 4; cz++) {
                long key = ChunkPos.asLong(cx, cz); Bucket bucket = refresh(level, key);
                nearby.add(key); revisions.add(bucket == null ? -1L : bucket.version);
                if (bucket == null) continue;
                for (BlockPos pos : bucket.positions)
                    if (!pos.equals(outlet) && Math.abs((long)pos.getX()-outlet.getX()) <= 12
                            && Math.abs((long)pos.getY()-outlet.getY()) <= 12 && Math.abs((long)pos.getZ()-outlet.getZ()) <= 12)
                        positions.add(pos);
            }
        positions.sort(Comparator.comparingDouble((BlockPos pos) -> outlet.distSqr(pos)).thenComparing(BlockPos::compareTo));
        if (neighbors.size() >= 2048 && !neighbors.containsKey(outlet)) {
            neighbors.remove(neighbors.keySet().iterator().next());
            level.getProfiler().incrementCounter("kw_suction_neighbors_lru_evicted");
        }
        var result = List.copyOf(positions);
        neighbors.put(outlet.immutable(), new Neighbors(List.copyOf(nearby), revisions.stream().mapToLong(Long::longValue).toArray(), result));
        return result;
    }
}
