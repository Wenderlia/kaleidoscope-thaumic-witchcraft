package dev.senluo.witchcraft.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.brew.FrostPath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import org.joml.Matrix4f;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** One two-sided plane per visible bridge, in the native final world pass. */
@EventBusSubscriber(modid=KaleidoscopeWitchcraft.MOD_ID,value=Dist.CLIENT)
public final class FrostPathRenderer implements BlockEntityRenderer<FrostPath.Expiry> {
    // Native no-outline preserves alpha below 0.1, depth-tests but only writes color.
    private static final RenderType MATERIAL=RenderType.entityNoOutline(ResourceLocation.fromNamespaceAndPath(
            KaleidoscopeWitchcraft.MOD_ID,"textures/block/frost_light.png"));
    private static final List<FrostPath.Expiry> VISIBLE=new ArrayList<>();
    @EventBusSubscriber(modid=KaleidoscopeWitchcraft.MOD_ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
    public static final class Registration {
        @SubscribeEvent public static void register(EntityRenderersEvent.RegisterRenderers e){
            e.registerBlockEntityRenderer(FrostPath.BE.get(),context->new FrostPathRenderer());
        }
    }
    @SubscribeEvent public static void frame(RenderFrameEvent.Pre event){VISIBLE.clear();}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){VISIBLE.clear();}
    @Override public void render(FrostPath.Expiry block,float partial,PoseStack poses,MultiBufferSource buffers,int light,int overlay){VISIBLE.add(block);}
    @SubscribeEvent public static void render(RenderLevelStageEvent event){
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_LEVEL || VISIBLE.isEmpty())return;
        Minecraft mc=Minecraft.getInstance();Vec3 camera=event.getCamera().getPosition();
        VISIBLE.removeIf(b->b.isRemoved() || b.getLevel()!=mc.level);
        VISIBLE.sort(Comparator.comparingDouble((FrostPath.Expiry b)->b.getBlockPos().distToCenterSqr(camera)).reversed());
        var modelView=RenderSystem.getModelViewStack();var projection=new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting=RenderSystem.getVertexSorting();var shader=RenderSystem.getShader();var color=RenderSystem.getShaderColor().clone();
        int t0=RenderSystem.getShaderTexture(0),t1=RenderSystem.getShaderTexture(1),t2=RenderSystem.getShaderTexture(2);
        modelView.pushMatrix();
        try{
            // AFTER_LEVEL has restored the hand view; use this frame's world matrices.
            modelView.set(event.getModelViewMatrix());RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(event.getProjectionMatrix(),VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.setShaderColor(1,1,1,1);
            var buffers=mc.renderBuffers().bufferSource();VertexConsumer v=buffers.getBuffer(MATERIAL);
            for(var block:VISIBLE){
                var p=block.getBlockPos();float x=(float)(p.getX()-camera.x),y=(float)(p.getY()+1-camera.y),z=(float)(p.getZ()-camera.z);
                float alpha=(float)Math.clamp((64-Math.sqrt(p.distToCenterSqr(camera)))/8,0,1);
                vertex(v,x,y,z,0,0,alpha);vertex(v,x,y,z+1,0,1,alpha);
                vertex(v,x+1,y,z+1,1,1,alpha);vertex(v,x+1,y,z,1,0,alpha);
            }
            buffers.endBatch(MATERIAL);
        }finally{
            MATERIAL.clearRenderState();VISIBLE.clear();
            modelView.popMatrix();RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection,sorting);RenderSystem.setShader(()->shader);
            RenderSystem.setShaderColor(color[0],color[1],color[2],color[3]);
            RenderSystem.setShaderTexture(0,t0);RenderSystem.setShaderTexture(1,t1);RenderSystem.setShaderTexture(2,t2);
        }
    }
    private static void vertex(VertexConsumer v,float x,float y,float z,float u,float vv,float alpha){
        v.addVertex(x,y,z).setColor(1F,1F,1F,alpha).setUv(u,vv).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT).setNormal(0,1,0);
    }
}
