package dev.senluo.witchcraft.time;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** One material contract for growth preflight, exterior reachability and placement. */
final class TreeGrowthMaterial {
    private static final TagKey<Block> SUBSTRATES = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft", "tree_root_replaceable"));
    private TreeGrowthMaterial() {}

    static boolean substrate(BlockState state) {
        // Root conversion has its own drop suppression; other block entities are never swallowed.
        return state.is(TemporalContent.ROOT.get()) || !state.hasBlockEntity()
                && state.getFluidState().isEmpty() && state.is(SUBSTRATES);
    }
}
