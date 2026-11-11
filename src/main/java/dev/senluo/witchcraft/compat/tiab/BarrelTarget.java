package dev.senluo.witchcraft.compat.tiab;

import com.github.ysbbbbbb.kaleidoscopetavern.block.brew.BarrelBlock;
import com.github.ysbbbbbb.kaleidoscopetavern.blockentity.brew.BarrelBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class BarrelTarget {
    private BarrelTarget() {}

    public static BlockPos resolve(UseOnContext context, BlockPos clicked) {
        Level level = context.getLevel();
        BlockState state = level.getBlockState(clicked);
        if (!(state.getBlock() instanceof BarrelBlock)) {
            return clicked;
        }

        BlockPos origin = BarrelBlock.getOriginPos(clicked, state);
        if (!level.isLoaded(origin)) {
            return clicked;
        }
        BarrelBlockEntity barrel = BarrelBlock.getBarrelEntity(level, clicked, state);
        // All 27 parts must use the same target for TIAB's existing rate/cost lookup.
        return barrel == null ? clicked : barrel.getBlockPos();
    }
}
