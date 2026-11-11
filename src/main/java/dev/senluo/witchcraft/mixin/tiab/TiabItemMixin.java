package dev.senluo.witchcraft.mixin.tiab;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.compat.tiab.BarrelTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.UseOnContext;
import org.mangorage.tiab.common.items.TiabItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = TiabItem.class, remap = false)
public abstract class TiabItemMixin {
    @WrapOperation(
            method = "useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/context/UseOnContext;getClickedPos()Lnet/minecraft/core/BlockPos;"),
            require = 1, allow = 1)
    private BlockPos senluo$targetBarrelController(UseOnContext context, Operation<BlockPos> original) {
        return BarrelTarget.resolve(context, original.call(context));
    }
}
