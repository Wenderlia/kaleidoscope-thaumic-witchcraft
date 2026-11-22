package dev.senluo.witchcraft.client;

import com.mojang.datafixers.util.Either;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.time.TemporalTooltips;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.core.component.DataComponents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID, value = Dist.CLIENT)
public final class TemporalTooltipEvents {
    private TemporalTooltipEvents() {}

    @SubscribeEvent
    public static void details(ItemTooltipEvent event) {
        if (event.getEntity() == null || !event.getEntity().level().isClientSide) return;
        if (event.getItemStack().getItem() instanceof dev.senluo.witchcraft.brew.LiujinVodkaItem
                && Screen.hasShiftDown() && !event.getItemStack().has(DataComponents.HIDE_ADDITIONAL_TOOLTIP)) {
            event.getToolTip().removeIf(line -> line.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                    && text.getKey().equals("tooltip.kaleidoscope_witchcraft.wine.hint"));
            for (String key : new String[]{"recipe", "aging", "abilities", "compression"})
                event.getToolTip().add(dev.senluo.witchcraft.brew.LiujinVodkaItem.text(key));
        }
        TemporalTooltips.expand(event.getItemStack(), event.getToolTip(), Screen.hasShiftDown(), Screen.hasControlDown(),
                ModList.get().isLoaded("tiab"));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void width(RenderTooltipEvent.GatherComponents event) {
        if (!TemporalTooltips.supports(event.getItemStack())) return;
        // TC owns the complete focus tooltip, including its own expansion and source-provider behavior.
        if (event.getItemStack().is(dev.senluo.witchcraft.slash.SlashContent.FOCUS.get())) return;
        dev.senluo.witchcraft.compat.quark.QuarkFoodTooltip.fit(event);
        if (!event.getItemStack().has(DataComponents.HIDE_ADDITIONAL_TOOLTIP)
                && !event.getItemStack().has(DataComponents.HIDE_TOOLTIP)) {
            String registered = ModList.get().getModContainerById(KaleidoscopeWitchcraft.MOD_ID)
                    .map(container -> container.getModInfo().getDisplayName()).orElse(null);
            placeSourceAndLore(event.getTooltipElements(), registered);
        }
        int width = Math.max(40, Math.min(240, event.getScreenWidth() - 24));
        width = event.getMaxWidth() > 0 ? Math.min(width, event.getMaxWidth()) : width;
        event.setMaxWidth(width);
        if (!event.getItemStack().has(DataComponents.HIDE_ADDITIONAL_TOOLTIP)
                && !event.getItemStack().has(DataComponents.HIDE_TOOLTIP)) {
            var elements = event.getTooltipElements();
            boolean hasTotal = elements.stream().anyMatch(element -> element.left().filter(line ->
                    storageKey(line).equals("total") || storageKey(line).equals("total_exact")).isPresent());
            TissueStorageTooltip.Snapshot storage = null;
            for (int i = 0; i < elements.size(); i++) {
                var line = elements.get(i).left().orElse(null);
                String key = storageKey(line);
                if (key.equals("core") && hasTotal) {
                    elements.remove(i--);
                    continue;
                }
                boolean total = key.equals("total") || key.equals("total_exact") || key.equals("core");
                if (total || key.equals("side_storage")) {
                    if (storage == null) storage = TissueStorageTooltip.snapshot(event.getItemStack(), width, !key.equals("total"));
                    var view = total ? storage.total() : storage.side();
                    if (view == null) elements.remove(i--);
                    else elements.set(i, Either.right(view));
                }
            }
            for (int i = 0; i < event.getTooltipElements().size(); i++) {
                var text = event.getTooltipElements().get(i).left().orElse(null);
                if (text instanceof net.minecraft.network.chat.Component component && CompactDetailTooltip.numericDetail(text))
                    event.getTooltipElements().set(i, Either.right(new CompactDetailTooltip.Row(component.copy(), width)));
            }
            ItemNameEffects.apply(event);
        }
    }

    static void placeSourceAndLore(List<Either<FormattedText, TooltipComponent>> elements, String registered) {
        var fallback = TemporalTooltips.source();
        String translated = fallback.getString();
        String registeredName = registered == null ? translated : registered;
        Either<FormattedText, TooltipComponent> firstSource = null;
        Either<FormattedText, TooltipComponent> firstLore = null;
        for (var rows = elements.listIterator(); rows.hasNext();) {
            var row = rows.next();
            var text = row.left().orElse(null);
            if (text == null) continue;
            if (TemporalTooltips.isSource(text, registeredName, translated)) {
                if (firstSource != null) {
                    rows.remove();
                    continue;
                }
                firstSource = row;
            }
            if (firstLore == null && storageKey(text).startsWith("vintage.")) firstLore = row;
        }
        if (firstSource == null) {
            firstSource = Either.left(fallback);
            elements.add(firstSource);
        }
        if (firstLore == null) return;
        elements.removeIf(row -> storageKey(row.left().orElse(null)).startsWith("vintage."));
        for (int i = 0; i < elements.size(); i++) {
            if (elements.get(i) == firstSource) {
                elements.add(i, firstLore);
                return;
            }
        }
    }

    private static String storageKey(FormattedText text) {
        if (text instanceof net.minecraft.network.chat.Component component
                && component.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents translation) {
            String prefix = "tooltip.kaleidoscope_witchcraft.";
            if (translation.getKey().startsWith(prefix)) return translation.getKey().substring(prefix.length());
        }
        return "";
    }
}
