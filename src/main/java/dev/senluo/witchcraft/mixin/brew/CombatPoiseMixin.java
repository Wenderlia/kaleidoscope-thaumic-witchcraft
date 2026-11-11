package dev.senluo.witchcraft.mixin.brew;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.senluo.witchcraft.brew.CombatPoise;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
public abstract class CombatPoiseMixin {
    // Vanilla has already dispatched the event and returned for canceled knockback here.
    @ModifyExpressionValue(method="knockback", at=@At(value="INVOKE",
            target="Lnet/neoforged/neoforge/event/entity/living/LivingKnockBackEvent;getStrength()F", remap=false))
    private float witchcraft$steady(float strength) {
        return CombatPoise.knockback((LivingEntity)(Object)this,strength);
    }
}
