package dev.senluo.witchcraft.client;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.compat.thaumcraft.SlashClientBridge;
import dev.senluo.witchcraft.ritual.RitualView;
import dev.senluo.witchcraft.slash.SlashRequests;
import dev.senluo.witchcraft.slash.SlashVisibility;
import dev.senluo.witchcraft.slash.drawing.*;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Local visual prediction only. Native held use owns activation; the server measures and pays. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID, value = Dist.CLIENT)
public final class SlashDrawingClient {
    private static Draft draft;
    private static boolean blockedUntilRelease;
    private static final class Draft {
        int id, age, pendingTicks, pending = -1;
        boolean chatLatched, releaseArmed;
        final ResourceLocation dimension;
        final net.minecraft.client.multiplayer.ClientLevel level;
        final java.util.UUID caster;
        final InteractionHand hand;
        final ItemStack wand;
        final Object focus;
        StrokeSphere sphere;
        StrokeTrace trace = new StrokeTrace();
        Draft(Minecraft mc, InteractionHand hand) {
            this.hand = hand;
            level = mc.level; caster = mc.player.getUUID();
            dimension = mc.level.dimension().location(); wand = mc.player.getItemInHand(hand);
            focus = dev.senluo.witchcraft.compat.thaumcraft.SlashFocus.state(wand);
            sphere = StrokeSphere.facing(mc.player.getLookAngle());
        }
    }
    public static boolean active() { return draft != null; }
    private static boolean eligible(Minecraft mc, Draft d) {
        return mc.player != null && mc.level != null && mc.player.isAlive() && !mc.player.isSpectator()
                && mc.level == d.level && SlashVisibility.CASTER_ONLY.visibleTo(d.caster, mc.player.getUUID())
                && (mc.screen == null || mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen)
                && mc.isWindowActive() && !mc.isPaused() && RitualView.get(mc.player) == null
                && d.dimension.equals(mc.level.dimension().location()) && !SlashClientBridge.selectingFocus()
                && mc.player.getItemInHand(d.hand).getItem() == d.wand.getItem()
                && java.util.Objects.equals(d.focus, dev.senluo.witchcraft.compat.thaumcraft.SlashFocus.state(mc.player.getItemInHand(d.hand)));
    }
    public static void begin(net.minecraft.world.entity.player.Player caster, InteractionHand hand) {
        Minecraft mc = Minecraft.getInstance();
        if (caster != mc.player || blockedUntilRelease || draft != null || mc.player == null || mc.level == null || !mc.options.keyUse.isDown()
                || mc.screen != null || !mc.isWindowActive() || !SlashRequests.installed(mc.player.getItemInHand(hand))) return;
        draft = new Draft(mc, hand);
    }
    public static void status(SlashDrawing.Status status) {
        Minecraft mc = Minecraft.getInstance();
        if (draft == null || mc.level == null || draft.hand != status.hand() || !draft.dimension.equals(status.dimension())) {
            if (status.open() && mc.player != null)
                PacketDistributor.sendToServer(new SlashDrawing.Control(status.id(), status.dimension(), SlashDrawing.CANCEL));
            return;
        }
        if (!status.open()) {
            if (draft.id == 0 || draft.id == status.id()) clear(mc);
            return;
        }
        if (draft.id != 0 && draft.id != status.id()) {
            PacketDistributor.sendToServer(new SlashDrawing.Control(status.id(), status.dimension(), SlashDrawing.CANCEL)); return;
        }
        draft.id = status.id();
        // The native start packet can trail the local look sample. Authority fixes the frame once.
        if (draft.sphere.forward().dot(status.forward()) < .9999) {
            StrokeSphere authoritative = StrokeSphere.facing(status.forward());
            draft.trace.rebase(draft.sphere, authoritative);
            draft.sphere = authoritative;
        }
        if (draft.chatLatched) send(mc, SlashDrawing.CHAT_LATCH);
        if (draft.pending >= 0) send(mc, draft.pending);
    }
    private static void send(Minecraft mc, int action) {
        if (draft == null || draft.id == 0 || mc.player == null) return;
        mc.player.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
                mc.player.getYRot(), mc.player.getXRot(), mc.player.onGround()));
        PacketDistributor.sendToServer(new SlashDrawing.Control(draft.id, draft.dimension, action));
    }
    private static void finish(Minecraft mc, boolean release) {
        if (draft == null || draft.pending >= 0) return;
        if (release && eligible(mc, draft)) draft.trace.finish(draft.sphere.project(mc.player.getLookAngle()));
        SlashDrawingPreview.clear();
        draft.pending = release && eligible(mc, draft) ? SlashDrawing.RELEASE : SlashDrawing.CANCEL;
        if (draft.pending == SlashDrawing.CANCEL) blockedUntilRelease = true;
        send(mc, draft.pending);
    }
    private static void clear(Minecraft mc) {
        Draft old = draft; draft = null;
        SlashDrawingPreview.clear();
        if (old != null && mc.player != null && mc.player.isUsingItem()
                && mc.player.getUsedItemHand() == old.hand
                && SlashRequests.installed(mc.player.getUseItem())) mc.player.stopUsingItem();
    }
    @SubscribeEvent public static void key(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.keyUse.matches(event.getKey(), event.getScanCode())) useButton(mc, event.getAction());
    }
    @SubscribeEvent public static void mouse(InputEvent.MouseButton.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.keyUse.matchesMouse(event.getButton())) useButton(mc, event.getAction());
    }
    private static void useButton(Minecraft mc, int action) {
        if (draft != null && draft.chatLatched) {
            if (mc.screen != null) return;
            if (action == GLFW.GLFW_PRESS) draft.releaseArmed = true;
            if (action == GLFW.GLFW_RELEASE && draft.releaseArmed) finish(mc, true);
        } else if (action == GLFW.GLFW_RELEASE) finish(mc, true);
        if (action == GLFW.GLFW_RELEASE) blockedUntilRelease = false;
    }
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public static void screen(ScreenEvent.Opening event) {
        if (event.isCanceled()) return;
        if (draft == null || draft.pending >= 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (event.getNewScreen() instanceof net.minecraft.client.gui.screens.ChatScreen) {
            draft.chatLatched = true; draft.releaseArmed = false;
            send(mc, SlashDrawing.CHAT_LATCH);
        } else if (event.getNewScreen() != null) finish(mc, false);
    }
    @SubscribeEvent public static void use(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.isUseItem() && (blockedUntilRelease || draft != null && draft.chatLatched)
                && mc.player != null && SlashRequests.installed(mc.player.getItemInHand(event.getHand()))) {
            event.setCanceled(true); event.setSwingHand(false);
        }
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.options.keyUse.isDown()) blockedUntilRelease = false;
        if (draft == null) return;
        draft.age++;
        if (draft.pending >= 0) { if (++draft.pendingTicks > 40) clear(mc); return; }
        if (draft.id == 0 && draft.age > 40) { clear(mc); return; }
        if (!eligible(mc, draft)) { finish(mc, false); return; }
        if (!draft.chatLatched && !mc.options.keyUse.isDown()) { finish(mc, true); return; }
        if (!draft.chatLatched && (!mc.player.isUsingItem() || mc.player.getUsedItemHand() != draft.hand) && draft.age > 4) { finish(mc, false); return; }
        draft.trace.sample(draft.sphere.project(mc.player.getLookAngle()));
        if (draft.age % 5 == 0) send(mc, SlashDrawing.KEEP_ALIVE);
    }
    @SubscribeEvent public static void frame(RenderFrameEvent.Pre event) { SlashDrawingPreview.clear(); }
    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        SlashDrawingPreview.clear();
        if (draft == null || draft.pending >= 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (!eligible(mc, draft) || mc.getOverlay() != null || mc.getCameraEntity() != mc.player) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 origin = draft.sphere.origin(mc.player.getEyePosition(partial));
        SlashDrawingPreview.capture(event, draft, origin, draft.sphere.project(mc.player.getViewVector(partial)));
    }
    static void renderGui(net.minecraft.client.gui.GuiGraphics gui, net.minecraft.client.DeltaTracker partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (draft == null || draft.pending >= 0 || !eligible(mc, draft) || mc.getOverlay() != null
                || mc.getCameraEntity() != mc.player) { SlashDrawingPreview.clear(); return; }
        SlashDrawingPreview.render(gui, draft, draft.sphere, draft.trace);
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        draft = null; blockedUntilRelease = false; SlashDrawingPreview.clear();
    }
}
