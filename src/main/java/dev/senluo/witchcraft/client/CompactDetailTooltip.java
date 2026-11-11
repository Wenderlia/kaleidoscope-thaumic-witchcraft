package dev.senluo.witchcraft.client;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import org.joml.Matrix4f;

/** Numeric detail rows keep native glyph scale and use wrapping to fit the available space. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CompactDetailTooltip implements ClientTooltipComponent {
    private static final float SCALE = 1.0F;
    private static final Set<String> KEYS = Set.of("slots", "investment", "growth_time");
    private final List<FormattedCharSequence> lines;
    private final int lineHeight;

    public record Row(Component text, int width) implements TooltipComponent {}

    public static boolean numericDetail(FormattedText text) {
        if (!(text instanceof Component component) || !(component.getContents() instanceof TranslatableContents translation)) return false;
        String prefix = "tooltip.kaleidoscope_witchcraft.";
        String key = translation.getKey();
        return key.startsWith(prefix) && KEYS.contains(key.substring(prefix.length()));
    }

    private CompactDetailTooltip(Row row) {
        Font font = Minecraft.getInstance().font;
        lines = font.split(row.text(), Math.max(1, (int)Math.floor(row.width() / SCALE)));
        lineHeight = Math.max(1, (int)Math.ceil((font.lineHeight + 1) * SCALE));
    }

    @SubscribeEvent
    public static void register(RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(Row.class, CompactDetailTooltip::new);
    }

    @Override public int getHeight() { return lines.size() * lineHeight; }
    @Override public int getWidth(Font font) {
        return (int)Math.ceil(lines.stream().mapToInt(font::width).max().orElse(0) * SCALE);
    }
    @Override public void renderText(Font font, int x, int y, Matrix4f pose, MultiBufferSource.BufferSource buffers) {
        for (int i = 0; i < lines.size(); i++) {
            Matrix4f scaled = new Matrix4f(pose).translate(x, y + i * lineHeight, 0).scale(SCALE, SCALE, 1);
            font.drawInBatch(lines.get(i), 0, 0, -1, true, scaled, buffers, Font.DisplayMode.NORMAL, 0, 15728880);
        }
    }
}
