package dev.senluo.witchcraft.mixin.brew;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface SlowMovementAccess {
    @Accessor("stuckSpeedMultiplier") Vec3 witchcraft$stuckMultiplier();
    @Invoker("getBlockSpeedFactor") float witchcraft$blockSpeedFactor();
}
