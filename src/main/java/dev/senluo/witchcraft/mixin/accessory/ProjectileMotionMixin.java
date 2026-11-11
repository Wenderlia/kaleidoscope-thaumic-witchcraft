package dev.senluo.witchcraft.mixin.accessory;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.senluo.witchcraft.accessory.TurtleField;
import dev.senluo.witchcraft.accessory.ProjectileBarrier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Entity.class)
public abstract class ProjectileMotionMixin {
    @WrapMethod(method = "setPos(DDD)V")
    private void witchcraft$clipFlight(double x, double y, double z, Operation<Void> original) {
        Vec3 constrained = TurtleField.position((Entity) (Object) this, x, y, z);
        if (constrained == null) original.call(x, y, z);
        else original.call(constrained.x, constrained.y, constrained.z);
    }

    @ModifyVariable(method = "setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), argsOnly = true)
    private Vec3 witchcraft$holdFlight(Vec3 movement) {
        Entity entity = (Entity) (Object) this;
        return dev.senluo.witchcraft.accessory.CastBarrier.velocity(entity,
                TurtleField.projectileVelocity(entity, movement));
    }

    @ModifyVariable(method = "setYRot", at = @At("HEAD"), argsOnly = true)
    private float witchcraft$flightYaw(float yaw) {
        return ProjectileBarrier.rotation((Entity) (Object) this, yaw, true);
    }

    @ModifyVariable(method = "setXRot", at = @At("HEAD"), argsOnly = true)
    private float witchcraft$flightPitch(float pitch) {
        return ProjectileBarrier.rotation((Entity) (Object) this, pitch, false);
    }
}
