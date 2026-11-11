package dev.senluo.witchcraft.mixin.ritual;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LivingEntity.class)
public interface HurtSoundAccess {
    @Invoker("playHurtSound")
    void witchcraft$playHurtSound(DamageSource source);
}
