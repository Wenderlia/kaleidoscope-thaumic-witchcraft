package dev.senluo.witchcraft.client;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.vishroom.VishroomContent;
import dev.senluo.witchcraft.vishroom.VishroomPartBlock;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class VishroomModels {
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ItemProperties.register(VishroomContent.PART_ITEM.get(),
                ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID, "vishroom_part"),
                (stack, level, entity, seed) -> VishroomPartBlock.itemVariant(stack)));
    }
}
