package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.ritual.RitualWater;
import dev.senluo.witchcraft.ritual.WaterReceivers;
import dev.tc4port.thaumcraft.api.ThaumcraftContent;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

/** Positions only. Palette scans are lazy, limited to relevant chunks, and invalidated by real changes. */
final class RitualWaterIndex {
    private static final Map<ServerLevel, RitualWaterIndex> LEVELS = new WeakHashMap<>();
    private static final class Bucket {
        Set<BlockPos> centers = Set.of(), entities = Set.of(), receivers = Set.of();
        long checked = Long.MIN_VALUE;
        boolean dirty = true;
    }
    private final Map<Long, Bucket> chunks = new HashMap<>();
    // Unchecked chunks and known crucibles, including ordinary crucibles with no active ritual.
    private final LongOpenHashSet centerChunks = new LongOpenHashSet();

    private Bucket bucket(long key) {
        return chunks.computeIfAbsent(key, missing -> {
            centerChunks.add(key);
            return new Bucket();
        });
    }

    static RitualWaterIndex get(ServerLevel level) { return LEVELS.computeIfAbsent(level, ignored -> new RitualWaterIndex()); }
    static void load(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk) {
            var index = get(level);
            Bucket bucket = new Bucket();
            index.chunks.put(chunk.getPos().toLong(), bucket);
            index.entities(chunk, bucket, level.getGameTime());
        }
    }
    static void unload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            var index = LEVELS.get(level);
            if (index == null) return;
            long key = event.getChunk().getPos().toLong();
            index.chunks.remove(key);
            index.centerChunks.remove(key);
        }
    }
    static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) { LEVELS.remove(level); RitualWaterSupply.unload(level); }
    }
    static void tags(TagsUpdatedEvent event) {
        for (var index : LEVELS.values()) for (var bucket : index.chunks.values()) bucket.dirty = true;
    }
    static void changed(ServerLevel level, BlockPos pos, boolean entityChanged, boolean receiverChanged) {
        var index = get(level);
        long key = ChunkPos.asLong(pos);
        var bucket = index.bucket(key);
        if (entityChanged) {
            bucket.checked = Long.MIN_VALUE;
            index.centerChunks.add(key);
        }
        if (receiverChanged && !bucket.dirty) {
            var receivers = new HashSet<>(bucket.receivers);
            if (WaterReceivers.candidate(level.getBlockState(pos))) receivers.add(pos.immutable()); else receivers.remove(pos);
            bucket.receivers = receivers;
        }
    }
    private void entities(LevelChunk chunk, Bucket bucket, long now) {
        Set<BlockPos> centers = new HashSet<>(), entities = new HashSet<>();
        var live = chunk.getBlockEntities();
        for (var be : live.values()) if (!be.isRemoved()) {
            BlockPos pos = be.getBlockPos().immutable();
            entities.add(pos);
            if (be.getType() == ThaumcraftContent.blockEntity("crucible")) centers.add(pos);
        }
        // Packed entities can become live without a block change. Check them on load or invalidation.
        boolean pendingCenter = false;
        if (bucket.checked == Long.MIN_VALUE) {
            for (BlockPos pos : chunk.getBlockEntitiesPos()) {
                if (!live.containsKey(pos) && ThaumcraftContent.blockEntity("crucible").isValid(chunk.getBlockState(pos))) {
                    pendingCenter = true;
                    break;
                }
            }
        }
        bucket.centers = centers;
        bucket.entities = entities;
        bucket.checked = pendingCenter ? Long.MIN_VALUE : now;
        long key = chunk.getPos().toLong();
        if (pendingCenter || !centers.isEmpty()) centerChunks.add(key);
        else centerChunks.remove(key);
    }
    private Bucket refresh(ServerLevel level, long key, boolean receivers) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
        if (chunk == null) return null;
        Bucket bucket = bucket(key);
        long now = level.getGameTime();
        if (bucket.checked == Long.MIN_VALUE || now < bucket.checked || now - bucket.checked >= 40)
            entities(chunk, bucket, now);
        if (receivers && bucket.dirty) {
            Set<BlockPos> found = new HashSet<>();
            var sections = chunk.getSections();
            for (int i = 0; i < sections.length; i++) {
                var section = sections[i];
                if (!section.maybeHas(WaterReceivers::candidate)) continue;
                int bottom = level.getSectionYFromSectionIndex(i) << 4;
                for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                    if (WaterReceivers.candidate(section.getBlockState(x, y, z)))
                        found.add(new BlockPos((chunk.getPos().x << 4) + x, bottom + y, (chunk.getPos().z << 4) + z));
            }
            bucket.receivers = found; bucket.dirty = false;
        }
        return bucket;
    }
    List<BlockPos> centers(ServerLevel level) {
        var result = new ArrayList<BlockPos>();
        for (long key : centerChunks.toLongArray()) {
            Bucket bucket = refresh(level, key, false);
            if (bucket != null) for (var pos : bucket.centers) if (level.shouldTickBlocksAt(pos)) result.add(pos);
        }
        result.sort(BlockPos::compareTo); return result;
    }
    List<BlockPos> nearby(ServerLevel level, BlockPos center, boolean receivers) {
        int r = RitualWater.REACH;
        var result = new ArrayList<BlockPos>();
        for (int x = (center.getX()-r) >> 4; x <= (center.getX()+r) >> 4; x++)
            for (int z = (center.getZ()-r) >> 4; z <= (center.getZ()+r) >> 4; z++) {
                Bucket bucket = refresh(level, ChunkPos.asLong(x,z), receivers);
                if (bucket == null) continue;
                for (var pos : receivers ? bucket.receivers : bucket.entities)
                    if (pos.distSqr(center) <= r*r && level.shouldTickBlocksAt(pos)) result.add(pos);
            }
        result.sort(BlockPos::compareTo); return result;
    }
}
