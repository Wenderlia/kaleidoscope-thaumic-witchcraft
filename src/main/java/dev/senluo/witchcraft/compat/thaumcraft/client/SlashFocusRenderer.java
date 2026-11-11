package dev.senluo.witchcraft.compat.thaumcraft.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.math.Axis;
import dev.senluo.witchcraft.client.SlashFocusMesh;
import dev.senluo.witchcraft.compat.thaumcraft.SlashFocus;
import dev.tc4port.thaumcraft.api.client.FocusRenderContext;
import dev.tc4port.thaumcraft.api.client.FocusRenderApi;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.resources.ResourceLocation;

/** Public focus-model hand-over, retaining TC's shell geometry, mount, layer order and glint. */
public final class SlashFocusRenderer {
    private static final ResourceLocation SHELL = ResourceLocation.fromNamespaceAndPath("thaumcraft", "textures/models/wand.png");
    private static final ResourceLocation HALLOWEEN = ResourceLocation.fromNamespaceAndPath("thaumcraft", "textures/models/spec_h.png");
    private static MultiBufferSource.BufferSource buffers;
    private static final ModelPart shell = shell();

    private SlashFocusRenderer() {}

    public static void register() {
        // Let TC finish its normal terminal: pose restoration and capture of the
        // actual mount happen after our renderer returns (including custom carriers).
        FocusRenderApi.register(SlashFocus.ID, ignored -> SlashFocusRenderer::render);
    }

    private static ModelPart shell() {
        var mesh = new MeshDefinition();
        mesh.getRoot().addOrReplaceChild("shell", CubeListBuilder.create().texOffs(0, 0)
                .addBox(-3, -6, -3, 6, 6, 6), PartPose.ZERO);
        return LayerDefinition.create(mesh, 32, 32).bakeRoot().getChild("shell");
    }

    private static void render(FocusRenderContext context) {
        if (buffers == null) {
            var fixed = new java.util.LinkedHashMap<RenderType, ByteBufferBuilder>();
            fixed.put(RenderType.glint(), new ByteBufferBuilder(262_144));
            buffers = MultiBufferSource.immediateWithBuffers(fixed, new ByteBufferBuilder(262_144));
        }
        var pose = context.poseStack();
        pose.pushPose();
        try {
            // The API mount points along +Y; the retained mesh/shell use the native -Y model basis.
            pose.mulPose(Axis.XP.rotationDegrees(180));
            SlashFocusMesh.render(pose, buffers.getBuffer(RenderType.entityTranslucent(SlashFocusMesh.TEXTURE)),
                    context.staff(), context.animationTick(), LightTexture.FULL_BRIGHT, context.packedOverlay());
            buffers.endBatch();
            if (context.staff()) { pose.translate(0, -.0475F, 0); pose.scale(.525F, .5525F, .525F); }
            else pose.scale(.5F, .5F, .5F);
            var date = java.time.LocalDate.now();
            var type = RenderType.entityTranslucent(date.getMonthValue() == 10 && date.getDayOfMonth() == 31 ? HALLOWEEN : SHELL);
            var vertices = context.foil().isPresent() ? ItemRenderer.getCompassFoilBuffer(buffers, type, context.foil().get())
                    : buffers.getBuffer(type);
            int tint = context.profile().color();
            float brightness = (205 + (float) Math.sin(context.animationTick() / 3) * 10) / 255F;
            int rgb = ((int) ((tint >> 16 & 255) * brightness) << 16)
                    | ((int) ((tint >> 8 & 255) * brightness) << 8) | (int) ((tint & 255) * brightness);
            shell.render(pose, vertices, LightTexture.FULL_BRIGHT, context.packedOverlay(), 0x99000000 | rgb);
        } finally {
            // Private batches only: never flush the host's unrelated item/entity work.
            buffers.endBatch();
            pose.popPose();
        }
    }
}
