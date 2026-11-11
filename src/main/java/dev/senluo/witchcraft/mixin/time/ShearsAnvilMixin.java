package dev.senluo.witchcraft.mixin.time;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.time.FruitHarvest;
import net.minecraft.core.Holder;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AnvilMenu.class)
public abstract class ShearsAnvilMixin {
    @WrapOperation(method="createResult", at=@At(value="INVOKE",
            target="Lnet/minecraft/world/item/ItemStack;supportsEnchantment(Lnet/minecraft/core/Holder;)Z"))
    private boolean witchcraft$fortuneShears(ItemStack stack, Holder<Enchantment> enchantment, Operation<Boolean> original) {
        // Only extend anvil eligibility; vanilla still decides levels, XP cost and incompatible enchantments.
        return original.call(stack,enchantment) || enchantment.is(Enchantments.FORTUNE) && FruitHarvest.isShears(stack);
    }
}
