package dev.senluo.witchcraft.client;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.brew.AmberFerments;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;

@EventBusSubscriber(modid=KaleidoscopeWitchcraft.MOD_ID, bus=EventBusSubscriber.Bus.MOD, value=Dist.CLIENT)
public final class AmberPulpTextures {
    @SubscribeEvent
    public static void register(RegisterClientExtensionsEvent event) {
        for (var entry : AmberFerments.ENTRIES) {
            var still=ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID,"block/"+entry.vintage.pulp+"_still");
            var flowing=ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID,"block/"+entry.vintage.pulp+"_flow");
            event.registerFluidType(new IClientFluidTypeExtensions() {
                @Override public ResourceLocation getStillTexture() { return still; }
                @Override public ResourceLocation getFlowingTexture() { return flowing; }
            }, entry.type.get());
        }
    }
    private AmberPulpTextures() {}
}
