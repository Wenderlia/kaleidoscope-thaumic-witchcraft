package dev.senluo.witchcraft.reservoir;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.server.level.ServerPlayer;

public final class ReservoirItem extends BlockItem {
    private final ReservoirProfile profile;
    public ReservoirItem(Block block,Properties properties,ReservoirProfile profile){super(block,properties);this.profile=profile;}
    public ReservoirProfile profile(){return profile;}
    @Override public EquipmentSlot getEquipmentSlot(ItemStack stack){return EquipmentSlot.CHEST;}
    @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand){
        ItemStack bag=player.getItemInHand(hand);
        return new InteractionResultHolder<>(openHeld(player,bag),bag);
    }
    @Override public InteractionResult useOn(UseOnContext context){
        Player player=context.getPlayer();
        if(player==null || player.isSecondaryUseActive())return super.useOn(context);
        return openHeld(player,context.getItemInHand());
    }
    static InteractionResult openHeld(Player player,ItemStack bag){
        if(player instanceof ServerPlayer server)ReservoirSession.openHeld(server,bag);
        // Opening a carried container is not a placement / arm-swing action.
        return InteractionResult.CONSUME;
    }
    @Override protected boolean updateCustomBlockEntityTag(BlockPos pos,Level level,Player player,ItemStack stack,BlockState state){
        if(!level.isClientSide && level.getBlockEntity(pos) instanceof ReservoirBlockEntity be)be.setBag(stack.copyWithCount(1));
        return true;
    }
    @Override protected boolean placeBlock(BlockPlaceContext context,BlockState state){
        return ReservoirData.supported(context.getItemInHand()) && super.placeBlock(context,state);
    }
}
