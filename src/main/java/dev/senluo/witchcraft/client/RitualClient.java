package dev.senluo.witchcraft.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.InputConstants;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.ritual.RitualPackets;
import dev.senluo.witchcraft.ritual.RitualView;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID, value = Dist.CLIENT)
public final class RitualClient {
    private static PostChain gray;
    private static boolean failed, previousShift;
    private static int width, height, heartbeat, selected;
    private static int heldScan = -1;
    private static UUID session;
    private RitualClient() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void freezeEntityClock(EntityTickEvent.Pre event) {
        var entity = event.getEntity();
        // Keep the former non-passenger scope; player animation uses its separate clock.
        if (!entity.level().isClientSide || entity instanceof Player || entity.isPassenger()) return;
        RitualView.State view = RitualView.get(entity);
        if (view == null) return;
        view.pose().apply(entity);
        entity.tickCount--; // ClientLevel increments this before the event.
        event.setCanceled(true);
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) { clear(); return; }
        RitualView.State view = RitualView.get(mc.player);
        if (view == null) { session = null; heartbeat = 0; return; }
        view.pose().apply(mc.player);
        boolean fresh = !view.session().equals(session);
        if (fresh) { session = view.session(); selected = mc.player.getInventory().selected; }
        mc.player.getInventory().selected = selected;
        // Read the physical binding outside the paused player simulation. Toggle-sneak is not a held key.
        boolean shift = physicalSneak(mc);
        if (fresh || shift != previousShift || ++heartbeat >= 10) {
            PacketDistributor.sendToServer(new RitualPackets.Input(session, shift));
            heartbeat = 0; previousShift = shift;
        }
    }

    private static boolean physicalSneak(Minecraft mc) {
        if (mc.screen != null || !mc.isWindowActive()) { heldScan = -1; return false; }
        var key = mc.options.keyShift.getKey();
        long window = mc.getWindow().getWindow();
        return switch (key.getType()) {
            case KEYSYM -> key.getValue() != InputConstants.UNKNOWN.getValue() && InputConstants.isKeyDown(window, key.getValue());
            case MOUSE -> GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
            case SCANCODE -> heldScan == key.getValue();
        };
    }
    @SubscribeEvent public static void key(InputEvent.Key event) {
        var key = Minecraft.getInstance().options.keyShift.getKey();
        if (key.getType() == InputConstants.Type.SCANCODE && key.getValue() == event.getScanCode())
            heldScan = event.getAction() == GLFW.GLFW_RELEASE ? -1 : event.getScanCode();
    }

    /** Run after world/hand rendering and vanilla post effects, before the GUI projection is installed. */
    public static void render(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getCameraEntity() != mc.player) return;
        RitualView.State view = RitualView.get(mc.player);
        if (view == null || view.gray() <= 0 || failed) return;
        try {
            if (gray == null) gray = new PostChain(mc.getTextureManager(), mc.getResourceManager(), mc.getMainRenderTarget(),
                    ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID, "shaders/post/ownership_gray.json"));
            int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
            if (w != width || h != height) { gray.resize(w, h); width = w; height = h; }
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.resetTextureMatrix();
            gray.setUniform("Saturation", 1 - view.gray());
            gray.process(partialTick);
            mc.getMainRenderTarget().bindWrite(true);
        } catch (java.io.IOException | RuntimeException error) {
            failed = true;
            LoggerFactory.getLogger(KaleidoscopeWitchcraft.MOD_ID).warn("Unable to load ownership grayscale", error);
            mc.getMainRenderTarget().bindWrite(true);
        }
    }

    public static void reload() {
        if (gray != null) { gray.close(); gray = null; }
        width = 0; height = 0; failed = false;
    }
    private static void clear() {
        RitualView.clear(); session = null; heartbeat = 0; previousShift = false; heldScan = -1;
        reload(); RitualRenderTypes.clear();
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }
    @SubscribeEvent public static void screen(ScreenEvent.Opening event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && RitualView.get(mc.player) != null
                && event.getNewScreen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) event.setCanceled(true);
    }
}
