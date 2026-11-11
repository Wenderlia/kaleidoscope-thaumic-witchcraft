package dev.senluo.witchcraft.mixin.brew;

import dev.senluo.witchcraft.brew.TimeStride;
import dev.senluo.witchcraft.brew.AutomaticAttack;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(Player.class)
public abstract class StrideAttackMixin {
    @ModifyArg(method="attack",at=@At(value="INVOKE",
            target="Lnet/minecraft/world/entity/player/Player;setSprinting(Z)V"),index=0)
    private boolean witchcraft$keepStride(boolean sprinting) {
        if(AutomaticAttack.active((Player)(Object)this))return ((Player)(Object)this).isSprinting();
        return sprinting || TimeStride.keepSprintAfterAttack((Player)(Object)this);
    }
}
