package dev.senluo.witchcraft.compat.thaumcraft;

import dev.tc4port.thaumcraft.api.MagicAttributes;
import dev.tc4port.thaumcraft.api.ThaumcraftApiHelper;
import dev.tc4port.thaumcraft.api.aspect.*;
import dev.tc4port.thaumcraft.api.entity.AttributeTypes;
import dev.tc4port.thaumcraft.api.focus.FocusApi;
import dev.tc4port.thaumcraft.api.focus.FocusCostApi;
import dev.tc4port.thaumcraft.api.focus.FocusCostQuote;
import dev.tc4port.thaumcraft.api.wand.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;

/** Native 20744 quote/payment bridge; power projects increases without undoing native discounts. */
final class SlashVis {
    private SlashVis() {}

    private static VisCost base(ItemStack wand) {
        // Presentation and payment read one synchronized catalog, including upgrade-sensitive costs.
        return FocusApi.installedState(wand).filter(s -> s.focusId().equals(SlashFocus.ID))
                .flatMap(s -> FocusApi.registry().profile(s.focusId()).flatMap(p -> p.fixedCost(s.upgradeLevels())))
                .filter(cost -> cost.amounts().getOrDefault(VisChannel.PERDITIO, 0) > 0).orElse(null);
    }

    static boolean pay(ItemStack wand, ServerPlayer player, VisAction action) {
        return pay(wand, player, action, 1);
    }

    static boolean pay(ItemStack wand, ServerPlayer player, VisAction action, double scale) {
        VisCost cost = base(wand);
        if (cost == null || !Double.isFinite(scale) || scale <= 0
                || scale > dev.senluo.witchcraft.slash.drawing.StrokeTrace.MAX_MULTIPLIER) return false;
        if (scale != 1) {
            java.util.Map<VisChannel, Integer> amounts = new java.util.EnumMap<>(VisChannel.class);
            for (var entry : cost.amounts().entrySet()) {
                double scaled = entry.getValue() * scale;
                if (scaled > Integer.MAX_VALUE) return false;
                // Minimum native centivis prevents a nonzero drawn cast becoming free by quantization.
                amounts.put(entry.getKey(), Math.max(1, (int) Math.ceil(scaled)));
            }
            cost = VisCost.ofCentivis(amounts);
        }
        var result = ThaumcraftApiHelper.consumeVisFromWand(wand, player, cost, action, VisConsumptionContext.CASTING);
        return action == VisAction.EXECUTE ? result.consumed() : result.affordable();
    }

    static double potential(ItemStack wand, ServerPlayer player, WandView view) {
        VisCost cost = base(wand);
        if (cost == null) return Double.NaN;
        var quote = FocusCostApi.quote(wand, player, cost, FocusCostQuote.Cadence.PER_PAYMENT, 0);
        var modifier = quote.modifiers().get(VisChannel.PERDITIO);
        if (modifier == null) return Double.NaN;
        double rate = 1;
        for (var term : modifier.terms()) {
            switch (term.kind()) {
                case CAP -> rate *= Math.max(1, term.amount());
                case CARRIER_DIVISOR -> rate /= Math.min(1, term.amount());
                case EXHAUSTION -> rate += Math.max(0, term.amount());
                default -> { /* Discounts never reduce power. Attribute terms are projected below. */ }
            }
        }
        double result = SlashEfficacy.chaosUsage(wand) * rate * positiveAttribute(player)
                * dev.senluo.witchcraft.slash.SlashPotential.PER_CHAOS_VIS;
        return Double.isFinite(result) && result > 0 && result <= 1_000_000 ? result : Double.NaN;
    }

    // The public quote exposes the net ATTRIBUTE factor, not individual signed modifiers.
    // Resolve the public attribute to retain independent penalties even when discounts offset them.
    private static double positiveAttribute(ServerPlayer player) {
        var attribute = AttributeTypes.resolve(player, MagicAttributes.WAND_VIS_COST_MULTIPLIER);
        if (attribute == null) return 1;
        var modifiers = attribute.getModifiers();
        double base = Math.max(1, attribute.getBaseValue());
        for (var modifier : modifiers)
            if (modifier.operation() == AttributeModifier.Operation.ADD_VALUE && modifier.amount() > 0)
                base += modifier.amount();
        double value = base;
        for (var modifier : modifiers)
            if (modifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_BASE && modifier.amount() > 0)
                value += base * modifier.amount();
        for (var modifier : modifiers)
            if (modifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL && modifier.amount() > 0)
                value *= 1 + modifier.amount();
        // Same attribute clamp as the native evaluator, after filtering reductions rather than before.
        return attribute.getAttribute().value().sanitizeValue(value);
    }
}
