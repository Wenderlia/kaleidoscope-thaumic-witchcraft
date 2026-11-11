package dev.senluo.witchcraft.client;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.ritual.RitualView;
import dev.senluo.witchcraft.slash.SlashRequests;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Physical clicks are immediate; holding the remappable attack key repeats independently. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID, value = Dist.CLIENT)
public final class SlashClient {
    private static boolean pressed;
    private static boolean blockedUntilRelease;
    private static int sequence;
    private static int ticks, nextRepeat;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void attack(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (!event.isAttack() || mc.player == null || !SlashRequests.installed(mc.player.getMainHandItem())) return;
        event.setCanceled(true); event.setSwingHand(false);
        // Raw key/mouse presses own casts. Vanilla can invoke this event again for held mining.
    }
    private static boolean eligible(Minecraft mc) {
        return mc.player != null && mc.level != null && mc.screen == null && mc.isWindowActive() && !mc.isPaused()
                && !mc.player.isSpectator() && !mc.player.isUsingItem() && !SlashDrawingClient.active()
                && SlashRequests.installed(mc.player.getMainHandItem()) && RitualView.get(mc.player) == null
                && !dev.senluo.witchcraft.compat.thaumcraft.SlashClientBridge.selectingFocus();
    }
    private static void input(int action) {
        if (action == GLFW.GLFW_RELEASE) { pressed = false; blockedUntilRelease = false; return; }
        if (action != GLFW.GLFW_PRESS) return; // OS keyboard repeat is not another physical click.
        Minecraft mc = Minecraft.getInstance();
        pressed = true;
        blockedUntilRelease = !eligible(mc);
        nextRepeat = ticks + SlashRequests.HOLD_INTERVAL;
        if (!blockedUntilRelease) send(mc, false);
    }
    private static void send(Minecraft mc, boolean held) {
        // Flush the native look intent first: a physical click can precede this tick's movement packet.
        mc.player.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
                mc.player.getYRot(), mc.player.getXRot(), mc.player.onGround()));
        PacketDistributor.sendToServer(new SlashRequests.Cast(sequence++, mc.player.level().dimension().location(), held));
    }
    @SubscribeEvent public static void key(InputEvent.Key event) {
        if (Minecraft.getInstance().options.keyAttack.matches(event.getKey(), event.getScanCode())) input(event.getAction());
    }
    @SubscribeEvent public static void mouse(InputEvent.MouseButton.Post event) {
        if (Minecraft.getInstance().options.keyAttack.matchesMouse(event.getButton())) input(event.getAction());
    }
    @SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ticks++;
        if (!mc.options.keyAttack.isDown()) { pressed = false; blockedUntilRelease = false; return; }
        if (!eligible(mc)) { blockedUntilRelease = true; return; }
        if (!pressed || blockedUntilRelease || ticks < nextRepeat) return;
        nextRepeat = ticks + SlashRequests.HOLD_INTERVAL;
        send(mc, true);
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        pressed = false; blockedUntilRelease = false; sequence = 0; ticks = 0; nextRepeat = 0;
    }
}
