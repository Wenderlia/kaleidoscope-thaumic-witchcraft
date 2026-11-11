package dev.senluo.witchcraft.mixin.time;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.time.EntityTime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(ServerLevel.class)
public abstract class EntityTimeMixin {
    @WrapOperation(method="tickNonPassenger",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;tick()V"))
    private void senluo$localTick(Entity entity,Operation<Void> original){
        dev.senluo.witchcraft.accessory.TurtleField.tick(entity, () -> EntityTime.tick(entity,()->original.call(entity)));
    }
    @WrapOperation(method="tickPassenger",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;rideTick()V"))
    private void senluo$rideTick(Entity entity,Operation<Void> original){
        dev.senluo.witchcraft.accessory.TurtleField.tick(entity, () -> EntityTime.tick(entity,()->original.call(entity)));
    }
}
