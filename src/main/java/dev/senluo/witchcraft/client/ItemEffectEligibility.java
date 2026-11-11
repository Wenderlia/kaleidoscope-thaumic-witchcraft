package dev.senluo.witchcraft.client;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import dev.senluo.witchcraft.brew.WineData;
import dev.senluo.witchcraft.time.TemporalContent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Shared visual qualification; the wine's own rules remain authoritative. */
final class ItemEffectEligibility {
    // Weak keys use identity, so neither NBT hashing nor retained stacks are needed.
    // Replacing immutable CustomData naturally invalidates the corresponding view.
    private static final Cache<CustomData, Boolean> GILDED_WINE = CacheBuilder.newBuilder()
            .weakKeys().maximumSize(128).concurrencyLevel(1).build();

    private ItemEffectEligibility() {}

    static boolean isGildedWine(ItemStack stack) {
        if (!stack.is(TemporalContent.LIUJIN_VODKA.get())) return false;
        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        Boolean cached = GILDED_WINE.getIfPresent(data);
        if (cached != null) return cached;
        WineData wine = WineData.read(stack);
        boolean eligible = wine != null && wine.grade() >= 13;
        GILDED_WINE.put(data, eligible);
        return eligible;
    }
}
