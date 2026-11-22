package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.slash.SlashContent;
import dev.senluo.witchcraft.slash.SlashShape;
import dev.senluo.witchcraft.motion.BezierSpeed;
import dev.tc4port.thaumcraft.api.focus.*;
import dev.tc4port.thaumcraft.api.aspect.AspectId;
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

    private static double factor(ItemStack carrier, Map<FocusUpgradeId, Integer> levels, FocusUpgradeId upgrade, String key) {
        int level = Math.clamp(levels.getOrDefault(upgrade, 0), 0, 5);
        return level == 0 ? 1 : Math.pow(FocusApi.parameter(carrier, SlashFocus.ID, levels, key), level);
    }

    static double baseMultiplier(ItemStack carrier, Map<FocusUpgradeId, Integer> levels) {
        return factor(carrier, levels, ID, "efficacy_base") * factor(carrier, levels, SlashUpgrades.SURGE, "surge_base");
    }

    static double discount(ItemStack carrier, Map<FocusUpgradeId, Integer> levels) {
        return factor(carrier, levels, ID, "efficacy_cost");
    }

    static VisCost baseCost(VisCost cost, double factor) {
        if (factor == 1) return cost;
        Map<AspectId, Integer> amounts = new LinkedHashMap<>();
        cost.amounts().forEach((channel, amount) -> {
            double scaled = amount * factor;
            if (!Double.isFinite(scaled) || scaled < 1 || scaled > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Invalid efficacy base Vis cost");
            amounts.put(channel, (int) scaled);
        });
        return VisCost.ofCentivis(amounts);
    }

    record Parameters(double range, double first, double last, double start, double end) {
        static Parameters read(ItemStack wand, Map<FocusUpgradeId, Integer> levels) {
            return new Parameters(FocusApi.parameter(wand, SlashFocus.ID, levels, "flight_range"),
                    FocusApi.parameter(wand, SlashFocus.ID, levels, "blade_initial"),
                    FocusApi.parameter(wand, SlashFocus.ID, levels, "blade_final"),
                    FocusApi.parameter(wand, SlashFocus.ID, levels, "flight_speed_start"),
                    FocusApi.parameter(wand, SlashFocus.ID, levels, "flight_speed_end"));
        }

        Flight flight(double potential, double rangeMultiplier) {
            double reach = range * rangeMultiplier;
            double scale = Math.min(Math.sqrt(potential), Math.min(SlashShape.MAX_RANGE / reach,
                    Math.min(SlashShape.MAX_LENGTH / last, SlashShape.MAX_SPEED / end)));
            SlashShape shape = new SlashShape(Math.min(SlashShape.MAX_RANGE, reach * scale), first * scale,
                    Math.min(SlashShape.MAX_LENGTH, last * scale));
            return new Flight(shape, shape.speed(start * scale, Math.min(SlashShape.MAX_SPEED, end * scale)));
        }
    }

    record Flight(SlashShape shape, BezierSpeed speed) {}
}
