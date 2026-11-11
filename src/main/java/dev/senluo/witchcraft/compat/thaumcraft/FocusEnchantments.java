package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.enchanting.GetEnchantmentLevelEvent;

/** Expose an installed focus through the host's gameplay-enchantment query, without writing wand NBT. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID)
public final class FocusEnchantments {
    private FocusEnchantments() {}
    @SubscribeEvent public static void levels(GetEnchantmentLevelEvent event) {
        if (!ModList.get().isLoaded("thaumcraft")) return;
        var focus = SlashFocus.installedCarrier(event.getStack());
        if (focus.isEmpty()) return;
        // This is the separate focus stack, not another carrier: the event cannot recurse into itself.
        // Keep native equipment/loot/tick consumers and third-party gameplay-only enchantments working.
        if (event.getTargetEnchant() != null) {
            var enchantment = event.getTargetEnchant();
            event.getEnchantments().upgrade(enchantment, focus.getEnchantmentLevel(enchantment));
        } else {
            var enchants = focus.getAllEnchantments(event.getLookup());
            for (var entry : enchants.entrySet())
                event.getEnchantments().upgrade(entry.getKey(), entry.getIntValue());
        }
    }
}
