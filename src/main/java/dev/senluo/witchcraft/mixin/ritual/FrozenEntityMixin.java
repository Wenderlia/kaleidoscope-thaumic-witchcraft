package dev.senluo.witchcraft.mixin.ritual;

import dev.senluo.witchcraft.ritual.OwnershipRitual;
import dev.senluo.witchcraft.ritual.SustainedRitual;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Entity.class, priority = 2000)
public abstract class FrozenEntityMixin {
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void witchcraft$holdLook(double yaw, double pitch, CallbackInfo ci) {
        if (OwnershipRitual.frozen((Entity)(Object)this)) ci.cancel();
    }
    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void witchcraft$holdMotion(MoverType type, Vec3 movement, CallbackInfo ci) {
        if (OwnershipRitual.frozen((Entity)(Object)this)) ci.cancel();
    }

    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void witchcraft$holdPush(Entity other, CallbackInfo ci) {
        Entity self = (Entity)(Object)this;
        if (OwnershipRitual.frozen(self) || OwnershipRitual.frozen(other)) {
            SustainedRitual.contact(self, other, SustainedRitual.Contact.COLLISION);
            SustainedRitual.contact(other, self, SustainedRitual.Contact.COLLISION);
            ci.cancel();
        }
    }

    @Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;Z)Z", at = @At("HEAD"), cancellable = true)
    private void witchcraft$holdVehicle(Entity vehicle, boolean force, CallbackInfoReturnable<Boolean> ci) {
        Entity self = (Entity)(Object)this;
        if (OwnershipRitual.frozen(self) || OwnershipRitual.frozen(vehicle)) {
            SustainedRitual.contact(self, vehicle, SustainedRitual.Contact.VEHICLE);
            SustainedRitual.contact(vehicle, self, SustainedRitual.Contact.VEHICLE);
            ci.setReturnValue(false);
        }
    }
}
