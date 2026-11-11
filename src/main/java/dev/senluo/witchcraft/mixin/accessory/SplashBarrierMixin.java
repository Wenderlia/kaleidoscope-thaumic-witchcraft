package dev.senluo.witchcraft.mixin.accessory;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.accessory.PotionBarrier;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ThrownPotion.class)
public abstract class SplashBarrierMixin {
    @WrapMethod(method = "applySplash")
    private void witchcraft$splash(Iterable<MobEffectInstance> effects, Entity direct, Operation<Void> original) {
        PotionBarrier.apply((ThrownPotion)(Object)this, () -> original.call(effects, direct));
    }

    @WrapMethod(method = "applyWater")
    private void witchcraft$water(Operation<Void> original) {
        PotionBarrier.apply((ThrownPotion)(Object)this, () -> original.call());
    }

    @WrapOperation(method = "applySplash", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;isAffectedByPotions()Z"))
    private boolean witchcraft$admission(LivingEntity target, Operation<Boolean> original) {
        ThrownPotion potion = (ThrownPotion)(Object)this;
        return original.call(target) && (potion.distanceToSqr(target) >= 16 || PotionBarrier.permits(potion, target));
    }

    @WrapOperation(method = "applyWater", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private <T extends Entity> List<T> witchcraft$waterTargets(Level level, Class<T> type, AABB bounds,
            Predicate<? super T> predicate, Operation<List<T>> original) {
        return PotionBarrier.filter((ThrownPotion)(Object)this, original.call(level, type, bounds, predicate));
    }

    @WrapOperation(method = "applyWater", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private <T extends Entity> List<T> witchcraft$rehydration(Level level, Class<T> type, AABB bounds, Operation<List<T>> original) {
        return PotionBarrier.filter((ThrownPotion)(Object)this, original.call(level, type, bounds));
    }

    @Inject(method = "dowseFire", at = @At("HEAD"), cancellable = true)
    private void witchcraft$waterBlock(BlockPos position, CallbackInfo callback) {
        if (!PotionBarrier.permits((ThrownPotion)(Object)this, Vec3.atCenterOf(position))) callback.cancel();
    }
}
