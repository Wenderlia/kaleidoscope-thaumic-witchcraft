package dev.senluo.witchcraft.mixin.curios.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Curios 9.5.1 omits the stack when entering NeoForge's tooltip render pipeline. */
@Pseudo
@Mixin(targets = "top.theillusivec4.curios.client.gui.CuriosScreen", remap = false)
public abstract class CuriosTooltipContextMixin {
    // Match only the legacy overload. Once upstream passes the stack itself, this is a no-op.
    @WrapOperation(method = "renderTooltip(Lnet/minecraft/client/gui/GuiGraphics;II)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;renderTooltip("
                    + "Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;II)V"),
            require = 0, allow = 1)
    private void witchcraft$withTooltipStack(GuiGraphics graphics, Font font, List<Component> text,
                                           Optional<TooltipComponent> image, int x, int y,
                                           Operation<Void> original, @Local ItemStack displayedStack) {
        // Capture Curios' actual display stack, after its slot extension has resolved it.
        // Preserve the wrapped invocation, other mods' hooks, and any surrounding tooltip scope.
        TooltipStackAccess context = (TooltipStackAccess) graphics;
        ItemStack previous = context.witchcraft$getTooltipStack();
        context.witchcraft$setTooltipStack(displayedStack);
        try {
            original.call(graphics, font, text, image, x, y);
        } finally {
            context.witchcraft$setTooltipStack(previous);
        }
    }
}
