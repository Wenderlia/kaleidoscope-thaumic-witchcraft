package dev.senluo.witchcraft.mixin.accessory;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.accessory.BarrierVisuals;
import dev.senluo.witchcraft.accessory.ExplosionBarrier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Explosion.class)
public abstract class ExplosionBarrierMixin {
    @Shadow @Final private Level level;

    @WrapMethod(method = "explode")
    private void witchcraft$propagation(Operation<Void> original) {
        ExplosionBarrier.explode(level, (Explosion)(Object)this, () -> original.call());
    }

    @WrapMethod(method = "getSeenPercent")
    private static float witchcraft$exposure(Vec3 origin, Entity target, Operation<Float> original) {
        return ExplosionBarrier.exposure(origin, target, () -> original.call(origin, target));
    }

    @WrapOperation(method = "getSeenPercent", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private static BlockHitResult witchcraft$sample(Level level, ClipContext context, Operation<BlockHitResult> original) {
        return ExplosionBarrier.clip(level, context, () -> original.call(level, context));
    }

    @WrapMethod(method = "finalizeExplosion")
    private void witchcraft$visualSource(boolean particles, Operation<Void> original) {
        BarrierVisuals.particles(level, ((Explosion)(Object)this).center(), () -> original.call(particles));
    }
}
