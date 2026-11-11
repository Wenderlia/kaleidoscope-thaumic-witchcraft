package dev.senluo.witchcraft.mixin.thaumcraft;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.senluo.witchcraft.compat.thaumcraft.ShockFieldSight;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockGetter.class)
public interface ShockSightMixin {
    @ModifyReturnValue(method = "clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;", at = @At("RETURN"))
    private BlockHitResult witchcraft$shockSight(BlockHitResult original, ClipContext ray) {
        return ShockFieldSight.clip((BlockGetter) this, ray, original);
    }
}
