package dev.senluo.witchcraft.mixin.time;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.senluo.witchcraft.time.WorkTicks;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Includes accelerators which call EntityBlock.getTicker rather than the level scheduler. */
@Mixin(BaseEntityBlock.class)
public abstract class TickerFactoryMixin {
    @ModifyReturnValue(method = "createTickerHelper", at = @At("RETURN"))
    private static <T extends BlockEntity> BlockEntityTicker<T> witchcraft$wrap(BlockEntityTicker<T> ticker) {
        return WorkTicks.wrap(ticker);
    }
}
