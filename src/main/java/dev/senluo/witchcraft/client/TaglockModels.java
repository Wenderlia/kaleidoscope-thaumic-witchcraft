package dev.senluo.witchcraft.client;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.ritual.RitualContent;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class TaglockModels {
    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> dev.senluo.witchcraft.ritual.GeasRecipePreview.viewer(
                () -> net.minecraft.client.Minecraft.getInstance().player));
        event.enqueueWork(() -> ItemProperties.register(RitualContent.TAGLOCK.get(),
                ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID, "taglock_bound"),
                (stack, level, entity, seed) -> stack.has(RitualContent.TAGLOCK_SAMPLE.get()) ? 1.0F : 0.0F));
    }

    private TaglockModels() {}
}
