package dev.senluo.witchcraft.client;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.time.FruitItem;
import dev.senluo.witchcraft.time.TemporalContent;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class FruitModels {
    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ItemProperties.register(TemporalContent.FRUIT_ITEM.get(),
                ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID,"fruit_quality"),
                // Preserve the existing I-IV thresholds; V extends the predicate to 1.25.
                (stack,level,entity,seed) -> FruitItem.grade(stack) / 4.0F));
    }
}
