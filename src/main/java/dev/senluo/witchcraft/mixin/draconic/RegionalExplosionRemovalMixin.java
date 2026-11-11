package dev.senluo.witchcraft.mixin.draconic;

import dev.senluo.witchcraft.region.RegionProtection;
import dev.senluo.witchcraft.region.RegionRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** DE's batch bypasses Level and writes chunk sections directly. Veto before any removal or chunk access. */
@Pseudo
@Mixin(targets = "com.brandon3055.draconicevolution.lib.ExplosionHelper", remap = false)
public abstract class RegionalExplosionRemovalMixin {
    @Shadow @Final private ServerLevel serverWorld;
    @Inject(method = "removeBlock", at = @At("HEAD"), cancellable = true)
    private void witchcraft$region(BlockPos pos, CallbackInfo ci) {
        if (RegionProtection.explosionEdit(serverWorld, pos)) ci.cancel();
    }
    @Inject(method = "removeBlock", at = @At("RETURN"))
    private void witchcraft$geometryChanged(BlockPos pos, CallbackInfo ci) {
        // Section writes do not pass the native Level.setBlock observer.
        RegionRules.blockChanged(serverWorld, pos);
    }
}
