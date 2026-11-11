package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.ritual.RitualHerba;
import dev.tc4port.thaumcraft.api.ThaumcraftContent;

import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Track positions only. Re-read saved markers after chunk load and discard idle centers. */
final class RitualCenterTicks {
    private static final Map<ServerLevel, Set<BlockPos>> CENTERS = new WeakHashMap<>();

    static void register() {
        NeoForge.EVENT_BUS.addListener(RitualCenterTicks::load);
        NeoForge.EVENT_BUS.addListener(RitualCenterTicks::tick);
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Unload event) -> {
            var positions = CENTERS.get(event.getLevel());
            if (positions != null) positions.removeIf(pos -> ChunkPos.asLong(pos) == event.getChunk().getPos().toLong());
        });
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> CENTERS.remove(event.getLevel()));
    }

    static void track(ServerLevel level, BlockPos pos) {
        CENTERS.computeIfAbsent(level, ignored -> new HashSet<>()).add(pos.immutable());
    }

    private static void load(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) return;
        var matrix = ThaumcraftContent.blockEntity("infusion_matrix");
        var crucible = ThaumcraftContent.blockEntity("crucible");
        for (var pos : chunk.getBlockEntitiesPos()) {
            var state = chunk.getBlockState(pos);
            if (matrix.isValid(state) || crucible.isValid(state)) track(level, pos);
        }
    }

    private static void tick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.tickRateManager().runsNormally()) return;
        var positions = CENTERS.get(level);
        if (positions == null || positions.isEmpty()) return;
        for (var pos : List.copyOf(positions)) {
            if (!level.hasChunkAt(pos) || !level.shouldTickBlocksAt(pos)) continue;
            var entity = level.getBlockEntity(pos);
            var pot = EssentiaStores.pot(entity);
            if (entity != null && entity.getType() == ThaumcraftContent.blockEntity("infusion_matrix") && InfusionGeas.hasAttempt(entity)) {
                InfusionGeas.tick(entity);
            } else if (EssentiaStores.pot(entity) != null && RitualHerba.marked(entity)) {
                SteamRituals.refreshHerba(entity);
            } else {
                positions.remove(pos);
            }
        }
    }

    private RitualCenterTicks() {}
}
