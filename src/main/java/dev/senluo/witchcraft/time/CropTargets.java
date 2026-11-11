package dev.senluo.witchcraft.time;

import dev.senluo.witchcraft.compat.CropGrowth;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Loaded positions only. The world scheduler explicitly supplies topology and lifecycle changes. */
public final class CropTargets {
    private final ServerLevel level;
    private final Map<Long, Set<BlockPos>> chunks = new HashMap<>();
    private final Map<Colony, Area> groups = new IdentityHashMap<>();
    private final Map<Long, Set<Area>> watchers = new HashMap<>();

    public CropTargets(ServerLevel level) { this.level = level; }
    public void topologyChanged() { groups.clear(); watchers.clear(); }
    void forget(Colony colony) {
        Area old = groups.remove(colony);
        if (old == null) return;
        for (long chunk : old.chunks) {
            Set<Area> bucket = watchers.get(chunk);
            if (bucket != null && bucket.remove(old) && bucket.isEmpty()) watchers.remove(chunk);
        }
    }
    public void clear() { topologyChanged(); chunks.clear(); }

    public void chunkChanged(long chunk) {
        chunks.remove(chunk);
        Set<Area> affected = watchers.get(chunk);
        if (affected != null) for (Area area : affected) area.dirtyChunks.add(chunk);
    }

    public void blockChanged(BlockPos pos, BlockState before, BlockState after) {
        // Age changes do not alter candidate membership; execution reads the current state.
        boolean crop = CropGrowth.accepts(after);
        if (crop == CropGrowth.accepts(before)) return;
        long chunk = ChunkPos.asLong(pos);
        Set<BlockPos> indexed = chunks.get(chunk);
        if (indexed == null) return; // Discovery waits until a consumer actually needs this chunk.
        boolean changed = crop ? indexed.add(pos.immutable()) : indexed.remove(pos);
        Set<Area> affected = watchers.get(chunk);
        if (!changed || affected == null) return;
        for (Area area : affected) {
            if (area.dirtyChunks.contains(chunk)) continue; // First/current chunk discovery is still pending.
            if (crop) {
                if (!area.covers(pos)) continue;
                BlockPos added = pos.immutable();
                area.matched.computeIfAbsent(chunk, unused -> new HashSet<>()).add(added);
                area.listDirty |= area.positions.add(added);
            } else {
                Set<BlockPos> matches = area.matched.get(chunk);
                if (matches != null && matches.remove(pos)) {
                    area.listDirty |= area.positions.remove(pos);
                    if (matches.isEmpty()) area.matched.remove(chunk);
                }
            }
        }
    }

    public List<BlockPos> get(Colony colony) {
        Area area = area(colony);
        for (long key : area.dirtyChunks) {
            Set<BlockPos> old = area.matched.remove(key);
            if (old != null) for (BlockPos pos : old) area.listDirty |= area.positions.remove(pos);
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null) continue;
            Set<BlockPos> found = new HashSet<>();
            for (BlockPos pos : chunks.computeIfAbsent(key, ignored -> discover(chunk)))
                if (area.covers(pos)) found.add(pos);
            if (!found.isEmpty()) {
                area.matched.put(key, found);
                area.listDirty |= area.positions.addAll(found);
            }
        }
        area.dirtyChunks.clear();
        if (area.listDirty) {
            area.targets = List.copyOf(area.positions);
            area.listDirty = false;
        }
        return area.targets;
    }

    private Area area(Colony colony) {
        return groups.computeIfAbsent(colony, group -> {
            Area area = new Area(group);
            for (long chunk : area.chunks)
                watchers.computeIfAbsent(chunk, unused -> new HashSet<>()).add(area);
            return area;
        });
    }

    /** Geometry-only planting sites are built once per connection graph, not once per container. */
    List<BlockPos> hangingSites(Colony colony) {
        Area area = area(colony);
        if (area.hangingSites == null)
            area.hangingSites = area.logs.stream().filter(pos -> !area.logs.contains(pos.below())).map(BlockPos::below)
                    .sorted(Comparator.comparingLong(BlockPos::asLong)).toList();
        return area.hangingSites;
    }
    boolean hangsFrom(Colony colony, BlockPos crop) {
        return area(colony).logs.contains(crop.above());
    }

    private Set<BlockPos> discover(LevelChunk chunk) {
        Set<BlockPos> found = new HashSet<>();
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (!section.maybeHas(CropGrowth::accepts)) continue;
            int bottom = level.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                if (CropGrowth.accepts(section.getBlockState(x, y, z)))
                    found.add(new BlockPos(chunk.getPos().getMinBlockX() + x, bottom + y, chunk.getPos().getMinBlockZ() + z));
        }
        return found;
    }

    private static final class Area {
        final Set<BlockPos> feeds = new HashSet<>();
        final Set<BlockPos> bodies = new HashSet<>();
        final Set<BlockPos> logs;
        List<BlockPos> hangingSites;
        final Set<Long> chunks = new HashSet<>();
        final Set<Long> dirtyChunks = new HashSet<>();
        final Map<Long, Set<BlockPos>> matched = new HashMap<>();
        final NavigableSet<BlockPos> positions = new TreeSet<>(Comparator.comparingLong(BlockPos::asLong));
        boolean listDirty;
        List<BlockPos> targets = List.of();

        Area(Colony group) {
            group.roots.stream().limit(256).forEach(feeds::add);
            logs = Set.copyOf(group.logs);
            group.members.forEach(member -> bodies.add(member.getBlockPos()));
            for (BlockPos pos : feeds) CropSupplyRange.ROOT.watchChunks(pos, chunks::add);
            for (BlockPos pos : logs) CropSupplyRange.WOOD.watchChunks(pos, chunks::add);
            for (BlockPos pos : bodies) CropSupplyRange.BODY.watchChunks(pos, chunks::add);
            dirtyChunks.addAll(chunks);
        }

        boolean covers(BlockPos crop) {
            return CropSupplyRange.ROOT.covers(feeds, crop)
                    || CropSupplyRange.WOOD.covers(logs, crop)
                    || CropSupplyRange.BODY.covers(bodies, crop);
        }
    }
}
