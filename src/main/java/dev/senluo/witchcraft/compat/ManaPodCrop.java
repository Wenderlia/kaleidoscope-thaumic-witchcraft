package dev.senluo.witchcraft.compat;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/** TC4R 20742 leaf-hanging pod, without loading TC classes when the optional mod is absent. */
final class ManaPodCrop implements HangingCrop {
    private static final ResourceLocation POD = ResourceLocation.parse("thaumcraft:mana_pod");
    private static final ResourceLocation BEAN = ResourceLocation.parse("thaumcraft:mana_bean");

    private IntegerProperty age(BlockState state) {
        var property = state.getBlock().getStateDefinition().getProperty("age");
        return property instanceof IntegerProperty integer && integer.getPossibleValues().contains(0)
                && integer.getPossibleValues().contains(7) ? integer : null;
    }
    public boolean matches(BlockState state) { return BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(POD); }
    public boolean isSeed(ItemStack seed) { return !seed.isEmpty() && BuiltInRegistries.ITEM.getKey(seed.getItem()).equals(BEAN); }
    public boolean mature(BlockState state) { return matches(state) && age(state) != null && state.getValue(age(state)) == 7; }
    public BlockState afterHarvest(BlockState state) { return state.setValue(age(state), 0); }
    public boolean canPlant(ServerLevel level, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getOptional(POD).filter(block -> level.isAreaLoaded(pos, 1)
                && level.getBlockState(pos).isAir() && block.defaultBlockState().canSurvive(level, pos)).isPresent();
    }
    public List<ItemStack> drops(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer actor, ItemStack tool) {
        List<ItemStack> result = new ArrayList<>(Block.getDrops(state, level, pos, level.getBlockEntity(pos), actor, tool));
        // TC20742's table emits 1 + a 67% bonus bean but ignores Fortune. Preserve its aspect and
        // loot context, then add one 1/3-chance bonus per Fortune level (vanilla crop-style trials).
        ItemStack bean = result.stream().filter(this::isSeed).findFirst().orElse(ItemStack.EMPTY);
        if (!bean.isEmpty()) {
            var fortune = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.FORTUNE);
            int rank = Math.clamp(EnchantmentHelper.getItemEnchantmentLevel(fortune, tool), 0, 255), bonus = 0;
            for (int i = 0; i < rank; i++) if (level.random.nextInt(3) == 0) bonus++;
            if (bonus > 0) result.add(bean.copyWithCount(bonus));
        }
        return result;
    }
}
