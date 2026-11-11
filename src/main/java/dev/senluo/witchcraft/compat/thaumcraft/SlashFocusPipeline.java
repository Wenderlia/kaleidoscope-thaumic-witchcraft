package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.slash.SlashContent;
import dev.tc4port.thaumcraft.api.aspect.VisCost;
import dev.tc4port.thaumcraft.api.focus.action.FocusInput;
import dev.tc4port.thaumcraft.api.wand.pipeline.*;
import java.util.LinkedHashMap;

/** Pure cost/prediction middleware, selected explicitly by the synchronized focus catalog. */
public final class SlashFocusPipeline {
    private SlashFocusPipeline() {}

    public static void register() {
        WandPipelineApi.define(SlashContent.id("slash_prediction"), WandCastStages.PREDICT, WandPipelineSide.CLIENT, (query, next) ->
                next.proceed(query.input() == FocusInput.SECONDARY ? query.withoutCost() : query));
        WandPipelineApi.define(SlashContent.id("efficacy_cost"), WandCastStages.COST, (query, next) -> {
            if (query.crafting() || query.raw().isEmpty()) return next.proceed(query);
            var raw = SlashEfficacy.baseCost(query.carrier(), VisCost.ofCentivis(query.raw()));
            var cost = next.proceed(new WandCastStages.Cost(query.carrier(), query.player(), raw.amounts(), false));
            double discount = SlashEfficacy.costMultiplier(query.carrier());
            var effective = new LinkedHashMap<dev.tc4port.thaumcraft.api.aspect.VisChannel, Integer>();
            cost.forEach((channel, amount) -> {
                int value = (int) (amount * discount);
                if (value > 0) effective.put(channel, value);
            });
            return effective;
        });
        WandPipelineApi.define(SlashContent.id("efficacy_tooltip"), WandQueryStages.COST_TOOLTIP, (query, next) ->
                next.proceed(new WandQueryStages.CostTooltip(query.carrier(),
                        query.baseCost().map(cost -> SlashEfficacy.baseCost(query.carrier(), cost)))));
    }
}
