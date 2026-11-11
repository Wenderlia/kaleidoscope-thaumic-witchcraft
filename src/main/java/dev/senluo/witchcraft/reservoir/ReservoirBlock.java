package dev.senluo.witchcraft.reservoir;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;

public final class ReservoirBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING=HorizontalDirectionalBlock.FACING;
    public static final MapCodec<ReservoirBlock> CODEC=simpleCodec(ReservoirBlock::new);
    public ReservoirBlock(Properties properties){super(properties);registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH));}
    protected MapCodec<? extends BaseEntityBlock> codec(){return CODEC;}
    protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){builder.add(FACING);}
    public BlockState getStateForPlacement(BlockPlaceContext ctx){return defaultBlockState().setValue(FACING,ctx.getHorizontalDirection().getOpposite());}
    public BlockEntity newBlockEntity(BlockPos pos,BlockState state){return new ReservoirBlockEntity(pos,state);}
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level,BlockState state,BlockEntityType<T> type){
        return level.isClientSide?null:createTickerHelper(type,ReservoirContent.ENTITY.get(),ReservoirBlockEntity::serverTick);
    }
    protected RenderShape getRenderShape(BlockState state){return RenderShape.ENTITYBLOCK_ANIMATED;}
    protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return Block.box(2,0,2,14,15,14);}
    protected ItemInteractionResult useItemOn(ItemStack stack,BlockState state,Level level,BlockPos pos,Player player,InteractionHand hand,BlockHitResult hit){
        if(level.getBlockEntity(pos) instanceof ReservoirBlockEntity be && !stack.isEmpty()){
            if(level.isClientSide)return ReservoirContent.acceptsInput(stack) && ReservoirData.supported(be.bag())
                    ? ItemInteractionResult.SUCCESS : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            if(ReservoirContent.interact((ServerPlayer)player,be.bag(),hand,be::changed))return ItemInteractionResult.SUCCESS;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
    protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit){
        if(player.isSpectator() || dev.senluo.witchcraft.ritual.OwnershipRitual.frozen(player))return InteractionResult.PASS;
        if(!(level.getBlockEntity(pos) instanceof ReservoirBlockEntity be))return InteractionResult.PASS;
        if(!level.isClientSide){
            if(player.isShiftKeyDown() && player.getMainHandItem().isEmpty()){
                if(!be.pickup((ServerPlayer)player))return InteractionResult.FAIL;
            }else be.open((ServerPlayer)player);
        }
        return InteractionResult.SUCCESS;
    }
    protected void onRemove(BlockState state,Level level,BlockPos pos,BlockState next,boolean moving){
        if(!level.restoringBlockSnapshots && !state.is(next.getBlock()) && !level.isClientSide && level.getBlockEntity(pos) instanceof ReservoirBlockEntity be){
            ItemStack stack=be.removalDrop();if(!stack.isEmpty())Block.popResource(level,pos,stack);
        }
        super.onRemove(state,level,pos,next,moving);
    }
    @Override public ItemStack getCloneItemStack(BlockState state,net.minecraft.world.phys.HitResult target,LevelReader level,BlockPos pos,Player player){
        return level.getBlockEntity(pos) instanceof ReservoirBlockEntity be?be.bag().copy():new ItemStack(state.getBlock());
    }
}
