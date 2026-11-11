package dev.senluo.witchcraft.mixin.brew;

import dev.senluo.witchcraft.brew.SolarFire;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Suppress only internal solar pulse feedback. Server damage events remain unchanged. */
@Mixin(LivingEntity.class)
abstract class SolarHurtFeedbackMixin {
    @Inject(method="handleDamageEvent",at=@At("HEAD"),cancellable=true)
    private void solarPulse(DamageSource source,CallbackInfo ci){if(source.is(SolarFire.SELF))ci.cancel();}
    @Inject(method="playHurtSound",at=@At("HEAD"),cancellable=true)
    private void solarSound(DamageSource source,CallbackInfo ci){if(source.is(SolarFire.SELF))ci.cancel();}
}
