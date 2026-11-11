package dev.senluo.witchcraft.time;

import dev.senluo.witchcraft.compat.CropGrowth;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Supply eligibility and price are independent from the receiving tree's absorption quote. */
public final class MachineWork {
    private static final TagKey<Block> EXCLUDED = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft", "machine_acceleration_excluded"));
    private MachineWork() {}
    public static int rate(LivingEntity source) { return source.amber() ? 0 : source.tree() ? source.tissue.giant ? 320 : 80 : 20; }
    public static long price(LivingEntity source) { return (source.tree() ? source.tissue.giant ? 2 : 3 : 4) * Tissue.TU; }

    public static Runnable operation(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos) || !level.shouldTickBlocksAt(pos)) return null;
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity == null || entity.isRemoved() || entity instanceof LivingEntity
                || entity instanceof RootEntity || entity instanceof FruitEntity) return null;
        var state = level.getBlockState(pos);
        if (state.is(EXCLUDED) || CropGrowth.accepts(state) || !WorkSources.outputWork(level, entity)) return null;
        return ticker(level, pos, entity);
    }
    private static <T extends BlockEntity> Runnable ticker(ServerLevel level, BlockPos pos, T entity) {
        var state = level.getBlockState(pos);
        @SuppressWarnings("unchecked")
        var type = (net.minecraft.world.level.block.entity.BlockEntityType<T>) entity.getType();
        var ticker = state.getTicker(level, type);
        return ticker == null ? null : () -> ExtraTicks.run(() -> ticker.tick(level, pos, state, entity));
    }
}
