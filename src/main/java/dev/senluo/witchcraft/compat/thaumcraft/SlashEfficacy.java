package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.slash.SlashContent;
import dev.senluo.witchcraft.slash.SlashShape;
import dev.senluo.witchcraft.motion.BezierSpeed;
import dev.tc4port.thaumcraft.api.focus.*;
import dev.tc4port.thaumcraft.api.aspect.VisChannel;
import dev.tc4port.thaumcraft.api.aspect.VisCost;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.item.ItemStack;

/** Catalog owns values and native upgrade slots. This adapter only supplies multiplicative semantics. */
public final class SlashEfficacy {
    public static final FocusUpgradeId ID = FocusUpgradeId.of(SlashContent.id("efficacy"));
    private SlashEfficacy() {}

    static void requireParameters() {
        FocusApi.requireParameters(SlashFocus.ID, Set.of("efficacy_base", "efficacy_cost", "surge_base", "flight_range",
                "flight_speed_start", "flight_speed_end", "blade_initial", "blade_final"));
    }

    private static double factor(ItemStack carrier, FocusStateView state, String parameter) {
        int level = Math.clamp(state.level(ID), 0, 5);
        return level == 0 ? 1 : Math.pow(FocusApi.parameter(carrier, state.focusId(), state.upgradeLevels(), parameter), level);
    }

    public static double costMultiplier(ItemStack wand) {
        var state = FocusApi.installedState(wand).filter(s -> s.focusId().equals(SlashFocus.ID));
        return state.map(s -> factor(wand, s, "efficacy_cost")).orElse(1.0);
    }

    /** Shared by native cost presentation and payment. Quantize only at the centivis boundary. */
    public static VisCost baseCost(ItemStack carrier, VisCost cost) {
        var state = FocusApi.installedState(carrier).orElseThrow();
        if (!state.focusId().equals(SlashFocus.ID)) return cost;
        double factor = factor(carrier, state, "efficacy_base") * SlashUpgrades.surge(carrier);
        if (factor == 1) return cost;
        Map<VisChannel, Integer> amounts = new LinkedHashMap<>();
        cost.amounts().forEach((channel, amount) -> {
            double scaled = amount * factor;
            if (!Double.isFinite(scaled) || scaled < 1 || scaled > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Invalid efficacy base Vis cost");
            amounts.put(channel, (int) scaled);
        });
        return VisCost.ofCentivis(amounts);
    }

    /** Unrounded base usage drives power; native cost rounding or discounts must not change it. */
    static double chaosUsage(ItemStack wand) {
        var state = FocusApi.installedState(wand).orElseThrow();
        var profile = FocusApi.registry().profile(state.focusId()).orElseThrow();
        var cost = profile.matchingCostOverride(state.upgradeLevels()).map(FocusProfileDefinition.CostOverride::cost)
                .or(profile::baseCost).orElseThrow();
        return cost.amounts().getOrDefault(VisChannel.PERDITIO, 0) / 100.0
                * factor(wand, state, "efficacy_base") * SlashUpgrades.surge(wand);
    }

    static Flight flight(ItemStack wand, double initialPotential) {
        return flight(wand, initialPotential, 1);
    }

    static Flight flight(ItemStack wand, double initialPotential, double rangeMultiplier) {
        if (!Double.isFinite(rangeMultiplier) || rangeMultiplier <= 0)
            throw new IllegalArgumentException("Invalid slash range multiplier");
        double scale = Math.sqrt(initialPotential);
        double range = FocusApi.parameter(wand, "flight_range") * rangeMultiplier, first = FocusApi.parameter(wand, "blade_initial"),
                last = FocusApi.parameter(wand, "blade_final"), start = FocusApi.parameter(wand, "flight_speed_start"),
                end = FocusApi.parameter(wand, "flight_speed_end");
        // Keep the pre-existing spatial safety bounds without discarding or capping critical power.
        // One common scale retains proportions and flight duration at extreme potential.
        scale = Math.min(scale, Math.min(SlashShape.MAX_RANGE/range,
                Math.min(SlashShape.MAX_LENGTH/last, SlashShape.MAX_SPEED/end)));
        SlashShape shape = new SlashShape(Math.min(SlashShape.MAX_RANGE, range*scale), first*scale,
                Math.min(SlashShape.MAX_LENGTH, last*scale));
        return new Flight(shape, shape.speed(start*scale, Math.min(SlashShape.MAX_SPEED, end*scale)));
    }

    record Flight(SlashShape shape, BezierSpeed speed) {}
}
