package dev.senluo.witchcraft.mixin.accessory;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Mob.class)
public interface MobReachAccess {
    @Invoker("getAttackBoundingBox") AABB witchcraft$attackBounds();
}
