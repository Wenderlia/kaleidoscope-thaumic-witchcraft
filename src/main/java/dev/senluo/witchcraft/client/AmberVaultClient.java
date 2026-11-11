package dev.senluo.witchcraft.client;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.brew.AmberVaults;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid=KaleidoscopeWitchcraft.MOD_ID,value=Dist.CLIENT)
public final class AmberVaultClient {
    private static boolean held;
    private static int ticks;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        Minecraft mc=Minecraft.getInstance();
        boolean down=mc.player!=null && mc.screen==null && AmberVaults.ready(mc.player) && mc.player.isShiftKeyDown()
                && mc.player.getMainHandItem().isEmpty() && mc.player.getOffhandItem().isEmpty() && mc.options.keyUse.isDown();
        if(mc.getConnection()!=null && (down!=held || down && ++ticks>=10)){
            PacketDistributor.sendToServer(new AmberVaults.Held(down));ticks=0;
        }
        held=down;
    }
    private AmberVaultClient(){}
}
