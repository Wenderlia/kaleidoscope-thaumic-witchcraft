package dev.senluo.witchcraft.client;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.time.LivingEntity;
import dev.senluo.witchcraft.time.PlantStorageObservation;
import dev.senluo.witchcraft.time.TemporalText;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/** Client presentation owns cache lifetime and native HUD placement; balances come from the server. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class PlantStorageHud {
    private static PlantStorageObservation.Snapshot snapshot;
    private static ClientLevel observedLevel;
    private static long receivedAt;
    private static Component capacityText;
    private static Language labelLanguage;
    private static List<FormattedCharSequence> captionLines = List.of();
    private static Font captionFont;
    private static int captionWidth;

    private PlantStorageHud() {}

    @SubscribeEvent
    public static void register(RegisterGuiLayersEvent event) {
        PlantStorageObservation.install(value -> {
            if (value.capacity() <= 0 || value.stored() < 0 || value.stored() > value.capacity()) {
                clear();
                return;
            }
            if (snapshot == null || value.capacity() != snapshot.capacity()) capacityText = null;
            snapshot = value;
            observedLevel = Minecraft.getInstance().level;
            receivedAt = System.nanoTime();
        });
        event.registerAbove(VanillaGuiLayers.SELECTED_ITEM_NAME,
                ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID, "plant_storage"),
                (graphics, delta) -> {
                    var mc = Minecraft.getInstance();
                    if (snapshot == null || mc.level == null || mc.level != observedLevel || mc.player == null
                            || mc.gameMode == null || mc.options.hideGui || mc.screen != null
                            || !mc.player.isAlive() || mc.player.isSpectator()
                            || !PlantStorageObservation.holdingClock(mc.player)
                            || System.nanoTime() - receivedAt > 1_500_000_000L
                            || !snapshot.dimension().equals(mc.level.dimension().location())
                            || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                            || !snapshot.pos().equals(hit.getBlockPos())
                            || !(mc.level.getBlockEntity(hit.getBlockPos()) instanceof LivingEntity)) return;
                    if (capacityText == null || labelLanguage != Language.getInstance()) {
                        labelLanguage = Language.getInstance();
                        capacityText = TemporalText.text("storage.capacity", TemporalText.capacity(snapshot.capacity(), false));
                        captionLines = List.of();
                    }
                    int width = Math.min(StorageBar.TOTAL_WIDTH, graphics.guiWidth() - 16);
                    if (width < 3) return;
                    // Keep glyph pixels integral on the physical screen. At GUI scale 1 retain
                    // native text size rather than erasing strokes in small Chinese characters.
                    double guiScale = Math.max(1, mc.getWindow().getGuiScale());
                    float textScale = (float)(Math.max(1, Math.floor(guiScale * .75)) / guiScale);
                    int textHeight = (int)Math.ceil(mc.font.lineHeight * textScale);
                    int wrapWidth = Math.max(1, (int)(Math.min(width * 2, graphics.guiWidth() - 8) / textScale));
                    if (captionLines.isEmpty() || captionFont != mc.font || captionWidth != wrapWidth) {
                        captionFont = mc.font;
                        captionWidth = wrapWidth;
                        captionLines = mc.font.split(capacityText, wrapWidth);
                    }
                    int height = StorageBar.HEIGHT + 2 + captionLines.size() * (textHeight + 1) - 1;
                    // Follow NeoForge's health/armor/air displacement. Leave room for action-bar messages too.
                    int shift = Math.max(mc.gui.leftHeight, mc.gui.rightHeight);
                    int itemY = graphics.guiHeight() - Math.max(shift, 59)
                            + (mc.gameMode.canHurtPlayer() ? 0 : 14);
                    int actionY = graphics.guiHeight() - Math.max(shift + 9, 68);
                    int y = Math.max(4, Math.min(itemY - 7, actionY - 5) - height);
                    int x = (graphics.guiWidth() - width) / 2;
                    StorageBar.draw(graphics, x, y, width, snapshot.stored(), snapshot.capacity(), false, false);
                    // The meter owns the center anchor; caption length never shifts or stretches it.
                    // Reuse the existing localized caption, wrapping unusually long resource-pack text.
                    int lineY = y + StorageBar.HEIGHT + 2;
                    graphics.pose().pushPose();
                    graphics.pose().translate(0, 0, 3);
                    for (var line : captionLines) {
                        float textWidth = mc.font.width(line) * textScale;
                        float textX = Math.max(4, Math.min(x + width - textWidth, graphics.guiWidth() - 4 - textWidth));
                        graphics.pose().pushPose();
                        graphics.pose().translate(Math.round(textX * guiScale) / guiScale, lineY, 0);
                        graphics.pose().scale(textScale, textScale, 1);
                        graphics.drawString(mc.font, line, 0, 0, 0xE6FFFFFF, true);
                        graphics.pose().popPose();
                        lineY += textHeight + 1;
                    }
                    graphics.pose().popPose();
                });
    }

    private static void clear() {
        snapshot = null;
        observedLevel = null;
        capacityText = null;
        labelLanguage = null;
        captionLines = List.of();
        captionFont = null;
    }

    @EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID, value = Dist.CLIENT)
    public static final class Lifecycle {
        @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }
    }
}
