package dev.senluo.witchcraft.mixin.time;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.senluo.witchcraft.time.InventoryChanges;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Resolve the real container behind wrappers, including the remote half of a double chest. */
@Mixin(BaseContainerBlockEntity.class)
public abstract class InventoryContainerMixin {
    @ModifyReturnValue(method = "getItem", at = @At("RETURN"))
    private ItemStack witchcraft$read(ItemStack value) { InventoryChanges.read(this); return value; }
}
