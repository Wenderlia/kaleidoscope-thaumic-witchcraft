package dev.senluo.witchcraft.slash;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Collision policy only: passage neither harvests blocks nor spends cutting potential. */
final class SlashBlockPassage {
    private static final TagKey<Block> VEGETATION = TagKey.create(
            Registries.BLOCK, SlashContent.id("restrained_slash_passable"));
    private static final TagKey<Block> PASSABLE = TagKey.create(
            Registries.BLOCK, SlashContent.id("slash_passable"));

    private SlashBlockPassage() {}

    /** Muzzle and aiming rays obey the same unconditional passage as flight contacts. */
    static net.minecraft.world.level.ClipContext ray(net.minecraft.world.phys.Vec3 from,net.minecraft.world.phys.Vec3 to,
            net.minecraft.world.level.ClipContext.Block mode,net.minecraft.world.entity.Entity entity){
        return new net.minecraft.world.level.ClipContext(from,to,mode,net.minecraft.world.level.ClipContext.Fluid.NONE,entity){
            @Override public net.minecraft.world.phys.shapes.VoxelShape getBlockShape(BlockState state,net.minecraft.world.level.BlockGetter level,BlockPos pos){
                return state.is(PASSABLE)?net.minecraft.world.phys.shapes.Shapes.empty():super.getBlockShape(state,level,pos);
            }
        };
    }

    /** Local boxes shared by preflight and live collision; callers own loaded-state and work budgets. */
    static List<AABB> boxes(SlashEntity slash, ServerPlayer player, BlockPos pos) {
        if (slash.passesBlocks()) return List.of();
        var state = slash.level().getBlockState(pos);
        if (state.isAir() || allows(slash, state, pos)
                || !state.getFluidState().isEmpty() && state.getCollisionShape(slash.level(), pos).isEmpty())
            return List.of();
        return state.getShape(slash.level(), pos, CollisionContext.of(player)).toAabbs();
    }

    static boolean allows(SlashEntity slash, BlockState state, BlockPos pos) {
        if (slash.passesBlocks()) return true;
        if (state.is(PASSABLE)) return true;
        if (!slash.restrained()) return false;
        return state.is(VEGETATION)
                || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.SAPLINGS)
                || state.is(BlockTags.CROPS)
                || state.getBlock() instanceof LeavesBlock
                || state.getBlock() instanceof BushBlock
                || state.getBlock() instanceof GrowingPlantBlock
                // Native state geometry, not opacity or a material/name whitelist. Double slabs
                // and glass cubes still block; stairs, fences and single slabs can be crossed intact.
                || !state.isCollisionShapeFullBlock(slash.level(), pos);
    }
}
