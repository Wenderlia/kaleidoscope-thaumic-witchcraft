package dev.senluo.witchcraft.ritual;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Distinguished units within the vessel's real balance, never a second copy of that balance. */
public final class RitualHerba {
    private RitualHerba() {}
    public static boolean marked(BlockEntity vessel) {
        return count(vessel) > 0;
    }
    public static int count(BlockEntity vessel) {
        Integer count = vessel.getExistingDataOrNull(RitualContent.RITUAL_HERBA);
        return count == null ? 0 : count;
    }
    public static void set(BlockEntity vessel, boolean marked) {
        set(vessel, marked ? 1 : 0);
    }
    public static void set(BlockEntity vessel, int count) {
        if (count < 0 || count > 3) throw new IllegalArgumentException("Invalid ritual Herba count");
        if (count(vessel) == count) return;
        var level = vessel.getLevel();
        if (level == null || level.isClientSide) return;
        // Serialize zero explicitly too, so an existing client view is cleared by the native update tag.
        vessel.setData(RitualContent.RITUAL_HERBA, count);
        level.sendBlockUpdated(vessel.getBlockPos(), vessel.getBlockState(), vessel.getBlockState(), Block.UPDATE_CLIENTS);
    }
}
