package dev.senluo.witchcraft.mixin.ritual.client;

import dev.senluo.witchcraft.ritual.GeasView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(MultiPlayerGameMode.class)
public abstract class RestraintMiningClientMixin {
    @ModifyConstant(method = {"startDestroyBlock", "continueDestroyBlock"}, constant = @Constant(intValue = 5), require = 3)
    private int witchcraft$interval(int original) {
        return GeasView.restrained(Minecraft.getInstance().player) ? original * 2 : original;
    }
}
