package dev.senluo.witchcraft.mixin.accessory.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.senluo.witchcraft.client.BarrierModelCapture;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Capture the already posed main body. Layers, labels, shadows and equipment are excluded. */
@Mixin(LivingEntityRenderer.class)
public abstract class BarrierModelMixin {
    @WrapOperation(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"))
    private void witchcraft$captureOutline(EntityModel<?> model, PoseStack pose, VertexConsumer vertices,
            int light, int overlay, int color, Operation<Void> original, @Local(argsOnly = true) LivingEntity entity,
            @Local RenderType material) {
        BarrierModelCapture capture = material.mode() == VertexFormat.Mode.QUADS ? BarrierModelCapture.begin(entity, vertices) : null;
        if (capture == null) {
            original.call(model, pose, vertices, light, overlay, color);
            return;
        }
        boolean successful = false;
        try {
            original.call(model, pose, capture, light, overlay, color);
            successful = true;
        } finally {
            capture.end(successful);
        }
    }
}
