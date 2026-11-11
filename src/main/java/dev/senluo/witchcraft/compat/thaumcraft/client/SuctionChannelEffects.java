package dev.senluo.witchcraft.compat.thaumcraft.client;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.compat.thaumcraft.SuctionPresentation;
import dev.tc4port.thaumcraft.api.client.ThaumcraftClientEffectsApi;
import dev.tc4port.thaumcraft.api.client.ThaumcraftClientPresentationApi;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** TC's public observer packet feeds a bounded client stream; no fake matrix or copied inventory. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SuctionChannelEffects {
    private SuctionChannelEffects() {}
    static boolean emit(ClientLevel level, Vec3 from, Vec3 to, int phase, int color, float scale) {
        return NativeEffects.emit(level, from, to, phase, color, scale);
    }
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        if (ModList.get().isLoaded("thaumcraft")) event.enqueueWork(NativeEffects::register);
    }
    // Keep optional API method signatures (including compiler-generated lambda methods) off the event subscriber.
    private static final class NativeEffects {
        private static boolean warned;
        private static void register() {
            ThaumcraftClientPresentationApi.register(SuctionPresentation.ID, context -> {
                int color = SuctionPresentation.decodeColor(context.detail(), context.value());
                if (color < 0) return;
                SuctionStreams.accept(context.level(), context.origin(), context.target(), color);
            });
        }
        private static boolean emit(ClientLevel level, Vec3 from, Vec3 to, int phase, int color, float scale) {
            try {
                ThaumcraftClientEffectsApi.spawnEssentiaTrail(level, from, to, phase, color, scale);
                return true;
            } catch (RuntimeException failure) {
                // Only the external cosmetic primitive; do not modify or replay settled transfers.
                if (!warned) {
                    warned = true;
                    org.slf4j.LoggerFactory.getLogger(SuctionChannelEffects.class).warn(
                            "Native essentia trail failed; further visual failures are suppressed.", failure);
                }
                return false;
            }
        }
    }
}
