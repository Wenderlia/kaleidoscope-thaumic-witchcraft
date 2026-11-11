package dev.senluo.witchcraft.mixin.brew;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.brew.TimeStride;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
public abstract class FlightStrideMixin {
    @WrapOperation(method={"travel", "handleRelativeFrictionAndCalculateMovement"},
            at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/LivingEntity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"))
    private void witchcraft$flightStep(LivingEntity self,MoverType type,Vec3 movement,Operation<Void> original) {
        // Scale displacement once, not the retained momentum each tick. The original move
        // still resolves collisions. Server movement packets do not pass through this hook.
        double factor=type==MoverType.SELF && self instanceof Player player?TimeStride.flightMultiplier(player):1;
        original.call(self,type,factor>1?movement.scale(factor):movement);
    }
}
