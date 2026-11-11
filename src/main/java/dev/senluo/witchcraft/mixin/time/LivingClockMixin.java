package dev.senluo.witchcraft.mixin.time;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.time.EntityTime;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(LivingEntity.class)
public abstract class LivingClockMixin {
    @Inject(method="tickEffects",at=@At("HEAD"),cancellable=true)
    private void senluo$effects(CallbackInfo ci){if(EntityTime.held((LivingEntity)(Object)this))ci.cancel();}
    @WrapOperation(method="baseTick",at=@At(value="INVOKE",target="Lnet/neoforged/neoforge/common/CommonHooks;onLivingBreathe(Lnet/minecraft/world/entity/LivingEntity;II)V"))
    private void senluo$breathing(LivingEntity entity,int consume,int refill,Operation<Void> original){
        if(!EntityTime.held(entity))original.call(entity,consume,refill);
    }
}
