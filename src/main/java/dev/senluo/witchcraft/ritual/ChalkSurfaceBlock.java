package dev.senluo.witchcraft.ritual;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A non-colliding decal baked into the chunk mesh, with a thin selectable outline. */
public final class ChalkSurfaceBlock extends Block {
    public static final MapCodec<ChalkSurfaceBlock> CODEC = simpleCodec(ChalkSurfaceBlock::new);
    public static final IntegerProperty DESIGN = IntegerProperty.create("design", 0, ChalkPattern.DESIGNS-1);
    public static final IntegerProperty ROTATION = IntegerProperty.create("rotation", 0, ChalkPattern.TURNS-1);
    private static final VoxelShape OUTLINE = Block.box(0, 0, 0, 16, .5, 16);
    public ChalkSurfaceBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(DESIGN, 0).setValue(ROTATION, 0));
    }
    @Override protected MapCodec<? extends Block> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(DESIGN, ROTATION);
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return OUTLINE;
    }
    @Override protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos support = pos.below();
        return RitualChalk.surface(level, support, level.getBlockState(support)) >= 0;
    }
    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor,
            LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        // Actual tree regeneration can preserve a carving; loose chalk still needs a substrate.
        return direction == Direction.DOWN && !state.canSurvive(level, pos) ? Blocks.AIR.defaultBlockState() : state;
    }
}
