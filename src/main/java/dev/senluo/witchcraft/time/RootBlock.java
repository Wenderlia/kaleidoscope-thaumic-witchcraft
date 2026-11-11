package dev.senluo.witchcraft.time;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;

/** Real, diggable soil occupied by a root. Connections do not grant storage or income. */
public final class RootBlock extends Block implements EntityBlock {
    // Saved-state compatibility and development bookkeeping only; visuals use one size.
    public static final IntegerProperty AGE = IntegerProperty.create("age", 0, 3);
    public static final BooleanProperty EMBEDDED = BooleanProperty.create("embedded");
    public static BooleanProperty connection(Direction direction) {
        return switch (direction) {
            case DOWN -> BlockStateProperties.DOWN;
            case UP -> BlockStateProperties.UP;
            case NORTH -> BlockStateProperties.NORTH;
            case SOUTH -> BlockStateProperties.SOUTH;
            case WEST -> BlockStateProperties.WEST;
            case EAST -> BlockStateProperties.EAST;
        };
    }
    public RootBlock(Properties properties) {
        super(properties);
        BlockState state = stateDefinition.any().setValue(AGE, 0).setValue(EMBEDDED, true);
        for (Direction direction : Direction.values()) state = state.setValue(connection(direction), false);
        registerDefaultState(state);
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AGE, EMBEDDED);
        for (Direction direction : Direction.values()) builder.add(connection(direction));
    }
    public static void refresh(LevelAccessor level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return;
        BlockState previous = level.getBlockState(pos);
        if (!RootConnections.isRoot(previous)) return;
        BlockState state = previous;
        for (Direction direction : Direction.values()) state = state.setValue(connection(direction), RootConnections.connects(level, pos, direction));
        if (state != previous) level.setBlock(pos, state, 3);
    }
    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState previous, boolean moved) {
        super.onPlace(state, level, pos, previous, moved);
        if (!state.is(previous.getBlock())) refresh(level, pos);
    }
    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor,
                                               LevelAccessor level, BlockPos pos, BlockPos other) {
        return state.setValue(connection(direction), RootConnections.connects(level, pos, direction));
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        if (state.getValue(EMBEDDED)) return Shapes.block();
        double r = 1.5;
        double core = 3;
        VoxelShape shape = Block.box(8-core, 8-core, 8-core, 8+core, 8+core, 8+core);
        for (Direction d : Direction.values()) if (state.getValue(connection(d))) {
            shape = Shapes.or(shape, Block.box(
                    d == Direction.WEST ? 0 : 8-r, d == Direction.DOWN ? 0 : 8-r, d == Direction.NORTH ? 0 : 8-r,
                    d == Direction.EAST ? 16 : 8+r, d == Direction.UP ? 16 : 8+r, d == Direction.SOUTH ? 16 : 8+r));
        }
        return shape;
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new RootEntity(pos, state); }
    @Override public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) { return RootItem.stack(); }
    @Override public BlockState getToolModifiedState(BlockState state, net.minecraft.world.item.context.UseOnContext context,
                                                    net.neoforged.neoforge.common.ItemAbility ability, boolean simulate) {
        if (ability == net.neoforged.neoforge.common.ItemAbilities.HOE_TILL && state.getValue(EMBEDDED)
                && context.getItemInHand().canPerformAction(ability) && context.getClickedFace() != Direction.DOWN
                && context.getLevel().getBlockState(context.getClickedPos().above()).isAir())
            return RootedFarmlandBlock.fromRoot(state);
        return super.getToolModifiedState(state, context, ability, simulate);
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        if (level.restoringBlockSnapshots) {
            if (!RootConnections.isRoot(replacement)) super.onRemove(state, level, pos, replacement, moved);
            return;
        }
        if (RootConnections.isRoot(replacement)) {
            if (level instanceof ServerLevel server) TemporalWorld.get(server).invalidateConnections();
            return;
        }
        if (!state.is(replacement.getBlock()) && level instanceof ServerLevel server) {
            TemporalWorld world = TemporalWorld.get(server);
            if (!world.convertingRoot(pos)) {
                if (level.getBlockEntity(pos) instanceof RootEntity) popResource(level, pos, RootItem.stack());
                if (state.getValue(EMBEDDED)) popResource(level, pos, new ItemStack(Items.DIRT));
            }
            world.remove(pos);
        }
        super.onRemove(state, level, pos, replacement, moved);
    }
}
