package dev.senluo.witchcraft.mixin.ritual;

import com.llamalad7.mixinextras.sugar.Local;
import dev.senluo.witchcraft.region.RegionProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.EnderMan;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.world.entity.monster.EnderMan$EndermanTakeBlockGoal")
public abstract class RegionalEndermanTakeMixin {
    @Shadow @Final private EnderMan enderman;
    // Cancel the whole transfer before removal AND carried-block creation; suppressing only the write duplicates blocks.
    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"), cancellable = true)
    private void witchcraft$region(CallbackInfo ci, @Local BlockPos pos) {
        if (RegionProtection.mobEdit(enderman.level(), pos, enderman)) ci.cancel();
    }
}
