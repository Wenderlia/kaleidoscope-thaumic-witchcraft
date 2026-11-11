package dev.senluo.witchcraft.ritual;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A fixed baked decal with an independent center identity, not an ordinary array mark. */
public final class GoldenHeartBlock extends BaseEntityBlock {
    public static final MapCodec<GoldenHeartBlock> CODEC = simpleCodec(GoldenHeartBlock::new);
    public static final IntegerProperty ROTATION = IntegerProperty.create("rotation", 0, 3);
    private static final VoxelShape OUTLINE = Block.box(0, 0, 0, 16, .5, 16);
    public GoldenHeartBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(ROTATION, 0));
    }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(ROTATION); }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return OUTLINE; }
    @Override protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        var support = pos.below();
        return RitualChalk.surface(level, support, level.getBlockState(support)) >= 0;
    }
    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor,
            LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return direction == Direction.DOWN && !state.canSurvive(level, pos) ? Blocks.AIR.defaultBlockState() : state;
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new GoldenHeartEntity(pos, state); }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()
                || !RitualChalk.permitted(player, level, pos, ItemStack.EMPTY)
                || !RitualChalk.permitted(player, level, pos.below(), ItemStack.EMPTY)) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        return level.getBlockEntity(pos) instanceof GoldenHeartEntity heart ? heart.activate((ServerLevel) level, player) : InteractionResult.PASS;
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock()) && level instanceof ServerLevel server
                && level.getBlockEntity(pos) instanceof GoldenHeartEntity heart) heart.removed(server);
        super.onRemove(state, level, pos, next, moving);
    }
}
