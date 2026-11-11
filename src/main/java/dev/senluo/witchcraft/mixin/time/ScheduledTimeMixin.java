package dev.senluo.witchcraft.mixin.time;
import dev.senluo.witchcraft.time.TemporalWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ServerLevel.class)
public abstract class ScheduledTimeMixin {
    @Inject(method="tickBlock",at=@At("HEAD"),cancellable=true)
    private void senluo$block(BlockPos pos,Block block,CallbackInfo ci){if(TemporalWorld.get((ServerLevel)(Object)this).scheduled.intercept(pos,block))ci.cancel();}
    @Inject(method="tickFluid",at=@At("HEAD"),cancellable=true)
    private void senluo$fluid(BlockPos pos,Fluid fluid,CallbackInfo ci){if(TemporalWorld.get((ServerLevel)(Object)this).scheduled.intercept(pos,fluid))ci.cancel();}
}
