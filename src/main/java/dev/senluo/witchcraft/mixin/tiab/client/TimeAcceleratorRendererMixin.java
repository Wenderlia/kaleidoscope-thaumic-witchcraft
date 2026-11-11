package dev.senluo.witchcraft.mixin.tiab.client;

import com.github.ysbbbbbb.kaleidoscopetavern.block.brew.BarrelBlock;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.mangorage.tiab.common.client.renderer.BlockFaceTextRenderer;
import org.mangorage.tiab.common.client.renderer.TimeAcceleratorEntityRenderer;
import org.mangorage.tiab.common.entities.TimeAcceleratorEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

@Mixin(value = TimeAcceleratorEntityRenderer.class, remap = false)
public abstract class TimeAcceleratorRendererMixin {
    @WrapOperation(
            method = "render(Lorg/mangorage/tiab/common/entities/TimeAcceleratorEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lorg/mangorage/tiab/common/client/renderer/BlockFaceTextRenderer$IDrawText;render(Ljava/util/List;Ljava/lang/String;IIFFF)V"),
            require = 2, allow = 2)
    private void senluo$drawOnBarrelSurface(BlockFaceTextRenderer.IDrawText renderer,
                                          List<BlockFaceTextRenderer.Face> faces, String text,
                                          int light, int color, float x, float y, float offset,
                                          Operation<Void> original,
                                          TimeAcceleratorEntity entity, float yaw, float partialTick,
                                          PoseStack poses, MultiBufferSource buffers, int packedLight) {
        // The server-only target field is not synchronized; the entity position is.
        BlockPos pos = entity.blockPosition();
        BlockState state = entity.level().getBlockState(pos);
        boolean barrel = state.getBlock() instanceof BarrelBlock
                && BarrelBlock.getOriginPos(pos, state).equals(pos);
        if (!barrel) {
            original.call(renderer, faces, text, light, color, x, y, offset);
            return;
        }

        // The controller is inside the barrel. Move TIAB's unit-cube text to its outer faces.
        poses.pushPose();
        try {
            poses.scale(3.0F, 3.0F, 3.0F);
            original.call(renderer, faces, text, light, color, x, y, offset);
        } finally {
            poses.popPose();
        }
    }
}
