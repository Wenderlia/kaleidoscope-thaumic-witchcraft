package dev.senluo.witchcraft.mixin.accessory.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.accessory.TurtleField;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientLevel.class)
public abstract class ProjectileClientTickMixin {
    @WrapOperation(method = "tickNonPassenger", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V"))
    private void witchcraft$flightTick(Entity entity, Operation<Void> original) {
        TurtleField.tick(entity, () -> original.call(entity));
    }
    @WrapOperation(method = "tickPassenger", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;rideTick()V"))
    private void witchcraft$flightRideTick(Entity entity, Operation<Void> original) {
        TurtleField.tick(entity, () -> original.call(entity));
    }
}
