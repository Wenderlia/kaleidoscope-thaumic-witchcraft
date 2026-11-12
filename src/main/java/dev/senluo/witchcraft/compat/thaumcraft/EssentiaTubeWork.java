package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.time.WorkSources;
import dev.tc4port.thaumcraft.api.ThaumcraftContent;
import dev.tc4port.thaumcraft.api.essentia.EssentiaApi;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.server.level.ServerLevel;

/** TC4R tube semantics only; SteamWork owns spatial lookup and additional native updates. */
final class EssentiaTubeWork implements WorkSources.Process<BlockEntity> {
    private EssentiaTubeWork() {}

    static void register() {
        WorkSources.registerProcess(ThaumcraftContent.blockEntity("essentia_tube"), new EssentiaTubeWork());
    }

    @Override public boolean supportsSteam() { return true; }

    @Override
    public double price(ServerLevel level, BlockEntity tube) {
        // Transport does not create a second income source for the essentia being moved.
        return 0;
    }

    @Override
    public boolean steamWork(ServerLevel level, BlockEntity tube) {
        // Empty pipes still propagate suction. The native count drives suction, transfer,
        // valves and venting; do not replace it with global game time or duplicate transfers.
        return EssentiaApi.transport(level, tube.getBlockPos(), null) != null;
    }

    @Override
    public boolean outputWork(ServerLevel level, BlockEntity tube) {
        return steamWork(level, tube);
    }
}
