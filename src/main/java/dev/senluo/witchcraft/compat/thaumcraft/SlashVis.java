package dev.senluo.witchcraft.compat.thaumcraft;

import dev.tc4port.thaumcraft.api.MagicAttributes;
import dev.tc4port.thaumcraft.api.aspect.*;
import dev.tc4port.thaumcraft.api.entity.AttributeTypes;
import dev.tc4port.thaumcraft.api.focus.FocusApi;
import dev.tc4port.thaumcraft.api.focus.FocusUpgradeId;
import dev.tc4port.thaumcraft.api.focus.behavior.FocusServerContext;
import dev.senluo.witchcraft.slash.SlashCritical;
import dev.senluo.witchcraft.slash.SlashRules;
import dev.senluo.witchcraft.slash.drawing.StrokeTrace;
import java.util.Map;
import dev.tc4port.thaumcraft.api.focus.FocusCostQuote;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

/** Native pricing and gameplay power share the original, unrounded cost of the same cast phase. */
record SlashVis(FocusCostQuote price, double potential, SlashEfficacy.Parameters parameters) {
    static SlashVis quote(FocusServerContext<?, ?> context, Map<FocusUpgradeId, Integer> levels,
                          SlashRules rules, StrokeTrace trace) {
        var raw = FocusApi.registry().profile(SlashFocus.ID).flatMap(p -> p.fixedCost(levels)).orElse(null);
        if (raw == null || raw.amounts().getOrDefault(BuiltinAspects.PERDITIO, 0) <= 0) return null;
        var wand = context.carrier();
        double chaos = raw.amounts().get(BuiltinAspects.PERDITIO) / 100.0 * SlashEfficacy.baseMultiplier(wand, levels);
        double attribute = positiveAttribute(context.player());
        var base = context.quote(raw, FocusCostQuote.Cadence.PER_PAYMENT, 0);
        double power = chaos * surcharge(base) * attribute * dev.senluo.witchcraft.slash.SlashPotential.PER_CHAOS_VIS;
        if (!validPower(power)) return null;
        var parameters = SlashEfficacy.Parameters.read(wand, levels);
        double scale = trace == null ? 1 : trace.multiplier(parameters.flight(power, 1).shape().initialLength());
        var scaled = scaled(raw, scale);
        power *= rules.bindingPower() * (trace == null ? 1 : rules.drawingPower(scale));
        if (scaled == null || !validPower(power)) return null;
        var price = scaled.equals(raw) ? base : context.quote(scaled, FocusCostQuote.Cadence.PER_PAYMENT, 0);
        return new SlashVis(price, power, parameters);
    }

    private static boolean validPower(double power) {
        return Double.isFinite(power) && power > 0 && power <= SlashCritical.MAX_BASE;
    }

    private static VisCost scaled(VisCost raw, double scale) {
        if (scale == 1) return raw;
        if (!Double.isFinite(scale) || scale <= 0 || scale > StrokeTrace.MAX_MULTIPLIER) return null;
        var amounts = new java.util.LinkedHashMap<AspectId, Integer>();
        for (var entry : raw.amounts().entrySet()) {
            double scaled = entry.getValue() * scale;
            if (scaled > Integer.MAX_VALUE) return null;
            amounts.put(entry.getKey(), Math.max(1, (int) Math.ceil(scaled)));
        }
        return VisCost.ofCentivis(amounts);
    }

    private static double surcharge(FocusCostQuote quote) {
        var modifier = quote.modifiers().get(BuiltinAspects.PERDITIO);
        if (modifier == null) return 1;
        double rate = 1;
        for (var term : modifier.terms()) {
            switch (term.kind()) {
                case CAP -> rate *= Math.max(1, term.amount());
                case CARRIER_DIVISOR -> rate /= Math.min(1, term.amount());
                case EXHAUSTION -> rate += Math.max(0, term.amount());
                default -> { }
            }
        }
        return rate;
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
