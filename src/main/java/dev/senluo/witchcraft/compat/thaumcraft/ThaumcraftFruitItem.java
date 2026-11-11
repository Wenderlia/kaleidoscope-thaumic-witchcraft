package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.time.FruitItem;
import dev.tc4port.thaumcraft.api.aspect.AspectAmounts;
import dev.tc4port.thaumcraft.api.aspect.AspectId;
import dev.tc4port.thaumcraft.api.aspect.AspectItemView;
import java.util.LinkedHashMap;
import net.minecraft.world.item.ItemStack;

/** Grade-owned aspects through TC's item SPI; loaded only when TC is present. */
public final class ThaumcraftFruitItem extends FruitItem implements AspectItemView {
    private static final AspectAmounts[] GRADES = new AspectAmounts[6];
    static {
        for (int grade = 1; grade <= 5; grade++) {
            var aspects = new LinkedHashMap<AspectId, Integer>();
            aspects.put(AspectId.parse("praecantatio"), 2 * grade);
            aspects.put(AspectId.parse("vacuos"), 2 * grade);
            aspects.put(AspectId.parse("motus"), grade);
            aspects.put(AspectId.parse("herba"), grade);
            aspects.put(AspectId.parse("fames"), 2 * grade);
            aspects.put(AspectId.parse("lucrum"), 2 * grade);
            GRADES[grade] = new AspectAmounts(aspects);
        }
    }

    // Expose only the common base type to the unconditional item registry's verifier.
    public static FruitItem create(Properties properties) { return new ThaumcraftFruitItem(properties); }
    private ThaumcraftFruitItem(Properties properties) { super(properties); }

    @Override public AspectAmounts storedAspectData(ItemStack stack) {
        return GRADES[Math.clamp(FruitItem.grade(stack), 1, 5)];
    }
}
