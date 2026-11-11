package dev.senluo.witchcraft.mixin.brew;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.brew.AutomaticAttack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Suppress only the automatic invocation's input side effects, not unrelated damage callbacks. */
@Mixin(Player.class)
public abstract class AutomaticAttackMixin {
    @WrapOperation(method="attack",at=@At(value="INVOKE",
            target="Lnet/minecraft/world/entity/player/Player;resetAttackStrengthTicker()V"))
    private void witchcraft$independentClock(Player player,Operation<Void> original) {
        if(!AutomaticAttack.active(player))original.call(player);
    }
    @WrapOperation(method="attack",at=@At(value="INVOKE",
            target="Lnet/minecraft/world/entity/player/Player;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private void witchcraft$preserveMotion(Player player,Vec3 motion,Operation<Void> original) {
        if(!AutomaticAttack.active(player))original.call(player,motion);
    }
    @WrapOperation(method="attack",at=@At(value="INVOKE",
            target="Lnet/minecraft/world/entity/player/Player;causeFoodExhaustion(F)V"))
    private void witchcraft$noAutomaticNutrition(Player player,float amount,Operation<Void> original) {
        if(!AutomaticAttack.active(player))original.call(player,amount);
    }
    @ModifyExpressionValue(method="attack",at=@At(value="INVOKE",
            target="Lnet/minecraft/world/entity/player/Player;getYRot()F"))
    private float witchcraft$targetDirection(float yaw) {
        return AutomaticAttack.yaw((Player)(Object)this,yaw);
    }
}
