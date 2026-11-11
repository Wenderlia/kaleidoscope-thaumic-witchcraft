package dev.senluo.witchcraft.mixin.ritual;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.senluo.witchcraft.ritual.DurabilityLoss;
import dev.senluo.witchcraft.ritual.BoreToolWear;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemStack.class)
public abstract class DurabilityLossMixin {
    @WrapMethod(method = "inventoryTick")
    private void witchcraft$inventoryWork(Level level, Entity entity, int slot, boolean selected, Operation<Void> original) {
        if (!BoreToolWear.active()) { original.call(level, entity, slot, selected); return; }
        BoreToolWear.inventoryTick(() -> original.call(level, entity, slot, selected));
    }

    // This innermost overload covers weapons, armor, tools and other standard durability users.
    // Do not also intercept the ServerPlayer/EquipmentSlot forwarding overloads.
    @WrapOperation(method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Ljava/util/function/Consumer;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/enchantment/EnchantmentHelper;processDurabilityChange(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;I)I"))
    private int witchcraft$wear(ServerLevel level, ItemStack stack, int amount, Operation<Integer> original,
            @Local(argsOnly = true) LivingEntity bearer) {
        int nativeLoss = original.call(level, stack, amount);
        nativeLoss = dev.senluo.witchcraft.ritual.BoreToolWear.apply(bearer, stack, nativeLoss, level.random);
        return DurabilityLoss.forBearer(bearer).integral(nativeLoss, level.random);
    }
}
