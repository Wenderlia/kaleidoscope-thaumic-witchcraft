package dev.senluo.witchcraft.time;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Player intent stays separate from the platform's successful removal. No periodic scan. */
final class YearsBuildingEdits {
    interface Part {}
    private YearsBuildingEdits() {}
    static BlockState normalized(BlockState state) {
        if (!(state.getBlock() instanceof Part)) return state;
        var powered = net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED;
        var water = net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED;
        if (state.hasProperty(powered)) state = state.setValue(powered, false);
        if (state.hasProperty(water)) state = state.setValue(water, false);
        return state;
    }
    static void before(Level level, BlockPos pos, Player player) {
        if (level instanceof ServerLevel server) {
            var trees = TemporalWorld.get(server).trees;
            trees.beforeWoodRemoval(pos); trees.preparePrune(pos, player);
        }
    }
    static TreeLogic.PruneAttempt take(Level level, BlockPos pos, Player player) {
        return level instanceof ServerLevel server ? TemporalWorld.get(server).trees.takePrune(pos, player) : null;
    }
    static void finished(Level level, BlockPos pos, BlockState state, Player player, boolean removed, TreeLogic.PruneAttempt attempt) {
        if (!removed || attempt == null || level.getBlockState(pos).is(state.getBlock()) || !(level instanceof ServerLevel server)) return;
        var trees = TemporalWorld.get(server).trees;
        trees.finishPrune(attempt, player);
        if (state.getBlock() instanceof DoorBlock) {
            BlockPos other = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            // Native door destruction removes both cells, including creative upper-half removal.
            if (!level.getBlockState(other).is(state.getBlock())
                    && level.getBlockEntity(attempt.core()) instanceof LivingEntity core && trees.ownedBy(core,other)) trees.finishPrune(
                    new TreeLogic.PruneAttempt(other, attempt.core(), attempt.identity(), attempt.tick()), player);
        }
    }
    static void placed(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer) {
        if (!(level instanceof ServerLevel server)) return;
        var trees = TemporalWorld.get(server).trees;
        trees.placedWood(pos, placer);
        if (state.getBlock() instanceof DoorBlock) trees.placedWood(pos.above(), placer);
    }
}
