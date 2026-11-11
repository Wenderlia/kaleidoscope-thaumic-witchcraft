package dev.senluo.witchcraft.mixin.ritual;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.senluo.witchcraft.ritual.WorldRestraint;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Creeper.class)
public abstract class IgnitedCreeperMixin {
    @WrapOperation(method = "mobInteract", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/Creeper;ignite()V"))
    private void witchcraft$ignite(Creeper creeper, Operation<Void> original, @Local(argsOnly = true) Player player) {
        if (WorldRestraint.captured(creeper) == null) WorldRestraint.capture(creeper, WorldRestraint.quote(player, true));
        original.call(creeper);
    }
}
