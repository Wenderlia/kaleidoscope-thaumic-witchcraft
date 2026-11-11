package dev.senluo.witchcraft.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.slf4j.LoggerFactory;

/** Private late-world buffers. Direct passes preserve each material's blending;
 * composited passes own a colour target. Neither flushes the world's shared buffers. */
final class WorldEffectPass {
    enum Composite {
        DIRECT(null, null, null),
        SCREEN(GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR, GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE),
        PREMULTIPLIED_ALPHA(GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);

        private final GlStateManager.DestFactor dstColor;
        private final GlStateManager.SourceFactor srcAlpha;
        private final GlStateManager.DestFactor dstAlpha;

        Composite(GlStateManager.DestFactor dstColor, GlStateManager.SourceFactor srcAlpha,
                GlStateManager.DestFactor dstAlpha) {
            this.dstColor = dstColor;
            this.srcAlpha = srcAlpha;
            this.dstAlpha = dstAlpha;
        }

        void apply() {
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, dstColor, srcAlpha, dstAlpha);
        }
    }

    private final Composite composite;
    private final String name;
    private final RenderType[] materials;
    private TextureTarget target;
    private ByteBufferBuilder storage;
    private MultiBufferSource.BufferSource buffers;
    private boolean failed;

    WorldEffectPass(String name, Composite composite, RenderType... materials) {
        this.name = name;
        this.composite = composite;
        this.materials = materials.clone();
    }

    void render(RenderLevelStageEvent event, Consumer<MultiBufferSource.BufferSource> draw) {
        if(failed) return;
        RenderTarget main=Minecraft.getInstance().getMainRenderTarget();
        if(!main.useDepth || main.width<=0 || main.height<=0) return;
        try(WorldState state=new WorldState(event,main)) {
            try {
                prepare(main);
                if(composite==Composite.DIRECT)main.bindWrite(true);
                else {
                    target.clear(Minecraft.ON_OSX);
                    target.copyDepthFrom(main);
                    target.bindWrite(true);
                }
                draw.accept(buffers);
                buffers.endBatch();
                if(composite!=Composite.DIRECT) {
                    main.bindWrite(true);
                    RenderSystem.enableBlend();
                    composite.apply();
                    target.blitToScreen(main.viewWidth,main.viewHeight,false);
                }
            } catch(RuntimeException error) {
                failed=true;
                destroy();
                LoggerFactory.getLogger(KaleidoscopeWitchcraft.MOD_ID).warn(
                        "Unable to render {}; retrying after resource reload",name,error);
            }
        }
    }

    private void prepare(RenderTarget main) {
        if(storage==null) {
            storage=new ByteBufferBuilder(1<<21);
            buffers=MultiBufferSource.immediate(storage);
        }
        if(composite==Composite.DIRECT)return;
        if(target!=null && (target.width!=main.width || target.height!=main.height
                || target.isStencilEnabled()!=main.isStencilEnabled())) {
            target.destroyBuffers(); target=null;
        }
        if(target==null) {
            target=new TextureTarget(main.width,main.height,true,Minecraft.ON_OSX);
            if(main.isStencilEnabled()) target.enableStencil();
            target.setClearColor(0,0,0,0);
        }
    }

    void release() {
        if(!RenderSystem.isOnRenderThread()) { RenderSystem.recordRenderCall(this::release); return; }
        if(target!=null || storage!=null) {
            try { destroy(); } finally { Minecraft.getInstance().getMainRenderTarget().bindWrite(true); }
        }
        failed=false;
    }
    private void destroy() {
        TextureTarget old=target; target=null;
        try { if(old!=null) old.destroyBuffers(); }
        finally { if(storage!=null) { storage.close(); storage=null; buffers=null; } }
    }

    private final class WorldState implements AutoCloseable {
        private final RenderTarget main;
        private final Matrix4f projection=new Matrix4f(RenderSystem.getProjectionMatrix());
        private final Matrix4f texture=new Matrix4f(RenderSystem.getTextureMatrix());
        private final VertexSorting sorting=RenderSystem.getVertexSorting();
        private final ShaderInstance shader=RenderSystem.getShader();
        private final float[] color=RenderSystem.getShaderColor().clone();
        private final int texture0=RenderSystem.getShaderTexture(0),texture1=RenderSystem.getShaderTexture(1);
        private final Matrix4fStack modelView=RenderSystem.getModelViewStack();
        WorldState(RenderLevelStageEvent event,RenderTarget main) {
            this.main=main;
            modelView.pushMatrix(); modelView.set(event.getModelViewMatrix());
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(event.getProjectionMatrix(),VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.resetTextureMatrix(); RenderSystem.setShaderColor(1,1,1,1);
            RenderSystem.colorMask(true,true,true,true); RenderSystem.depthMask(true); RenderSystem.enableDepthTest();
        }
        @Override public void close() {
            try { for(RenderType type:materials) type.clearRenderState(); }
            finally {
                main.bindWrite(true); modelView.popMatrix(); RenderSystem.applyModelViewMatrix();
                RenderSystem.setProjectionMatrix(projection,sorting); RenderSystem.setTextureMatrix(texture);
                RenderSystem.setShaderColor(color[0],color[1],color[2],color[3]);
                RenderSystem.setShaderTexture(0,texture0); RenderSystem.setShaderTexture(1,texture1);
                RenderSystem.setShader(()->shader);
                RenderSystem.defaultBlendFunc(); RenderSystem.disableBlend();
                RenderSystem.colorMask(true,true,true,true); RenderSystem.depthMask(true);
                RenderSystem.enableDepthTest(); RenderSystem.enableCull();
            }
        }
    }
}
