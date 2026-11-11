package dev.senluo.witchcraft.mixin.brew;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.brew.InstantRecovery;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
public abstract class InstantRecoveryMixin {
    @WrapMethod(method="heal")
    private void witchcraft$scopeRecovery(float amount,Operation<Void> original) {
        LivingEntity entity=(LivingEntity)(Object)this;
        InstantRecovery.enterHeal(entity);
        try {original.call(amount);}
        finally {InstantRecovery.leaveHeal(entity);}
    }
    @WrapOperation(method="heal",at=@At(value="INVOKE",
            target="Lnet/minecraft/world/entity/LivingEntity;setHealth(F)V"))
    private void witchcraft$payApprovedRecovery(LivingEntity entity,float health,Operation<Void> original) {
        if(InstantRecovery.commit(entity,health))original.call(entity,health);
    }
}
