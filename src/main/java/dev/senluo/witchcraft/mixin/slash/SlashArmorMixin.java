package dev.senluo.witchcraft.mixin.slash;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.slash.SlashCombat;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** NeoForge's ARMOR reduction modifier acts after the nonlinear armor formula, not on armor points. */
@Mixin(LivingEntity.class)
public abstract class SlashArmorMixin {
    @WrapOperation(method = "getDamageAfterArmorAbsorb", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/damagesource/CombatRules;getDamageAfterAbsorb(Lnet/minecraft/world/entity/LivingEntity;FLnet/minecraft/world/damagesource/DamageSource;FF)F"),
            require = 1, allow = 1)
    private float witchcraft$slashArmor(LivingEntity target, float damage, DamageSource source,
                                       float armor, float toughness, Operation<Float> original) {
        return original.call(target, damage, source, SlashCombat.effectiveArmor(source, armor), toughness);
    }
}
