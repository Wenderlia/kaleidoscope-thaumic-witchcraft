package dev.senluo.witchcraft.compat.quark;

import com.mojang.datafixers.util.Either;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;

/** Correct Quark's declared row width while retaining its component, factory and renderer. */
public final class QuarkFoodTooltip {
    private static final String COMPONENT = "org.violetmoon.quark.content.client.tooltip.FoodTooltips$FoodComponent";
    private static Access access;
    private static boolean unavailable;

    private QuarkFoodTooltip() {}

    /** Called by our LOWEST-priority gather hook after Quark's NORMAL-priority insertion. */
    public static void fit(RenderTooltipEvent.GatherComponents event) {
        if (unavailable || !event.getItemStack().has(DataComponents.FOOD)) return;
        var elements = event.getTooltipElements();
        for (int i = 0; i < elements.size(); i++) {
            var component = elements.get(i).right().orElse(null);
            if (!(component instanceof ClientTooltipComponent renderer)
                    || !component.getClass().getName().equals(COMPONENT)) continue;
            try {
                if (access == null) access = Access.resolve(component.getClass());
                ItemStack stack = (ItemStack) access.stack().invoke(component);
                var minecraft = Minecraft.getInstance();
                var food = stack.getItem().getFoodProperties(stack, minecraft.player);
                int divisor = access.divisor().getInt(null);
                if (divisor <= 0) return;
                int nutrition = food == null ? 0 : food.nutrition();
                int count = (int) Math.ceil((double) nutrition / divisor);
                int width = count * 9;
                if (nutrition != 0 && count > access.threshold().getInt(null)) {
                    boolean half = nutrition % 2 != 0;
                    String multiplier = "x" + (count - (half ? 1 : 0)) + (half ? ".5" : "");
                    // Quark draws this label at x + 10 using Minecraft.font, with a one-pixel shadow.
                    width = 11 + minecraft.font.width(multiplier);
                }
                if (width != renderer.getWidth(minecraft.font)) {
                    elements.set(i, Either.right(access.constructor().newInstance(stack, width, renderer.getHeight())));
                }
            } catch (ReflectiveOperationException | LinkageError unsupported) {
                unavailable = true;
                org.slf4j.LoggerFactory.getLogger(QuarkFoodTooltip.class).warn(
                        "Quark food tooltip contract unavailable; retaining the original component: {}", unsupported.toString());
                return;
            }
        }
    }

    /** Resolve public members once; read live configuration and font metrics for each tooltip. */
    private record Access(Constructor<? extends TooltipComponent> constructor, Method stack, Field divisor, Field threshold) {
        static Access resolve(Class<? extends TooltipComponent> type) throws ReflectiveOperationException {
            Class<?> module = Class.forName("org.violetmoon.quark.content.client.module.ImprovedTooltipsModule",
                    false, type.getClassLoader());
            Method stack = type.getMethod("stack");
            Field divisor = module.getField("foodDivisor"), threshold = module.getField("foodCompressionThreshold");
            if (stack.getReturnType() != ItemStack.class) throw new NoSuchMethodException("FoodComponent.stack");
            if (divisor.getType() != int.class || threshold.getType() != int.class)
                throw new NoSuchFieldException("Quark food layout settings");
            return new Access(type.getConstructor(ItemStack.class, int.class, int.class), stack, divisor, threshold);
        }
    }
}
