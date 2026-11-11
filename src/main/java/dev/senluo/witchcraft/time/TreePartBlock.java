package dev.senluo.witchcraft.time;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Tree materials are ordinary stackable blocks. Only a nearby registered core restores them. */
public class TreePartBlock extends RotatedPillarBlock {
    public TreePartBlock(Properties properties) { super(properties); }
    @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (state.is(dev.senluo.witchcraft.time.TreeWood.LOGS) && level instanceof ServerLevel server) {
            TemporalWorld.get(server).trees.beforeWoodRemoval(pos);
            TemporalWorld.get(server).trees.preparePrune(pos, player);
        }
        return super.playerWillDestroy(level,pos,state,player);
    }

    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state,
            net.minecraft.world.entity.LivingEntity placer, net.minecraft.world.item.ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (state.is(dev.senluo.witchcraft.time.TreeWood.LOGS) && level instanceof ServerLevel server)
            TemporalWorld.get(server).trees.placedWood(pos, placer);
    }

    @Override public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player,
            boolean willHarvest, FluidState fluid) {
        TreeLogic trees = level instanceof ServerLevel server && state.is(dev.senluo.witchcraft.time.TreeWood.LOGS)
                ? TemporalWorld.get(server).trees : null;
        TreeLogic.PruneAttempt attempt = trees == null ? null : trees.takePrune(pos, player);
        boolean removed = super.onDestroyedByPlayer(state,level,pos,player,willHarvest,fluid);
        if (removed && trees != null && !level.getBlockState(pos).is(dev.senluo.witchcraft.time.TreeWood.LOGS)) trees.finishPrune(attempt,player);
        return removed;
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        super.onRemove(state,level,pos,replacement,moved);
        if (!state.is(replacement.getBlock()) && level instanceof ServerLevel server) TemporalWorld.get(server).trees.removed(pos);
    }
}
