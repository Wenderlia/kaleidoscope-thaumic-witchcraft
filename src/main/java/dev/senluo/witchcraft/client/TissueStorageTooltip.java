package dev.senluo.witchcraft.client;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.time.TemporalText;
import dev.senluo.witchcraft.time.RootDisplay;
import dev.senluo.witchcraft.time.TimeStorage;
import dev.senluo.witchcraft.time.Tissue;
import dev.senluo.witchcraft.time.TissueItem;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;

/** Read-only total and selected-side balances from one item snapshot, never from the world. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class TissueStorageTooltip implements ClientTooltipComponent {
    // An extra 0.85 transform unevenly resamples pixel glyphs at common GUI scales.
    private static final float TEXT_SCALE = 1.0F;
    private static final int TOTAL_WIDTH = StorageBar.TOTAL_WIDTH;
    private static final int SIDE_WIDTH = 48;
    private static final int BAR_HEIGHT = StorageBar.HEIGHT;
    private static final int GAP = 4;


    public record View(long stored, long capacity, int maxWidth, boolean side, boolean locked,
                       boolean precise) implements TooltipComponent {}
    public record Snapshot(View total, View side) {}

    private final View view;
    private List<FormattedCharSequence> numbers = List.of();
    private Font layoutFont;
    private int lineHeight;
    private int barWidth;
    private int contentWidth;
    private int contentHeight;
    private boolean inline;

    public static Snapshot snapshot(ItemStack stack, int width, boolean precise) {
        Tissue tissue = TissueItem.read(stack);
        TimeStorage balance = TimeStorage.of(tissue);
        int limit = Math.max(8, width);
        View total = new View(balance.quantity(), balance.capacity(), limit, false, false, precise);
        RootDisplay side = RootDisplay.selected(tissue);
        View sideView = null;
        if (side != null) {
            sideView = new View(side.stored(), side.capacity(), limit, true, side.locked(), true);
        }
        return new Snapshot(total, sideView);
    }

    TissueStorageTooltip(View view) {
        this.view = view;
        layout(net.minecraft.client.Minecraft.getInstance().font);
    }

    @SubscribeEvent
    public static void register(RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(View.class, TissueStorageTooltip::new);
    }

    private void layout(Font font) {
        if (font == layoutFont) return;
        layoutFont = font;
        lineHeight = (int)Math.ceil((font.lineHeight + 1) * TEXT_SCALE);
        barWidth = Math.min(view.side() ? SIDE_WIDTH : TOTAL_WIDTH, view.maxWidth());
        var capacity = TemporalText.text("storage.capacity", TemporalText.capacity(view.capacity(), view.precise()));
        int textWidth = (int)Math.ceil(font.width(capacity) * TEXT_SCALE);
        inline = barWidth + GAP + textWidth <= view.maxWidth();
        if (inline) {
            numbers = List.of(capacity.getVisualOrderText());
            contentWidth = barWidth + GAP + textWidth;
            contentHeight = Math.max(BAR_HEIGHT, lineHeight);
        } else {
            // Long exact numbers can wrap, but never stretch the fixed-length bar.
            numbers = font.split(capacity, Math.max(1, (int)Math.floor(view.maxWidth() / TEXT_SCALE)));
            int widest = numbers.stream().mapToInt(font::width).max().orElse(0);
            contentWidth = Math.max(barWidth, (int)Math.ceil(widest * TEXT_SCALE));
            contentHeight = numbers.size() * lineHeight + 1 + BAR_HEIGHT;
        }
    }

    @Override public int getWidth(Font font) { layout(font); return contentWidth; }
    @Override public int getHeight() { return contentHeight + (view.side() ? 5 : 2); }

    @Override public void renderImage(Font font, int x, int y, GuiGraphics graphics) {
        layout(font);
        int barY = inline ? y + (contentHeight - BAR_HEIGHT) / 2 : y + numbers.size() * lineHeight + 1;
        int textX = inline ? x + barWidth + GAP : x;
        int textY = inline ? y + (contentHeight - lineHeight) / 2 : y;
        smallText(graphics, font, numbers, textX, textY);
        StorageBar.draw(graphics, x, barY, barWidth, view.stored(), view.capacity(), view.locked(), view.side());
    }

    private void smallText(GuiGraphics graphics, Font font, List<FormattedCharSequence> lines, int x, int y) {
        for (int i = 0; i < lines.size(); i++) {
            graphics.pose().pushPose();
            graphics.pose().translate(x, y + i * lineHeight, 0);
            graphics.pose().scale(TEXT_SCALE, TEXT_SCALE, 1);
            graphics.drawString(font, lines.get(i), 0, 0, -1, true);
            graphics.pose().popPose();
        }
    }
}
