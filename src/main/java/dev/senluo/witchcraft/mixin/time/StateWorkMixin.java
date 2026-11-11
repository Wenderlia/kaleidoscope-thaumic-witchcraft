package dev.senluo.witchcraft.mixin.time;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.senluo.witchcraft.compat.CropGrowth;
import dev.senluo.witchcraft.time.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class StateWorkMixin {
    @ModifyReturnValue(method = "getTicker", at = @At("RETURN"))
    private <T extends BlockEntity> BlockEntityTicker<T> witchcraft$wrap(BlockEntityTicker<T> ticker) {
        return WorkTicks.wrap(ticker);
    }
    @WrapMethod(method = "randomTick")
    private void witchcraft$randomWork(ServerLevel level, BlockPos pos, RandomSource random, Operation<Void> original) {
        BlockState state = (BlockState)(Object)this;
        TemporalWorld world = TemporalWorld.get(level);
        if (!world.hasDomain(pos) || !CropGrowth.canRandomlyGrow(level, pos, state)
                || !world.absorb(pos, 1, SourceIncomeRates.CROP)) original.call(level, pos, random);
    }
}
