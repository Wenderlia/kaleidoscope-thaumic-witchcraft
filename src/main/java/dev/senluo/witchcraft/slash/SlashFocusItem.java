package dev.senluo.witchcraft.slash;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.Enchantment;

/** Standard item enchantment carrier. Focus ranks/properties are a separate component. */
public class SlashFocusItem extends Item {
    public SlashFocusItem() { super(new Properties().stacksTo(1)); }
    @Override public boolean isEnchantable(ItemStack stack) { return stack.getCount() == 1; }
    @Override public int getEnchantmentValue(ItemStack stack) { return Items.IRON_SWORD.getEnchantmentValue(stack); }

    @Override public boolean supportsEnchantment(ItemStack stack, Holder<Enchantment> enchantment) {
        return super.supportsEnchantment(stack, enchantment)
                || swords(enchantment.value().definition().supportedItems());
    }
    @Override public boolean isPrimaryItemFor(ItemStack stack, Holder<Enchantment> enchantment) {
        if (!supportsEnchantment(stack, enchantment)) return false;
        return enchantment.value().definition().primaryItems()
                .map(items -> stack.is(items) || swords(items)).orElse(true);
    }
    private static boolean swords(HolderSet<Item> items) {
        // Inspect the data-defined accepted family, not an enchantment ID whitelist or one reference sword.
        return items.stream().anyMatch(item -> item.is(ItemTags.SWORDS) || item.value() instanceof SwordItem);
    }
}
