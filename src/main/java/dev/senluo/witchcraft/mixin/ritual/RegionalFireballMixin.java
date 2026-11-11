package dev.senluo.witchcraft.mixin.ritual;

import dev.senluo.witchcraft.region.RegionProtection;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SmallFireball.class)
public abstract class RegionalFireballMixin {
    @Inject(method = "onHitBlock", at = @At("HEAD"), cancellable = true)
    private void witchcraft$region(BlockHitResult hit, CallbackInfo ci) {
        var shot = (SmallFireball)(Object)this;
        if (RegionProtection.mobEdit(shot.level(), hit.getBlockPos().relative(hit.getDirection()), shot)) ci.cancel();
    }
}
