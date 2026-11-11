package dev.senluo.witchcraft.brew;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.CommonHooks;
import org.joml.Vector3f;

/** Small, player-attributed changes. Never guesses arbitrary mod age fields or runs a BE ticker. */
final class DawnCultivation {
    private static final DustParticleOptions DEW = new DustParticleOptions(new Vector3f(.83F, .96F, .69F), .55F);
    private DawnCultivation() {}

    static void walk(ServerPlayer player, BlockPos feet, DawnWalk.State budget) {
        if (!player.mayBuild() || player.isSpectator()) return;
        var level = player.serverLevel();
        if (level.captureBlockSnapshots || level.restoringBlockSnapshots || !level.capturedBlockSnapshots.isEmpty()) return;
        boolean grew = false, moistened = false;
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-1, -1, -1), feet.offset(1, 1, 1))) {
            if (!level.hasChunkAt(pos) || !level.mayInteract(player, pos)) continue;
            BlockState before = level.getBlockState(pos), after = null;
            boolean crop = false;
            if (before.getBlock() instanceof FarmBlock && before.getValue(FarmBlock.MOISTURE) < 7) {
                after = before.setValue(FarmBlock.MOISTURE, 7);
            } else if (!grew && budget.credits > 0 && budget.cooldown == 0
                    // Moisture preserves a farmland BE; unknown crop BE progress is not inferred.
                    && !before.hasBlockEntity()
                    && level.isAreaLoaded(pos, 1) && before.canSurvive(level, pos)) {
                after = nextStage(before);
                crop = after != null;
            }
            if (after == null || before == after) continue;
            if (crop && !CommonHooks.canCropGrow(level, pos, before, true)) continue;
            if (!commit(player, pos.immutable(), before, after)) continue;
            if (crop) {
                CommonHooks.fireCropGrowPost(level, pos, before);
                budget.credits--; budget.cooldown = 100; grew = true;
                if (budget.quality >= 6) player.getFoodData().eat(1, .5F);
                level.sendParticles(DEW, pos.getX() + .5, pos.getY() + .6, pos.getZ() + .5, 3, .2, .15, .2, 0);
            } else moistened = true;
        }
        if (grew || moistened) {
            level.playSound(null, feet, SoundEvents.AZALEA_LEAVES_STEP, SoundSource.PLAYERS, .22F, 1.2F);
            level.sendParticles(DEW, player.getX(), player.getY() + .15, player.getZ(), 1, .16, .05, .16, 0);
        }
    }

    static BlockState nextStage(BlockState state) {
        if (state.getBlock() instanceof CropBlock crop)
            return crop.isMaxAge(state) ? null : crop.getStateForAge(crop.getAge(state) + 1);
        if (state.getBlock() instanceof SweetBerryBushBlock)
            return state.getValue(SweetBerryBushBlock.AGE) >= 3 ? null
                    : state.setValue(SweetBerryBushBlock.AGE, state.getValue(SweetBerryBushBlock.AGE) + 1);
        if (state.getBlock() instanceof SaplingBlock)
            return state.getValue(SaplingBlock.STAGE) == 0 ? state.setValue(SaplingBlock.STAGE, 1) : null;
        return null;
    }

    /** Same capture/event/restore boundary as native item placement, restricted to one known change. */
    static boolean commit(ServerPlayer player, BlockPos pos, BlockState before, BlockState after) {
        return dev.senluo.witchcraft.world.BlockPlacement.commit(player, pos, before, after);
    }
}
