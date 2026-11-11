package dev.senluo.witchcraft.compat.thaumcraft;

import dev.tc4port.thaumcraft.api.ThaumcraftContent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Relay native bellows changes to their consumers; no ticker or multiplier changes. */
final class BellowsUpdates {
    private static final Map<ServerLevel, Pending> LEVELS = new WeakHashMap<>();
    private static final AtomicLong TAGS = new AtomicLong();
    private static Block bellows;
    private static final class Pending {
        final Map<Long, Map<BlockPos, Direction>> sources = new HashMap<>();
        final Set<Long> chunkChanges = new HashSet<>(), loading = new HashSet<>();
        Map<BlockPos, BlockPos> targets = new HashMap<>();
        long tags = TAGS.get();

        void queue(BlockPos source, Direction facing) {
            targets.putIfAbsent(source.relative(facing), source.immutable());
        }
        void queue(Map<BlockPos, Direction> sources) {
            if (sources != null) sources.forEach(this::queue);
        }
    }
    private BellowsUpdates() {}

    static void register() {
        bellows = ThaumcraftContent.block("arcane_bellows");
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, BellowsUpdates::neighbors);
        NeoForge.EVENT_BUS.addListener(BellowsUpdates::load);
        NeoForge.EVENT_BUS.addListener(BellowsUpdates::unload);
        NeoForge.EVENT_BUS.addListener(BellowsUpdates::tick);
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
            if (event.getLevel() instanceof ServerLevel level) LEVELS.remove(level);
        });
        NeoForge.EVENT_BUS.addListener((TagsUpdatedEvent event) -> {
            // Integrated-server tag loading can originate on the client thread.
            if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) TAGS.incrementAndGet();
        });
    }

    static void changed(ServerLevel level, BlockPos pos, BlockState before, BlockState after) {
        if (!before.is(bellows) && !after.is(bellows)) return;
        Pending pending = LEVELS.computeIfAbsent(level, ignored -> new Pending());
        long key = ChunkPos.asLong(pos);
        if (before.is(bellows)) pending.queue(pos, before.getValue(BlockStateProperties.FACING));
        if (after.is(bellows)) {
            Direction facing = after.getValue(BlockStateProperties.FACING);
            pending.sources.computeIfAbsent(key, ignored -> new HashMap<>()).put(pos.immutable(), facing);
            pending.queue(pos, facing);
        } else {
            var bucket = pending.sources.get(key);
            if (bucket != null && bucket.remove(pos) != null && bucket.isEmpty()) pending.sources.remove(key);
        }
    }

    private static void neighbors(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Pending pending = LEVELS.get(level);
        if (pending == null || pending.sources.isEmpty()) return;
        // Inspect only indexed bellows among the actual notified sides, never world voxels.
        for (Direction side : event.getNotifiedSides()) {
            BlockPos pos = event.getPos().relative(side);
            var bucket = pending.sources.get(ChunkPos.asLong(pos));
            Direction facing = bucket == null ? null : bucket.get(pos);
            if (facing != null) pending.queue(pos, facing);
        }
    }

    private static void load(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) return;
        Map<BlockPos, Direction> found = new HashMap<>();
        // Read only the supplied chunk: it may not yet be promoted to FULL.
        for (BlockPos pos : chunk.getBlockEntitiesPos()) {
            BlockState state = chunk.getBlockState(pos);
            if (state.is(bellows)) found.put(pos.immutable(), state.getValue(BlockStateProperties.FACING));
        }
        Pending pending = LEVELS.get(level);
        if (found.isEmpty() && pending == null) return;
        if (pending == null) { pending = new Pending(); LEVELS.put(level, pending); }
        long key = chunk.getPos().toLong();
        pending.queue(pending.sources.remove(key));
        if (!found.isEmpty()) pending.sources.put(key, found);
        pending.loading.add(key);
        pending.chunkChanges.add(key);
    }

    private static void unload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Pending pending = LEVELS.get(level);
        if (pending == null) return;
        long key = event.getChunk().getPos().toLong();
        pending.queue(pending.sources.remove(key));
        pending.loading.remove(key);
        pending.chunkChanges.add(key);
    }

    private static void tick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Pending pending = LEVELS.get(level);
        if (pending == null) return;
        long tags = TAGS.get();
        if (pending.tags != tags) {
            pending.tags = tags;
            pending.sources.values().forEach(pending::queue);
        }
        var changes = pending.chunkChanges.iterator();
        while (changes.hasNext()) {
            long key = changes.next();
            int x = ChunkPos.getX(key), z = ChunkPos.getZ(key);
            if (pending.loading.contains(key) && level.getChunkSource().getChunkNow(x, z) == null) continue;
            pending.loading.remove(key);
            // Native blowsToward observes a two-block horizontal margin, including diagonals.
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                pending.queue(pending.sources.get(ChunkPos.asLong(x + dx, z + dz)));
            changes.remove();
        }
        if (!pending.targets.isEmpty()) {
            var targets = pending.targets;
            pending.targets = new HashMap<>();
            // Detach this batch before callbacks; new changes belong to the next batch.
            targets.forEach((target, source) -> {
                LevelChunk chunk = level.getChunkSource().getChunkNow(target.getX() >> 4, target.getZ() >> 4);
                if (chunk != null && chunk.getBlockState(target).hasBlockEntity())
                    level.neighborChanged(target, bellows, source);
            });
        }
        if (pending.sources.isEmpty() && pending.targets.isEmpty() && pending.chunkChanges.isEmpty()) LEVELS.remove(level);
    }
}
