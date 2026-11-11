package dev.senluo.witchcraft.mixin.tacz;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.tacz.guns.util.block.BlockRayTrace;
import dev.senluo.witchcraft.compat.tacz.TaczBarrier;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = BlockRayTrace.class, remap = false)
public abstract class BlockRayBarrierMixin {
    @WrapMethod(method = "rayTraceBlocks")
    private static BlockHitResult witchcraft$prefix(Level level, ClipContext context, Operation<BlockHitResult> original) {
        return TaczBarrier.clip(level, context, prefix -> original.call(level, prefix));
    }
}
