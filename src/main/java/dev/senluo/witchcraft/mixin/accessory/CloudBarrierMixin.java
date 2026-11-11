package dev.senluo.witchcraft.mixin.accessory;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.accessory.PotionBarrier;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AreaEffectCloud.class)
public abstract class CloudBarrierMixin {
    @WrapMethod(method = "tick")
    private void witchcraft$application(Operation<Void> original) {
        AreaEffectCloud cloud = (AreaEffectCloud)(Object)this;
        if (!cloud.level().isClientSide && cloud.tickCount % 5 != 0) original.call();
        else PotionBarrier.apply(cloud, () -> original.call());
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;isAffectedByPotions()Z"))
    private boolean witchcraft$admission(LivingEntity target, Operation<Boolean> original) {
        if (!original.call(target)) return false;
        AreaEffectCloud cloud = (AreaEffectCloud)(Object)this;
        double dx = target.getX() - cloud.getX(), dz = target.getZ() - cloud.getZ();
        return dx * dx + dz * dz > cloud.getRadius() * cloud.getRadius() || PotionBarrier.permits(cloud, target);
    }
}
