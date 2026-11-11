package dev.senluo.witchcraft.mixin.time;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import dev.senluo.witchcraft.time.EntityTime;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(Entity.class)
public abstract class EntityBaseTimeMixin {
    @WrapMethod(method="move")
    private void senluo$externalMotion(MoverType type,Vec3 movement,Operation<Void> original){
        Entity entity = (Entity)(Object)this;
        dev.senluo.witchcraft.accessory.TurtleField.move(entity, movement,
                mapped -> original.call(type, EntityTime.externalMove(entity, mapped)));
    }
    @WrapOperation(method="move",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 witchcraft$finalMotion(Entity entity, Vec3 movement, Operation<Vec3> original) {
        dev.senluo.witchcraft.brew.FrostPath.beforeMotion(entity,movement);
        Vec3 resolved=dev.senluo.witchcraft.accessory.TurtleField.hardMotion(entity, original.call(entity, movement));
        dev.senluo.witchcraft.brew.FrostPath.afterMotion(entity,resolved);
        return resolved;
    }
    @WrapOperation(method="baseTick",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;setRemainingFireTicks(I)V"))
    private void senluo$fireTime(Entity entity,int ticks,Operation<Void> original){
        if (!EntityTime.held(entity)) {
            int before = entity.getRemainingFireTicks();
            original.call(entity,ticks);
            dev.senluo.witchcraft.ritual.SteamFire.afterFireTime(entity, before, ticks);
        }
    }
    @WrapOperation(method="baseTick",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean senluo$fireDamage(Entity entity,DamageSource source,float amount,Operation<Boolean> original){return !EntityTime.held(entity)&&original.call(entity,source,amount);}
    @WrapOperation(method="baseTick",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;lavaHurt()V"))
    private void senluo$lavaDamage(Entity entity,Operation<Void> original){if(!EntityTime.held(entity))original.call(entity);}
}
