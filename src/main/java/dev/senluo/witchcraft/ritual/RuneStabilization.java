package dev.senluo.witchcraft.ritual;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** Shared rune-support observation. Consumers decide whether stabilization is required or grants a bonus. */
public final class RuneStabilization {
    public enum Result { STABILIZED, UNSTABILIZED, UNLOADED }

    /** Material eligibility belongs to its provider; rune position rules belong here. */
    @FunctionalInterface
    public interface Stabilizer {
        boolean matches(ServerLevel level, BlockPos pos, BlockState state);
    }

    private RuneStabilization() {}

    /** Input is a known rune's marked support block, not the physical chalk layer above it. */
    public static Result at(ServerLevel level, BlockPos support, Stabilizer stabilizer) {
        if (!level.hasChunkAt(support)) return Result.UNLOADED;
        if (stabilizer.matches(level, support, level.getBlockState(support))) return Result.STABILIZED;
        BlockPos below = support.below();
        return stabilizer.matches(level, below, level.getBlockState(below))
                ? Result.STABILIZED : Result.UNSTABILIZED;
    }

    /** Aggregate the rune observations only; RitualShape separately owns complete, drawable geometry. */
    public static Result all(ServerLevel level, BlockPos anchor, RitualShape shape, Stabilizer stabilizer) {
        for (BlockPos offset : shape.marks()) {
            Result result = at(level, anchor.offset(offset), stabilizer);
            if (result != Result.STABILIZED) return result;
        }
        return Result.STABILIZED;
    }
}
