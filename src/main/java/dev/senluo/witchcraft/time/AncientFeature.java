package dev.senluo.witchcraft.time;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/** New-chunk world generation only; moving or harvesting a specimen never invokes this. */
public final class AncientFeature extends Feature<NoneFeatureConfiguration> {
    public AncientFeature() { super(NoneFeatureConfiguration.CODEC); }
    @Override public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        var world = context.level();
        var random = context.random();
        BlockPos origin = context.origin();
        int roll = random.nextInt(100);
        int tier = roll < 60 ? 0 : roll < 85 ? 1 : roll < 97 ? 2 : 3;
        int coalCount = new int[]{6, 12, 24, 48}[tier];
        long amount = new long[]{60, 300, 1200, 6000}[tier] * Tissue.TU;
        for (int attempt = 0; attempt < 4; attempt++) {
            BlockPos core = new BlockPos((origin.getX() & ~15) + 4 + random.nextInt(8), -60 + random.nextInt(13),
                    (origin.getZ() & ~15) + 4 + random.nextInt(8));
            if (!world.getBlockState(core).is(Blocks.DEEPSLATE) || world.getBlockEntity(core) != null) continue;
            List<BlockPos> candidates = new ArrayList<>();
            for (BlockPos p : BlockPos.betweenClosed(core.offset(-6, -3, -6), core.offset(6, 3, 6))) {
                if (p.equals(core) || p.getY() < -63 || p.getY() > -45 || p.getX() >> 4 != core.getX() >> 4
                        || p.getZ() >> 4 != core.getZ() >> 4 || !world.getBlockState(p).is(Blocks.DEEPSLATE)
                        || world.getBlockEntity(p) != null) continue;
                candidates.add(p.immutable());
            }
            if (candidates.size() < coalCount) continue;
            candidates.sort(Comparator.comparingDouble(core::distSqr));
            List<BlockPos> selected = candidates.subList(0, coalCount);
            List<BlockPos> written = new ArrayList<>();
            BlockState host = world.getBlockState(core);
            boolean failed = false;
            for (BlockPos p : selected) {
                if (!world.setBlock(p, Blocks.DEEPSLATE_COAL_ORE.defaultBlockState(), 2)) { failed = true; break; }
                written.add(p);
            }
            if (!failed && world.setBlock(core, TemporalContent.AMBER.get().defaultBlockState()
                    .setValue(LivingBlock.AGE, tier == 3 ? 1 : 0), 2)
                    && world.getBlockEntity(core) instanceof LivingEntity entity) {
                entity.host = host;
                entity.tissue.setCoreQuantity(amount);
                entity.tissue.amberStage = tier == 3 ? 1 : 0;
                entity.setChanged();
                return true;
            }
            for (BlockPos p : written) world.setBlock(p, Blocks.DEEPSLATE.defaultBlockState(), 2);
            world.setBlock(core, host, 2);
        }
        return false;
    }
}
