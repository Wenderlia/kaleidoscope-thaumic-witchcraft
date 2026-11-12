package dev.senluo.witchcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.reservoir.*;
import net.minecraft.client.*;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.extensions.common.*;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid=KaleidoscopeWitchcraft.MOD_ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class ReservoirClient {
    @SubscribeEvent public static void setup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event){
        if(net.neoforged.fml.ModList.get().isLoaded("ftblibrary"))
            event.enqueueWork(dev.senluo.witchcraft.compat.ftb.ReservoirSidebar::register);
    }
    private static final KeyMapping OPEN=new KeyMapping("key.kaleidoscope_witchcraft.open_reservoir",GLFW.GLFW_KEY_B,"key.categories.kaleidoscope_witchcraft");
    @SubscribeEvent public static void keys(RegisterKeyMappingsEvent e){e.register(OPEN);}
    @SubscribeEvent public static void screens(RegisterMenuScreensEvent e){e.register(ReservoirContent.MENU.get(),ReservoirScreen::new);e.register(ReservoirContent.SILVER_MENU.get(),ReservoirScreen::new);}
    @SubscribeEvent public static void reload(RegisterClientReloadListenersEvent e){e.registerReloadListener((ResourceManagerReloadListener)ReservoirMesh::reload);}
    @SubscribeEvent public static void renders(EntityRenderersEvent.RegisterRenderers e){e.registerBlockEntityRenderer(ReservoirContent.ENTITY.get(),context->new ReservoirRenderer());}
    @SubscribeEvent public static void extensions(RegisterClientExtensionsEvent e){
        e.registerItem(new IClientItemExtensions(){
            private ReservoirRenderer renderer;
            public net.minecraft.client.model.HumanoidModel.ArmPose getArmPose(net.minecraft.world.entity.LivingEntity entity,net.minecraft.world.InteractionHand hand,net.minecraft.world.item.ItemStack stack){
                // A carry handle hangs from a relaxed arm rather than the usual raised item pose.
                return ReservoirContent.isBackpack(stack)?net.minecraft.client.model.HumanoidModel.ArmPose.EMPTY:null;
            }
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer(){if(renderer==null)renderer=new ReservoirRenderer();return renderer;}
        },ReservoirContent.BACKPACK.get(),ReservoirContent.SILVER_BACKPACK.get(),ReservoirContent.CONVERTER.get(),ReservoirContent.VOID_CONVERTER.get(),ReservoirContent.FROST_MODULE.get());
    }
    @SubscribeEvent public static void layers(EntityRenderersEvent.AddLayers e){
        for(var skin:e.getSkins()){
            net.minecraft.client.renderer.entity.player.PlayerRenderer renderer=e.getSkin(skin);
            if(renderer!=null)renderer.addLayer(new BackLayer(renderer));
        }
    }
    private static final class BackLayer extends RenderLayer<AbstractClientPlayer,PlayerModel<AbstractClientPlayer>> {
        BackLayer(RenderLayerParent<AbstractClientPlayer,PlayerModel<AbstractClientPlayer>> parent){super(parent);}
        public void render(PoseStack poses,MultiBufferSource buffers,int light,AbstractClientPlayer player,float swing,float amount,float partial,float age,float yaw,float pitch){
            var bag=ReservoirEquipment.worn(player);if(bag.isEmpty() || player.isInvisible())return;
            poses.pushPose();
            try{
                getParentModel().body.translateAndRotate(poses);
                poses.translate(0,.73,.46);poses.mulPose(Axis.XP.rotationDegrees(180));
                ReservoirRenderer.backpack(bag,poses,buffers,light,OverlayTexture.NO_OVERLAY);
            }finally{poses.popPose();}
        }
    }
    @EventBusSubscriber(modid=KaleidoscopeWitchcraft.MOD_ID,value=Dist.CLIENT)
    public static final class Input {
        @SubscribeEvent public static void tick(ClientTickEvent.Post e){
            var mc=Minecraft.getInstance();
            while(OPEN.consumeClick())if(mc.player!=null && mc.screen==null && !ReservoirEquipment.accessible(mc.player).isEmpty())PacketDistributor.sendToServer(new ReservoirRuntime.Open());
        }
    }
    private ReservoirClient(){}
}
