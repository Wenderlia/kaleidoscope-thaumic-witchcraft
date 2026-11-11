package dev.senluo.witchcraft.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;

/** A one-block native placement boundary, including protection events and conditional restoration. */
public final class BlockPlacement {
    private BlockPlacement() {}
    public static boolean commit(ServerPlayer player, BlockPos pos, BlockState before, BlockState after) {
        var level = player.serverLevel();
        if (!level.hasChunkAt(pos) || level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)
                || level.getBlockState(pos) != before || !level.mayInteract(player, pos)
                || level.captureBlockSnapshots || level.restoringBlockSnapshots || !level.capturedBlockSnapshots.isEmpty()) return false;
        BlockSnapshot snapshot = null;
        boolean accepted = false;
        try {
            level.captureBlockSnapshots = true;
            boolean changed = level.setBlock(pos, after, Block.UPDATE_ALL);
            level.captureBlockSnapshots = false;
            if (!level.capturedBlockSnapshots.isEmpty()) snapshot = level.capturedBlockSnapshots.getFirst();
            level.capturedBlockSnapshots.clear();
            if (!changed || snapshot == null) return false;
            if (EventHooks.onBlockPlace(player, snapshot, Direction.UP) || level.getBlockState(pos) != after) return false;
            accepted = true;
            after.onPlace(level, pos, before, false);
            level.markAndNotifyBlock(pos, level.getChunkAt(pos), before, after, Block.UPDATE_ALL, 512);
            return level.getBlockState(pos) == after;
        } finally {
            level.captureBlockSnapshots = false;
            if (snapshot == null && !level.capturedBlockSnapshots.isEmpty()) snapshot = level.capturedBlockSnapshots.getFirst();
            level.capturedBlockSnapshots.clear();
            if (!accepted && snapshot != null && level.getBlockState(pos) == after) {
                level.restoringBlockSnapshots = true;
                try { snapshot.restore(snapshot.getFlags() | Block.UPDATE_CLIENTS); }
                finally { level.restoringBlockSnapshots = false; }
            }
        }
    }
}
