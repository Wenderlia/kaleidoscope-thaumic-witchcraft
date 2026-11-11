package dev.senluo.witchcraft.mixin.tavern.client;

import com.github.ysbbbbbb.kaleidoscopetavern.blockentity.brew.BarrelBlockEntity;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.github.ysbbbbbb.kaleidoscopetavern.client.render.block.BarrelBlockEntityRender;
import com.github.ysbbbbbb.kaleidoscopetavern.util.RenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.senluo.witchcraft.compat.tavern.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=BarrelBlockEntityRender.class, remap=false)
public abstract class HoneyBarrelRenderMixin {
    @Inject(method="renderFluid(Lcom/github/ysbbbbbb/kaleidoscopetavern/blockentity/brew/BarrelBlockEntity;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at=@At("HEAD"), cancellable=true)
    private void witchcraft$honey(BarrelBlockEntity barrel, PoseStack pose, MultiBufferSource buffer, int light, CallbackInfo ci) {
        int honey = ((WineBarrel)barrel).witchcraft$batch().honey();
        if (honey == 0 || barrel.getFluid().isEmpty()) return;
        var sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(ResourceLocation.withDefaultNamespace("block/water_still"));
        // The mixture lives only in the barrel; no world fluid, bucket, or fluid tick is added.
        int color = 0xFF000000 | ((184 + honey * 12) << 16) | ((184 - honey * 6) << 8) | (124 - honey * 14);
        pose.pushPose(); pose.translate(0, 2, 0);
        RenderUtils.renderSurface(pose, buffer, sprite, color, light, 16, barrel.getFluid().getFluidAmount() / 4000F * .65F);
        pose.popPose(); ci.cancel();
    }
    @WrapOperation(method="renderItems(Lcom/github/ysbbbbbb/kaleidoscopetavern/blockentity/brew/BarrelBlockEntity;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IIF)V", at=@At(value="INVOKE", target="Lcom/github/ysbbbbbb/kaleidoscopetavern/blockentity/brew/BarrelBlockEntity;getIngredient()Lnet/neoforged/neoforge/items/ItemStackHandler;"))
    private ItemStackHandler witchcraft$inputs(BarrelBlockEntity barrel, Operation<ItemStackHandler> original) {
        BarrelBatch batch = ((WineBarrel)barrel).witchcraft$batch();
        return batch.active() ? batch.preview() : original.call(barrel);
    }
}
