package dev.senluo.witchcraft.mixin.ritual;

import java.util.function.BiConsumer;
import dev.senluo.witchcraft.region.RegionProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Final native block dispatch, including mod explosions which bypass or supplement the affected-block event. */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class RegionalExplosionBlockMixin {
    @Inject(method = "onExplosionHit", at = @At("HEAD"), cancellable = true)
    private void witchcraft$region(Level level, BlockPos pos, Explosion explosion,
            BiConsumer<ItemStack, BlockPos> drops, CallbackInfo ci) {
        if (RegionProtection.explosionEdit(level, pos)) ci.cancel();
    }
}
