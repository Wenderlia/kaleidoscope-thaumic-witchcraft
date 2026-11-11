package dev.senluo.witchcraft.mixin.ritual.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.senluo.witchcraft.client.RitualRenderTypes;
import dev.senluo.witchcraft.ritual.RitualView;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(EntityRenderDispatcher.class)
public abstract class FrozenModelMixin {
    @WrapMethod(method = "render")
    private void witchcraft$grayModel(Entity entity, double x, double y, double z, float yaw, float partialTick, PoseStack pose,
            MultiBufferSource buffers, int light, Operation<Void> original) {
        RitualView.State view = RitualView.get(entity);
        original.call(entity, x, y, z, yaw, view == null ? partialTick : 0.0F, pose,
                view == null ? buffers : RitualRenderTypes.wrap(buffers, view.gray()), light);
    }
}
