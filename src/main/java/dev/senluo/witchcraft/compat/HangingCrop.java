package dev.senluo.witchcraft.compat;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Explicit harvest/replant semantics. A crop tag alone does not promise safe age reset or drops. */
public interface HangingCrop {
    List<HangingCrop> ADAPTERS = List.of(new ManaPodCrop());

    static HangingCrop forCrop(BlockState state) {
        return ADAPTERS.stream().filter(adapter -> adapter.matches(state)).findFirst().orElse(null);
    }
    static HangingCrop forSeed(ItemStack seed) {
        return ADAPTERS.stream().filter(adapter -> adapter.isSeed(seed)).findFirst().orElse(null);
    }

    boolean matches(BlockState state);
    boolean isSeed(ItemStack stack);
    boolean mature(BlockState state);
    boolean canPlant(ServerLevel level, BlockPos pos);
    BlockState afterHarvest(BlockState state);
    List<ItemStack> drops(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer actor, ItemStack tool);
}
