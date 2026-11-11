package dev.senluo.witchcraft.mixin.draconic;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.region.RegionProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Only the reactor's forced post-blast work is suppressed; ordinary world physics still runs. */
@Pseudo
@Mixin(targets = "com.brandon3055.draconicevolution.lib.ExplosionHelper$RemovalProcess", remap = false)
public abstract class RegionalExplosionUpdatesMixin {
    @WrapOperation(method = "updateBlocks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V", remap = true))
    private void witchcraft$fall(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, Operation<Void> original) {
        if (!RegionProtection.explosionEdit(level, pos)) original.call(state, level, pos, random);
    }
    @WrapOperation(method = "updateBlocks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;handleNeighborChanged(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos;Z)V", remap = true))
    private void witchcraft$neighbor(BlockState state, Level level, BlockPos pos, Block neighbor,
            BlockPos source, boolean moving, Operation<Void> original) {
        if (!RegionProtection.explosionEdit(level, pos)) original.call(state, level, pos, neighbor, source, moving);
    }
}
