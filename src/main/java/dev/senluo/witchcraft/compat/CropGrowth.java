package dev.senluo.witchcraft.compat;

import dev.senluo.witchcraft.time.FruitBlock;
import dev.senluo.witchcraft.time.LivingBlock;
import dev.senluo.witchcraft.time.RootBlock;
import dev.senluo.witchcraft.time.RootedFarmlandBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** Read-only crop adapter. The caller owns scheduling, source provenance and TU transactions. */
public final class CropGrowth {
    /** An explicitly implemented, read-only native growth quote; never invokes bonemeal or rolls progress. */
    public interface Readiness {
        boolean canGrow(ServerLevel level, BlockPos pos, BlockState state);
    }
    // Retain the existing data-pack extension point for both absorption and paid growth.
    private static final TagKey<Block> CROPS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft", "absorption_crops"));
    // Explicit capability: having a block entity alone does not mean its ticker grows a crop.
    private static final TagKey<Block> TIMED = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft", "timed_crops"));
    private static final ResourceLocation MANA_POD = ResourceLocation.parse("thaumcraft:mana_pod");
    private static final ResourceLocation INFUSED_CROP = ResourceLocation.parse("thaumictinkerer:infused_crop");
    private CropGrowth() {}

    public static boolean accepts(BlockState state) {
        if (state.getBlock() instanceof LivingBlock || state.getBlock() instanceof RootBlock
                || state.getBlock() instanceof RootedFarmlandBlock || state.getBlock() instanceof FruitBlock) return false;
        return state.getBlock() instanceof CropBlock || state.is(CROPS) || state.is(TIMED);
    }
    public static boolean timed(BlockState state) { return state.is(TIMED); }

    /** Only observable age-based random growth can train adaptive supply; no guessed BE progress. */
    public static int progress(BlockState state) {
        if (!accepts(state) || timed(state)) return -1;
        var age = state.getBlock().getStateDefinition().getProperty("age");
        return age instanceof net.minecraft.world.level.block.state.properties.IntegerProperty property
                ? state.getValue(property) : -1;
    }

    /** Cheap capability filter; the lazy position index keeps mature crops as well. */
    public static boolean canAttempt(BlockState state) {
        if (!accepts(state) || !(timed(state) || state.isRandomlyTicking())) return false;
        if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) return false;
        if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(MANA_POD)) return true;
        // StateHolder uses reference keys. TC's own AGE is not vanilla's equal-looking AGE_7.
        var age = state.getBlock().getStateDefinition().getProperty("age");
        return age instanceof net.minecraft.world.level.block.state.properties.IntegerProperty property
                && property.getPossibleValues().contains(7) && state.getValue(property) < 7;
    }

    public static boolean canRandomlyGrow(ServerLevel level, BlockPos pos, BlockState state) {
        return !timed(state) && canAttempt(level, pos, state);
    }

    /** Quote a possible native growth attempt, without rolling growth or firing crop events twice. */
    public static boolean canAttempt(ServerLevel level, BlockPos pos, BlockState state) {
        if (!canAttempt(state)) return false;
        if (!level.isAreaLoaded(pos, 1) || !state.canSurvive(level, pos)) return false;
        if (state.getBlock() instanceof Readiness ready && !ready.canGrow(level, pos, state)) return false;
        if (timed(state) && ticker(level, pos, state) == null) return false;
        if (BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(INFUSED_CROP))
            return level.getRawBrightness(pos.above(), 0) >= 9;
        if (state.getBlock() instanceof CropBlock crop)
            return !crop.isMaxAge(state) && level.getRawBrightness(pos, 0) >= 9;
        if (state.is(Blocks.NETHER_WART)) return state.getValue(NetherWartBlock.AGE) < 3;
        if (state.is(Blocks.COCOA)) return state.getValue(CocoaBlock.AGE) < 2;
        if (state.is(Blocks.SWEET_BERRY_BUSH))
            return state.getValue(SweetBerryBushBlock.AGE) < 3 && level.getRawBrightness(pos.above(), 0) >= 9;
        if (state.is(Blocks.MELON_STEM) || state.is(Blocks.PUMPKIN_STEM)) {
            if (level.getRawBrightness(pos, 0) < 9) return false;
            if (state.getValue(StemBlock.AGE) < 7) return true;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos fruit = pos.relative(direction);
                BlockState soil = level.getBlockState(fruit.below());
                if (level.isEmptyBlock(fruit) && (soil.getBlock() instanceof FarmBlock || soil.is(BlockTags.DIRT))) return true;
            }
            return false;
        }
        if (state.is(Blocks.SUGAR_CANE))
            return level.isEmptyBlock(pos.above())
                    && !(level.getBlockState(pos.below()).is(Blocks.SUGAR_CANE)
                        && level.getBlockState(pos.below(2)).is(Blocks.SUGAR_CANE));
        if (state.is(Blocks.PITCHER_CROP))
            return state.getValue(PitcherCropBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
                    && ((BonemealableBlock) state.getBlock()).isValidBonemealTarget(level, pos, state);
        // Explicitly tagged custom plants keep their own light/growth semantics.
        return true;
    }

    /** A current native operation, quoted before the caller commits its TU transaction. */
    public record Attempt(boolean timed, Runnable advance) {}

    public static Attempt operation(ServerLevel level, BlockPos pos, BlockState state) {
        if (!canAttempt(level, pos, state)) return null;
        if (!timed(state)) return new Attempt(false, () -> state.randomTick(level, pos, level.random));
        BlockEntity entity = level.getBlockEntity(pos);
        BlockEntityTicker<BlockEntity> ticker = ticker(level, pos, state);
        return ticker == null ? null : new Attempt(true, () -> ticker.tick(level, pos, state, entity));
    }

    @SuppressWarnings("unchecked")
    private static BlockEntityTicker<BlockEntity> ticker(ServerLevel level, BlockPos pos, BlockState state) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity == null || entity.isRemoved()) return null;
        return state.getTicker(level, (BlockEntityType<BlockEntity>) entity.getType());
    }

}
