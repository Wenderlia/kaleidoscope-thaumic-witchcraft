package dev.senluo.witchcraft.time;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class FruitBlock extends Block implements EntityBlock {
    public static final IntegerProperty GRADE = IntegerProperty.create("grade",0,TreeBalance.MAX_FRUIT_GRADE);
    // Collision follows the authored mesh; selection presents one whole fruit.
    // Both arrays are cached once, never voxelized or bounded on the render path.
    private static final VoxelShape[] COLLISION_SHAPES = FruitShapes.create();
    private static final VoxelShape[] OUTLINE_SHAPES = createOutlines();

    private static VoxelShape[] createOutlines() {
        VoxelShape[] outlines = new VoxelShape[COLLISION_SHAPES.length];
        for (int i = 0; i < outlines.length; i++) {
            outlines[i] = Shapes.create(COLLISION_SHAPES[i].bounds());
        }
        return outlines;
    }

    public FruitBlock(Properties properties) { super(properties); registerDefaultState(stateDefinition.any().setValue(GRADE,0)); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) { builder.add(GRADE); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new FruitEntity(pos,state); }
    @Override public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        int grade = state.getValue(GRADE);
        return grade > 0 ? FruitItem.stack(grade) : ItemStack.EMPTY;
    }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return !level.isClientSide && type == TemporalContent.FRUIT_ENTITY.get()
                ? (world,pos,s,entity) -> FruitEntity.tick((FruitEntity)entity) : null;
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return OUTLINE_SHAPES[state.getValue(GRADE)];
    }
    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return COLLISION_SHAPES[state.getValue(GRADE)];
    }
    @Override protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }
    public static boolean canUse(Level level, BlockPos pos, Player player, FruitEntity fruit) {
        if (!fruit.supported()) {
            if (!level.isClientSide) LivingBlock.tell(player, "data_unavailable");
            return false;
        }
        return player.mayBuild() && !player.isSpectator() && level.mayInteract(player,pos);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof FruitEntity fruit) || !canUse(level,pos,player,fruit)) return InteractionResult.FAIL;
        player.displayClientMessage(status(fruit),true);
        return InteractionResult.CONSUME;
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.isEmpty()) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!stack.is(Items.GOLD_INGOT) && !stack.is(Items.CLOCK) && !FruitHarvest.isShears(stack)) return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide) return ItemInteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof FruitEntity fruit) || !canUse(level,pos,player,fruit)) return ItemInteractionResult.FAIL;
        if (stack.is(Items.CLOCK)) {
            player.displayClientMessage(status(fruit), true);
            return ItemInteractionResult.SUCCESS;
        }
        if (FruitHarvest.isShears(stack)) {
            fruit.prepareHarvest(stack);
            boolean removed;
            try { removed = level.removeBlock(pos,false); }
            finally { fruit.clearHarvestTool(); }
            if (!removed) return ItemInteractionResult.FAIL;
            stack.hurtAndBreak(1,player,hand == InteractionHand.MAIN_HAND
                    ? net.minecraft.world.entity.EquipmentSlot.MAINHAND : net.minecraft.world.entity.EquipmentSlot.OFFHAND);
            return ItemInteractionResult.SUCCESS;
        }
        LivingEntity core = fruit.core();
        if (core == null || !LivingBlock.canSpend(level, player, core)) return ItemInteractionResult.FAIL;
        if (fruit.hasten()) stack.consume(1,player);
        player.displayClientMessage(status(fruit),true);
        return ItemInteractionResult.SUCCESS;
    }
    @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level instanceof ServerLevel server) {
            TemporalWorld.get(server).trees.beforeWoodRemoval(pos);
            TemporalWorld.get(server).trees.preparePrune(pos, player);
        }
        // Vanilla damages the mining tool before removal: snapshot Fortune before its final use can break it.
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof FruitEntity fruit)
            fruit.prepareHarvest(player.getMainHandItem());
        return super.playerWillDestroy(level,pos,state,player);
    }
    @Override public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player,
            boolean willHarvest, FluidState fluid) {
        FruitEntity fruit = level.getBlockEntity(pos) instanceof FruitEntity entity ? entity : null;
        var trees = level instanceof ServerLevel server ? TemporalWorld.get(server).trees : null;
        var attempt = trees == null ? null : trees.takePrune(pos,player);
        try {
            boolean removed = super.onDestroyedByPlayer(state,level,pos,player,willHarvest,fluid);
            if (removed && trees != null && !level.getBlockState(pos).is(this)) trees.finishPrune(attempt,player);
            return removed;
        }
        finally { if (fruit != null) fruit.clearHarvestTool(); }
    }
    public static Component status(FruitEntity fruit) {
        var text = FruitItem.gradeName(fruit.grade).copy();
        if (!fruit.supported()) return TemporalText.notice("data_unavailable");
        int limit = fruit.limit();
        if (limit == 0 || fruit.core() != null && fruit.getLevel() instanceof ServerLevel server
                && TemporalWorld.get(server).colony(fruit.core()).productionMode() == 0) return text.append(TemporalText.message("fruit_paused"));
        if (fruit.grade >= limit) return text.append(TemporalText.message("fruit_at_limit"));
        return text.append(TemporalText.message("fruit_growing",TemporalText.capacity(fruit.remainingFee(),false),TemporalText.duration(fruit.remainingTicks())));
    }
    @Override protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor, BlockPos neighborPos, boolean moved) {
        if (!level.isClientSide && pos.above().equals(neighborPos) && level.getBlockEntity(pos) instanceof FruitEntity fruit)
            fruit.checkSupport();
        super.neighborChanged(state,level,pos,neighbor,neighborPos,moved);
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        if (!state.is(replacement.getBlock()) && level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof FruitEntity fruit
                && !fruit.harvested && (fruit.grade > 0 || !fruit.supported())) {
            fruit.harvested = true;
            FruitHarvest.drop(server,pos,FruitHarvest.product(fruit,fruit.harvestFortune()));
        }
        super.onRemove(state,level,pos,replacement,moved);
    }
}
