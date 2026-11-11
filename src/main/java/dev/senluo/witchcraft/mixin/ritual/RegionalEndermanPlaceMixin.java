package dev.senluo.witchcraft.mixin.ritual;

import dev.senluo.witchcraft.region.RegionProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.world.entity.monster.EnderMan$EndermanLeaveBlockGoal")
public abstract class RegionalEndermanPlaceMixin {
    @Shadow @Final private EnderMan enderman;
    @Inject(method = "canPlaceBlock", at = @At("HEAD"), cancellable = true)
    private void witchcraft$region(Level level, BlockPos pos, BlockState held, BlockState target, BlockState below,
                                  BlockPos support, CallbackInfoReturnable<Boolean> cir) {
        if (RegionProtection.mobEdit(level, pos, enderman)) cir.setReturnValue(false);
    }
}
