package dev.senluo.witchcraft.mixin.brew;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.senluo.witchcraft.brew.TimeMining;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class MiningProgressMixin {
    @WrapMethod(method="getDestroyProgress")
    private float witchcraft$mineFaster(Player player,BlockGetter level,BlockPos pos,Operation<Float> original) {
        return TimeMining.progress(player,pos,(BlockState)(Object)this,original.call(player,level,pos));
    }
}
