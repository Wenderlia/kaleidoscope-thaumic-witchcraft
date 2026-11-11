package dev.senluo.witchcraft.mixin.brew;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.class)
public interface MovementIntentAccess {
    @Accessor("jumping") boolean witchcraft$jumping();
}
