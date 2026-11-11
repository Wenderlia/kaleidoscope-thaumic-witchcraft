package dev.senluo.witchcraft.client;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.brew.FrostSupport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID, value = Dist.CLIENT)
public final class FrostSupportClient {
    private static LocalPlayer previousPlayer;
    private static boolean previousHeld;
    private FrostSupportClient() {}
    private static void update() {
        Minecraft mc = Minecraft.getInstance();
        var player = mc.player;
        boolean down = player != null && mc.screen == null && mc.isWindowActive() && FrostSupport.available(player)
                && mc.options.keyJump.isDown() && mc.options.keyShift.isDown();
        if (player != null) FrostSupport.intent(player, down);
        if (mc.getConnection() != null && (down != previousHeld || player != previousPlayer))
            PacketDistributor.sendToServer(new FrostSupport.Held(down));
        previousHeld = down;
        previousPlayer = player;
    }
    @SubscribeEvent public static void input(MovementInputUpdateEvent event) {
        update();
        // Holding both keys means crouching in place, not repeatedly jumping off the support.
        if (previousHeld && FrostSupport.onStep(event.getEntity())) event.getInput().jumping = false;
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) { update(); }
}
