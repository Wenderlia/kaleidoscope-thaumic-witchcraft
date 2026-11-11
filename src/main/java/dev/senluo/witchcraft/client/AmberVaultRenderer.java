package dev.senluo.witchcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.brew.AmberVaultEntity;
import dev.senluo.witchcraft.brew.AmberVaults;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid=KaleidoscopeWitchcraft.MOD_ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class AmberVaultRenderer extends EntityRenderer<AmberVaultEntity> {
    private final net.minecraft.client.renderer.block.BlockRenderDispatcher blocks;
    public AmberVaultRenderer(EntityRendererProvider.Context context){super(context);blocks=context.getBlockRenderDispatcher();}
    @SubscribeEvent public static void register(EntityRenderersEvent.RegisterRenderers event){event.registerEntityRenderer(AmberVaults.ENTITY.get(),AmberVaultRenderer::new);}
    @Override public ResourceLocation getTextureLocation(AmberVaultEntity entity){return TextureAtlas.LOCATION_BLOCKS;}
    @Override public void render(AmberVaultEntity entity,float yaw,float partial,PoseStack pose,MultiBufferSource buffer,int light){
        pose.pushPose();pose.translate(-.5,0,-.5);
        blocks.renderSingleBlock(AmberVaults.BLOCK.get().defaultBlockState(),pose,buffer,
                entity.bright()?net.minecraft.client.renderer.LightTexture.FULL_BRIGHT:light,OverlayTexture.NO_OVERLAY);
        pose.popPose();super.render(entity,yaw,partial,pose,buffer,light);
    }
}
