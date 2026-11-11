package dev.senluo.witchcraft.mixin.curios.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Access only; does not intercept any ordinary tooltip rendering. */
@Mixin(GuiGraphics.class)
public interface TooltipStackAccess {
    @Accessor("tooltipStack")
    ItemStack witchcraft$getTooltipStack();

    @Accessor("tooltipStack")
    void witchcraft$setTooltipStack(ItemStack stack);
}
