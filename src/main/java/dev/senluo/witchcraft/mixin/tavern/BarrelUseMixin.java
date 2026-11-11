package dev.senluo.witchcraft.mixin.tavern;

import com.github.ysbbbbbb.kaleidoscopetavern.block.brew.BarrelBlock;
import dev.senluo.witchcraft.compat.tavern.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=BarrelBlock.class, remap=false)
public abstract class BarrelUseMixin extends BaseEntityBlock {
    protected BarrelUseMixin(Properties properties) { super(properties); }
    @Inject(method="useItemOn(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/ItemInteractionResult;", at=@At("HEAD"), cancellable=true)
    private void witchcraft$input(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                  InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<ItemInteractionResult> ci) {
        if (hand != InteractionHand.MAIN_HAND || state.getValue(BarrelBlock.LAYER) != AttachFace.CEILING || state.getValue(BarrelBlock.INDEX) != 4) return;
        var barrel = BarrelBlock.getBarrelEntity(level, pos, state);
        if (barrel == null || !barrel.isOpen() || barrel.isBrewing()) return;
        BarrelBatch batch = ((WineBarrel)barrel).witchcraft$batch();
        if (BarrelBatch.special(stack) || batch.active() && stack.is(Items.WHEAT)) {
            barrel.addIngredient(player, stack); ci.setReturnValue(ItemInteractionResult.SUCCESS);
        } else if (batch.active() && stack.is(Items.GLASS_BOTTLE)) {
            batch.takeHoney(barrel, player, stack); ci.setReturnValue(ItemInteractionResult.SUCCESS);
        } else if (batch.active() && stack.isEmpty()) {
            barrel.removeIngredient(player); ci.setReturnValue(ItemInteractionResult.SUCCESS);
        }
    }
    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock()) && !level.isClientSide && level.getBlockEntity(pos) instanceof WineBarrel barrel)
            barrel.witchcraft$dropContents();
        super.onRemove(state, level, pos, next, moving);
    }
}
