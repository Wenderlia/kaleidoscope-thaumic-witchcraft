package dev.senluo.witchcraft.mixin.ritual;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.region.RegionProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FallingBlockEntity.class)
public abstract class RegionalExplosionDebrisMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean witchcraft$landing(Level level, BlockPos pos, BlockState state, int flags, Operation<Boolean> original) {
        var entity = (FallingBlockEntity) (Object) this;
        return !(entity.getPersistentData().getBoolean(RegionProtection.EXPLOSION_DEBRIS) && RegionProtection.explosionEdit(level, pos))
                && original.call(level, pos, state, flags);
    }
}
