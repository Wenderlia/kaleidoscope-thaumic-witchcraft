package dev.senluo.witchcraft.mixin.draconic;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.region.RegionProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "com.brandon3055.draconicevolution.blocks.reactor.ProcessExplosion", remap = false)
public abstract class RegionalReactorLavaMixin {
    @WrapOperation(method = "detonate", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z", remap = true))
    private boolean witchcraft$region(ServerLevel level, BlockPos pos, BlockState state, Operation<Boolean> original) {
        return !RegionProtection.explosionEdit(level, pos) && original.call(level, pos, state);
    }
}
