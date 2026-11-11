package dev.senluo.witchcraft.mixin.thaumcraft.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.senluo.witchcraft.compat.thaumcraft.client.CrucibleRim;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BaseEntityBlock.class)
public abstract class CrucibleTickerMixin {
    @ModifyReturnValue(method = "createTickerHelper", at = @At("RETURN"))
    private static <T extends BlockEntity> BlockEntityTicker<T> witchcraft$observeCrucible(
            BlockEntityTicker<T> original, BlockEntityType<T> requested) {
        return CrucibleRim.observe(original, requested);
    }
}
