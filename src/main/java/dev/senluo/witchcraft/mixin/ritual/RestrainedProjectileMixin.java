package dev.senluo.witchcraft.mixin.ritual;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.ritual.WorldRestraint;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Projectile.class)
public abstract class RestrainedProjectileMixin {
    @Shadow private UUID ownerUUID;

    @Inject(method = "setOwner", at = @At("HEAD"))
    private void witchcraft$sourceChanged(Entity owner, CallbackInfo ci) {
        // Native setOwner(null) is a no-op, and setting the same owner is not a new shot.
        if (owner != null && !Objects.equals(ownerUUID, owner.getUUID()))
            WorldRestraint.ownerChanged((Projectile)(Object)this);
    }
    @Inject(method = "onHitBlock", at = @At("HEAD"), cancellable = true)
    private void witchcraft$blockReaction(BlockHitResult hit, CallbackInfo ci) {
        if (WorldRestraint.blocksWorldEdit(((Projectile)(Object)this).level())) ci.cancel();
    }
    @WrapOperation(method = "hitTargetOrDeflectSelf", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/projectile/Projectile;onHit(Lnet/minecraft/world/phys/HitResult;)V"))
    private void witchcraft$impact(Projectile projectile, HitResult hit, Operation<Void> original) {
        if (projectile.level().isClientSide) { original.call(projectile, hit); return; }
        WorldRestraint.during(projectile, WorldRestraint.projectile(projectile), () -> { original.call(projectile, hit); return null; });
    }
}
