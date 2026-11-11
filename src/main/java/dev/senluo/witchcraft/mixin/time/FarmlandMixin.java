package dev.senluo.witchcraft.mixin.time;

import dev.senluo.witchcraft.time.RootedFarmlandBlock;
import dev.senluo.witchcraft.time.TemporalContent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FarmBlock.class)
public abstract class FarmlandMixin {
    @Inject(method="turnToDirt", at=@At("HEAD"), cancellable=true)
    private static void senluo$retainRoots(Entity entity, BlockState state, Level level, BlockPos pos, CallbackInfo ci) {
        if (!state.is(TemporalContent.ROOTED_FARMLAND.get())) return;
        RootedFarmlandBlock.turnToRootedDirt(entity, state, level, pos);
        ci.cancel();
    }
}
