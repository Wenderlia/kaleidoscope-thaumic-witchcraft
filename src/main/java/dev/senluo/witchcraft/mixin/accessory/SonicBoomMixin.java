package dev.senluo.witchcraft.mixin.accessory;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.accessory.SonicBarrier;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.warden.SonicBoom;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SonicBoom.class)
public abstract class SonicBoomMixin {
    // Pinned 1.21.1 strike body. Charge, target filters, sound and cooldown remain native.
    @WrapMethod(method = "lambda$tick$2")
    private static void witchcraft$quoteStrike(Warden warden, ServerLevel level, LivingEntity target, Operation<Void> original) {
        SonicBarrier.strike(warden, level, target, () -> original.call(warden, level, target));
    }

    @WrapOperation(method = "lambda$tick$2", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"))
    private static int witchcraft$truncateWave(ServerLevel level, ParticleOptions type, double x, double y, double z,
            int count, double dx, double dy, double dz, double speed, Operation<Integer> original) {
        if (type == ParticleTypes.SONIC_BOOM && !SonicBarrier.permitsParticle(level, new Vec3(x, y, z))) return 0;
        return original.call(level, type, x, y, z, count, dx, dy, dz, speed);
    }

    @WrapOperation(method = "lambda$tick$2", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private static boolean witchcraft$unreachableTarget(LivingEntity target, DamageSource source, float damage,
            Operation<Boolean> original) {
        return !SonicBarrier.blocksDamage(target) && original.call(target, source, damage);
    }
}
