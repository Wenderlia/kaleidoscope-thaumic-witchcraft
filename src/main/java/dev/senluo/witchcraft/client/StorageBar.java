package dev.senluo.witchcraft.client;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** The shared meter primitive has no text, backdrop, screen anchor or network dependency. */
final class StorageBar {
    static final int HEIGHT = 8;
    static final int TOTAL_WIDTH = 80;
    private static final int BAR_HEIGHT = HEIGHT;
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            KaleidoscopeWitchcraft.MOD_ID, "textures/gui/storage_bar.png");
    private static final int TEXTURE_WIDTH = 64, TEXTURE_HEIGHT = 16;
    private static final int SELECTED = 0xFF83D0C8;

    private StorageBar() {}

    static void draw(GuiGraphics graphics, int x, int y, int width, long stored, long capacity,
                     boolean locked, boolean selected) {
        if (width < 3) return;
        graphics.flush();
        graphics.pose().pushPose();
        repeat(graphics, x, y, width, 0, 0, 16, BAR_HEIGHT);
        graphics.pose().translate(0, 0, 1);
        piece(graphics, x, y, 16, 0, 1, BAR_HEIGHT);
        piece(graphics, x + width - 1, y, 17, 0, 1, BAR_HEIGHT);
        int filled = pixels(stored, capacity, width - 2);
        repeat(graphics, x + 1, y + 1, filled, 32, 1, 32, BAR_HEIGHT - 2);
        graphics.pose().translate(0, 0, 1);
        if (locked) seals(graphics, x + 1, y + 2, width - 2);
        if (selected) graphics.fill(x + 1, y + BAR_HEIGHT + 1, x + width - 1,
                y + BAR_HEIGHT + 2, SELECTED);
        graphics.pose().popPose();
    }

    private static void seals(GuiGraphics graphics, int x, int y, int width) {
        if (width <= 0) return;
        if (width < 7) {
            piece(graphics, x + width / 2, y, 5, 8, 1, 3);
            return;
        }
        int count = Math.max(1, (width + 12) / 28);
        for (int i = 0; i < count; i++) {
            int center = (2 * i + 1) * width / (2 * count);
            piece(graphics, x + center - 2, y, 0, 8, 5, 4);
        }
    }

    private static void repeat(GuiGraphics graphics, int x, int y, int width,
                               int u, int v, int tileWidth, int height) {
        for (int offset = 0; offset < width;) {
            int sourceX = offset % tileWidth;
            int count = Math.min(width - offset, tileWidth - sourceX);
            piece(graphics, x + offset, y, u + sourceX, v, count, height);
            offset += count;
        }
    }

    private static void piece(GuiGraphics graphics, int x, int y, int u, int v, int width, int height) {
        graphics.blit(TEXTURE, x, y, (float)u, (float)v, width, height, TEXTURE_WIDTH, TEXTURE_HEIGHT);
    }

    private static int pixels(long amount, long capacity, int width) {
        if (width <= 0 || capacity <= 0 || amount <= 0) return 0;
        if (amount >= capacity) return width;
        // A nonzero balance must not disappear below one pixel on a large-capacity tree.
        // Conversely, floating-point rounding must not make a nearly-full balance look full.
        return Math.max(1, Math.min(Math.max(1, width - 1), (int)Math.floor((double)amount / capacity * width)));
    }

}
