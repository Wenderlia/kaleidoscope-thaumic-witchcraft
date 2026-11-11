package dev.senluo.witchcraft.mixin.ritual;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.ritual.SteamFire;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FireBlock.class)
public abstract class SteamFireMixin {
    @WrapMethod(method="tick")
    private void witchcraft$combustionClock(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, Operation<Void> original) {
        SteamFire.tick(level, pos, (Block)(Object)this,
                () -> original.call(level.getBlockState(pos), level, pos, random));
    }
    @WrapOperation(method="tick", at=@At(value="INVOKE", target="Lnet/minecraft/server/level/ServerLevel;scheduleTick(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;I)V"))
    private void witchcraft$nativeDelay(ServerLevel level, BlockPos pos, Block block, int delay, Operation<Void> original) {
        if (!SteamFire.capture(level, pos, block, delay)) original.call(level, pos, block, delay);
    }
}
