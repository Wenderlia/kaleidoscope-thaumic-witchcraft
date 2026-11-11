package dev.senluo.witchcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.time.FruitBlock;
import dev.senluo.witchcraft.time.FruitEntity;
import dev.senluo.witchcraft.time.TemporalContent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Grade V alone: one analytic mote per eight seconds, no particle allocation or server tick. */
@EventBusSubscriber(modid=KaleidoscopeWitchcraft.MOD_ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class FruitRenderer implements BlockEntityRenderer<FruitEntity> {
    private static final RenderType DUST = RenderType.entityTranslucentEmissive(
            ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID,"textures/particle/amber_spark.png"),false);
    private final EntityRenderDispatcher entities;
    public FruitRenderer(BlockEntityRendererProvider.Context context) { entities=context.getEntityRenderer(); }
    @SubscribeEvent public static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(TemporalContent.FRUIT_ENTITY.get(),FruitRenderer::new);
    }
    @Override public void render(FruitEntity fruit,float partialTick,PoseStack poses,MultiBufferSource buffers,int light,int overlay) {
        if (fruit.getLevel()==null || fruit.getBlockState().getValue(FruitBlock.GRADE)!=5
                || Minecraft.getInstance().options.particles().get()==ParticleStatus.MINIMAL) return;
        double distance=fruit.getBlockPos().distToCenterSqr(entities.camera.getPosition());
        if (distance>=24*24) return;
        long phase=Math.floorMod(fruit.getLevel().getGameTime()+Math.floorMod(fruit.getBlockPos().asLong(),160L),160L);
        double age=(phase+partialTick)/60.0;
        if (age>=1) return;
        float fade=(float)Math.min(1,Math.max(0,(24-Math.sqrt(distance))/8));
        float alpha=(float)Math.sin(age*Math.PI)*.48F*fade;
        if (alpha<.01F) return;
        poses.pushPose();
        try {
            // Front window, followed by an upward two-block drift over three seconds.
            poses.translate(.5+Math.sin(age*Math.PI)*.035,.48+age*2,.64);
            poses.mulPose(entities.cameraOrientation());
            var pose=poses.last();var vertices=buffers.getBuffer(DUST);
            float half=.018F;
            vertex(vertices,pose,-half,-half,0,1,alpha);
            vertex(vertices,pose,half,-half,1,1,alpha);
            vertex(vertices,pose,half,half,1,0,alpha);
            vertex(vertices,pose,-half,half,0,0,alpha);
        } finally { poses.popPose(); }
    }
    private static void vertex(VertexConsumer v,PoseStack.Pose p,float x,float y,float u,float w,float a) {
        v.addVertex(p,x,y,0).setColor(1F,.89F,.65F,a).setUv(u,w)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(p,0,0,1);
    }
    @Override public AABB getRenderBoundingBox(FruitEntity fruit) {
        return new AABB(fruit.getBlockPos()).expandTowards(0,2,0);
    }
    @Override public int getViewDistance() { return 24; }
}
