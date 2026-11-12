package dev.senluo.witchcraft.vishroom;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.*;

/** Native baked block models; each cap underside selects its real 16px atlas region. */
public final class VishroomPartBlock extends Block {
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, VishroomShape.PARTS - 1);
    private static final VoxelShape[] SHAPES = java.util.Arrays.stream(VishroomShape.BOXES).map(boxes -> {
        VoxelShape shape = Shapes.empty();
        for (double[] b : boxes) shape = Shapes.or(shape, Block.box(b[0],b[1],b[2],b[3],b[4],b[5]));
        return shape.optimize();
    }).toArray(VoxelShape[]::new);
    public VishroomPartBlock(Properties properties) {
        super(properties); registerDefaultState(stateDefinition.any().setValue(PART, VishroomShape.STEM));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(PART); }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return SHAPES[state.getValue(PART)]; }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        // BlockItem validates survival before its later component-copy step.
        return context.getItemInHand().getOrDefault(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY)
                .apply(defaultBlockState());
    }
    private static Direction supportDirection(BlockState state) {
        return switch (state.getValue(PART)) {
            case VishroomShape.TUFT -> Direction.UP;
            case VishroomShape.YOUNG -> Direction.DOWN;
            default -> null;
        };
    }
    @Override protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction direction = supportDirection(state);
        if (direction == null) return true;
        BlockPos support = pos.relative(direction);
        return level.getBlockState(support).isFaceSturdy(level, support, direction.getOpposite());
    }
    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState previous, boolean moved) {
        super.onPlace(state, level, pos, previous, moved);
        if (!level.isClientSide && !state.canSurvive(level, pos)) level.scheduleTick(pos, this, 1);
    }
    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor,
            LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (direction == supportDirection(state) && !state.canSurvive(level, pos)) level.scheduleTick(pos, this, 1);
        return super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }
    @Override protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        // A restored support or a replacement full part cancels the pending loss.
        if (!state.canSurvive(level, pos)) level.destroyBlock(pos, true);
    }
    @Override public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        var item = new ItemStack(VishroomContent.PART_ITEM.get());
        item.set(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY.with(PART, state));
        return item;
    }
    public static float itemVariant(ItemStack stack) {
        Integer value = stack.getOrDefault(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY).get(PART);
        return (value == null ? VishroomShape.STEM : value) / (float)(VishroomShape.PARTS - 1);
    }
    @Override public String getDescriptionId() { return "block.thaumcraft.vishroom"; }
}
