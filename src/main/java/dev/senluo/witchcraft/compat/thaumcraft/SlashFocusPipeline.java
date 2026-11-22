package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.slash.SlashContent;
import dev.tc4port.thaumcraft.api.aspect.VisCost;
import dev.tc4port.thaumcraft.api.focus.action.FocusInput;
import dev.tc4port.thaumcraft.api.wand.pipeline.*;
import dev.tc4port.thaumcraft.api.aspect.VisCostSource;
import dev.tc4port.thaumcraft.api.focus.FocusApi;
import java.util.LinkedHashMap;

/** Pure cost/prediction middleware, selected explicitly by the synchronized focus catalog. */
public final class SlashFocusPipeline {
    private SlashFocusPipeline() {}

    public static void register() {
        WandPipelineApi.define(SlashContent.id("slash_prediction"), WandCastStages.PREDICT, WandPipelineSide.CLIENT, (query, next) ->
                next.proceed(query.input() == FocusInput.SECONDARY ? query.withoutCost() : query));
        WandPipelineApi.define(SlashContent.id("efficacy_cost"), WandCastStages.COST, (query, next) -> {
            if (!query.source().equals(VisCostSource.FOCUS) || query.raw().isEmpty()) return next.proceed(query);
            var state = FocusApi.installedState(query.carrier()).filter(s -> s.focusId().equals(SlashFocus.ID)
                    && query.openingFocus().filter(s.focusId()::equals).isPresent());
            if (state.isEmpty()) return next.proceed(query);
            var levels = state.get().upgradeLevels();
            double base = SlashEfficacy.baseMultiplier(query.carrier(), levels);
            double discount = SlashEfficacy.discount(query.carrier(), levels);
            var raw = SlashEfficacy.baseCost(VisCost.ofCentivis(query.raw()), base);
            var cost = next.proceed(new WandCastStages.Cost(query.carrier(), query.player(), raw.amounts(),
                    query.source(), query.openingFocus()));
            if (discount == 1 || cost.isEmpty()) return cost;
            var effective = new LinkedHashMap<dev.tc4port.thaumcraft.api.aspect.AspectId, Integer>();
            cost.forEach((channel, amount) -> {
                int value = (int) (amount * discount);
                if (value > 0) effective.put(channel, value);
            });
            return effective;
        });
        WandPipelineApi.define(SlashContent.id("efficacy_tooltip"), WandQueryStages.COST_TOOLTIP, (query, next) -> {
            var state = FocusApi.installedState(query.carrier()).filter(s -> s.focusId().equals(SlashFocus.ID));
            if (query.baseCost().isEmpty() || state.isEmpty()) return next.proceed(query);
            double base = SlashEfficacy.baseMultiplier(query.carrier(), state.get().upgradeLevels());
            return next.proceed(new WandQueryStages.CostTooltip(query.carrier(),
                    query.baseCost().map(cost -> SlashEfficacy.baseCost(cost, base))));
        });
    }
}
