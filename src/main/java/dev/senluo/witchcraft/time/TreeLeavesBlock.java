package dev.senluo.witchcraft.time;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/** Native leaf distance/decay; persistent leaves are player-placed or explicitly remembered grafts. */
public final class TreeLeavesBlock extends LeavesBlock {
    public static final BooleanProperty GREAT_TREE = BooleanProperty.create("great_tree");

    public TreeLeavesBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(GREAT_TREE, false)
                .setValue(PERSISTENT, true).setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y));
    }

    public BlockState naturalState() {
        return defaultBlockState().setValue(PERSISTENT, false);
    }

    public static boolean placedByPlayer(BlockState state) {
        return state.is(TemporalContent.TIME_LEAVES.get()) && state.getValue(PERSISTENT);
    }

    public static BlockState reconcile(BlockState actual, BlockState planned) {
        return actual.setValue(GREAT_TREE, planned.getValue(GREAT_TREE));
    }

    /** Six local native-neighbor queries; never scans a canopy or loads a chunk. */
    public static int supportDistance(LevelReader level, BlockPos pos) {
        int distance = DECAY_DISTANCE;
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = pos.relative(direction);
            if (!level.hasChunkAt(neighbor)) continue;
            distance = Math.min(distance, LeavesBlock.getOptionalDistanceAt(level.getBlockState(neighbor)).orElse(DECAY_DISTANCE) + 1);
        }
        return distance;
    }

    @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state,
            net.minecraft.world.entity.player.Player player) {
        if (level instanceof ServerLevel server) {
            TemporalWorld.get(server).trees.beforeWoodRemoval(pos);
            TemporalWorld.get(server).trees.preparePrune(pos, player);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state,
            net.minecraft.world.entity.LivingEntity placer, net.minecraft.world.item.ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel server) TemporalWorld.get(server).trees.placedWood(pos, placer);
    }

    @Override public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos,
            net.minecraft.world.entity.player.Player player, boolean harvest, net.minecraft.world.level.material.FluidState fluid) {
        TreeLogic trees = level instanceof ServerLevel server ? TemporalWorld.get(server).trees : null;
        TreeLogic.PruneAttempt attempt = trees == null ? null : trees.takePrune(pos, player);
        boolean removed = super.onDestroyedByPlayer(state, level, pos, player, harvest, fluid);
        if (removed && trees != null && !level.getBlockState(pos).is(this)) trees.finishPrune(attempt, player);
        return removed;
    }

    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState previous, boolean moved) {
        super.onPlace(state, level, pos, previous, moved);
        if (!level.isClientSide && !state.is(previous.getBlock())) level.scheduleTick(pos, this, 1);
    }

    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        super.onRemove(state, level, pos, replacement, moved);
        if (!state.is(replacement.getBlock()) && level instanceof ServerLevel server)
            TemporalWorld.get(server).trees.removed(pos);
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(GREAT_TREE, RotatedPillarBlock.AXIS);
    }
}
