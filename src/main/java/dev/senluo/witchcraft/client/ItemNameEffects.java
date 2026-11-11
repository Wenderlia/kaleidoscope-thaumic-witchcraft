package dev.senluo.witchcraft.client;

import com.mojang.datafixers.util.Either;
import dev.senluo.witchcraft.accessory.AccessoryContent;
import dev.senluo.witchcraft.time.FruitItem;
import dev.senluo.witchcraft.time.TemporalContent;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.StringDecomposer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;

/** A frame-local name view; the host still owns font selection, bidi, wrapping and drawing. */
final class ItemNameEffects {
    private ItemNameEffects() {}

    private record Eligibility(Item item, WeakReference<CustomData> data, Profile profile) {}
    private static Eligibility lastEligibility;

    // Theme is independent of rarity. Only the wine profile denotes a costly milestone.
    private enum Profile {
        AMBER_FRUIT(2400, 0xEAA63F, 0xFFD36C, 0xFFF1B4, 0xF2BC50, 0xEAA63F),
        GILDED_WINE(2100, 0xE5A03E, 0xF7C85B, 0xFFF2BD, 0xF4BB58, 0xE5A03E),
        HORIZON_STONE(2200, 0x59D4E8, 0x8CA6F5, 0xB991EA, 0xA4F0D8, 0x59D4E8);

        private final int[] colors;
        private final int periodMillis;

        Profile(int periodMillis, int... colors) {
            this.periodMillis = periodMillis;
            this.colors = colors;
        }

        int color(double position, long millis) {
            // Milliseconds from the monotonic render clock, not game ticks or frame count.
            // Several distinct color stops avoid the old, nearly stationary cosine trough.
            double phase = Math.floorMod(millis, periodMillis) / (double) periodMillis;
            double progress = position - phase;
            progress -= Math.floor(progress);
            double segment = progress * (colors.length - 1);
            int index = Math.min((int) segment, colors.length - 2);
            double blend = segment - index;
            blend = blend * blend * (3.0 - 2.0 * blend);
            int first = colors[index], next = colors[index + 1];
            return channel(first, next, 16, blend) << 16 | channel(first, next, 8, blend) << 8
                    | channel(first, next, 0, blend);
        }

        private static int channel(int first, int next, int shift, double blend) {
            int from = first >> shift & 255;
            int to = next >> shift & 255;
            return (int) Math.round(from + (to - from) * blend);
        }
    }

    static void apply(RenderTooltipEvent.GatherComponents event) {
        ItemStack stack = event.getItemStack();
        if (stack.has(DataComponents.HIDE_TOOLTIP) || stack.has(DataComponents.HIDE_ADDITIONAL_TOOLTIP)
                || stack.has(DataComponents.CUSTOM_NAME) || stack.has(DataComponents.ITEM_NAME)) return;
        var elements = event.getTooltipElements();
        if (elements.isEmpty()) return;

        // Verify the native title structure. A different provider's title keeps its own presentation.
        Component expected = Component.empty().append(stack.getHoverName()).withStyle(stack.getRarity().getStyleModifier());
        if (!(elements.getFirst().left().orElse(null) instanceof Component title) || !expected.equals(title)) return;
        Profile profile = profile(stack);
        if (profile == null) return;
        elements.set(0, Either.left(colorize(title, profile, Util.getMillis())));
    }

    private static Profile profile(ItemStack stack) {
        if (stack.is(AccessoryContent.TURTLE_RUNE.get())) return Profile.HORIZON_STONE;
        if (stack.is(TemporalContent.LIUJIN_VODKA.get()))
            return ItemEffectEligibility.isGildedWine(stack) ? Profile.GILDED_WINE : null;
        if (!stack.is(TemporalContent.FRUIT_ITEM.get())) return null;

        // CustomData is replaced on legitimate component updates. Identity avoids hashing/copying NBT
        // again just to animate a name; one weak entry never retains an ItemStack or a world.
        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        if (lastEligibility != null && lastEligibility.item() == stack.getItem()
                && lastEligibility.data().get() == data) return lastEligibility.profile();
        int version = FruitItem.economyVersion(stack);
        Profile profile = version == 2 && FruitItem.grade(stack) == 5 ? Profile.AMBER_FRUIT : null;
        lastEligibility = new Eligibility(stack.getItem(), new WeakReference<>(data), profile);
        return profile;
    }

    private record Glyph(int codePoint, Style style) {}

    private static FormattedText colorize(Component title, Profile profile, long millis) {
        List<Glyph> glyphs = new ArrayList<>();
        // Resolve only this frame's view through the native styled/codepoint visitor, including § formatting.
        // No item data or source Component is modified; effective font, bold/italic and other styles survive.
        StringDecomposer.iterateFormatted(title, Style.EMPTY, (index, style, codePoint) -> {
            glyphs.add(new Glyph(codePoint, style));
            return true;
        });
        if (glyphs.isEmpty()) return title;

        List<FormattedText> colored = new ArrayList<>(glyphs.size());
        for (int i = 0; i < glyphs.size(); i++) {
            Glyph glyph = glyphs.get(i);
            colored.add(FormattedText.of(Character.toString(glyph.codePoint()),
                    glyph.style().withColor(profile.color(i / (double) glyphs.size(), millis))));
        }
        // Keep a text element so ClientHooks uses its actual font and screen/mouse-dependent wrap width.
        return FormattedText.composite(colored);
    }
}
