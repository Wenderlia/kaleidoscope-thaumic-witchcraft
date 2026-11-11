package dev.senluo.witchcraft.brew;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

/** Quote only our additive contribution; the native attribute still applies modifiers and clamps. */
final class StepHeightFloor {
    static void apply(AttributeInstance attribute, ResourceLocation id, double floor) {
        if (attribute == null) return;
        var previous = attribute.getModifier(id);
        double amount = 0;
        if (floor > 0) {
            double value = attribute.getBaseValue(), baseMultiplier = 1, totalMultiplier = 1;
            for (var modifier : attribute.getModifiers()) {
                if (modifier.id().equals(id)) continue;
                switch (modifier.operation()) {
                    case ADD_VALUE -> value += modifier.amount();
                    case ADD_MULTIPLIED_BASE -> baseMultiplier += modifier.amount();
                    case ADD_MULTIPLIED_TOTAL -> totalMultiplier *= 1 + modifier.amount();
                }
            }
            // Native ADD_MULTIPLIED_BASE also scales ADD_VALUE, not just the original base.
            double scale = baseMultiplier * totalMultiplier;
            if (scale > 0 && Double.isFinite(scale)) amount = Math.max(0, floor / scale - value);
        }
        if (previous == null && amount == 0 || previous != null && Math.abs(previous.amount() - amount) < 1e-8) return;
        attribute.removeModifier(id);
        if (amount > 0) attribute.addTransientModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_VALUE));
    }
    private StepHeightFloor() {}
}
