package dev.senluo.witchcraft.time;

import net.minecraft.server.level.ServerLevel;

/** Optional paid structure work. Natural development and inventory remain independent. */
public final class GrowthCatalysis {
    public static final long PLANT_FEE = 120 * Tissue.TU;
    public static final long ROOT_FEE = 60 * Tissue.TU;
    public static final long TREE_FEE = 5_760 * Tissue.TU;
    public static final long GIANT_FEE = 28_800 * Tissue.TU;
    private static final long[] AMBER_FEE = {0, 600 * Tissue.TU, 2_400 * Tissue.TU, 9_600 * Tissue.TU};

    public record Quote(int ticks, long milliTu) {}
    private GrowthCatalysis() {}

    /** Read-only: includes existing tissue only; never creates roots, advances work or reserves funds. */
    public static Quote quote(LivingEntity entity) {
        if (!entity.tissue.supported()) return new Quote(0, 0);
        Tissue tissue = entity.tissue;
        int natural = Math.max(0, DevelopmentTime.naturalLimit(entity) - tissue.naturalTicks);
        int amber = DevelopmentTime.amberRemaining(entity);
        int elapsed = Math.max(Math.max(natural, amber), DevelopmentTime.attachedTicks(tissue));
        if (elapsed <= 0) return new Quote(0, 0);
        long ownGrowth = growthPrice(tissue, elapsed);
        long naturalPrice = 0;
        if (!entity.amber()) {
            int end = tissue.naturalTicks + Math.min(elapsed, natural);
            // The core's young tissue and its first plant phase develop together, not as two purchases.
            ownGrowth = Math.max(ownGrowth, interval(PLANT_FEE, tissue.naturalTicks, end, 0, Tissue.ROOT_TICKS));
            naturalPrice = interval(TREE_FEE, tissue.naturalTicks, end, Tissue.ROOT_TICKS, TreeBalance.ENTRY_TICKS)
                    + interval(GIANT_FEE, tissue.naturalTicks, end, TreeBalance.ENTRY_TICKS, TreeBalance.GIANT_TICKS);
        }
        long price = ownGrowth + naturalPrice;
        if (amber > 0) price += WorkCost.between(AMBER_FEE[tissue.amberStage + 1], tissue.trainingTicks,
                tissue.trainingTicks + amber, Tissue.AMBER_TIME[tissue.amberStage + 1]);
        for (Tissue.Side side : tissue.sides) price += attachedPrice(side.tissue, elapsed);
        if (tissue.rootProduction != null) {
            price += rootProductionPrice(tissue.rootProduction, elapsed);
            price += attachedPrice(tissue.rootProduction.output().tissue, elapsed);
        }
        return new Quote(elapsed, price);
    }

    private static long interval(long fee, int from, int to, int start, int end) {
        return WorkCost.between(fee, Math.clamp(from - start, 0, end - start),
                Math.clamp(to - start, 0, end - start), end - start);
    }

    /** Only the selected tissue's own maturity; nested roots and the mother's clocks are excluded. */
    public static Quote quoteSide(LivingEntity entity) {
        if (!entity.tissue.supported()) return new Quote(0, 0);
        if (entity.tissue.rootProduction != null) {
            RootProduction production = entity.tissue.rootProduction;
            return new Quote(production.remainingTicks(), rootProductionPrice(production, production.remainingTicks()));
        }
        Tissue.Side side = entity.tissue.selectedSide();
        if (side == null || !side.tissue.supported() || side.mature()) return new Quote(0, 0);
        int remaining = side.remainingTicks();
        return new Quote(remaining, growthPrice(side.tissue, remaining));
    }

    /** Requote before debit; completing maturity increases capacity but never creates inventory. */
    public static boolean completeSide(LivingEntity entity) {
        if (!(entity.getLevel() instanceof ServerLevel level) || entity.isRemoved()
                || !level.shouldTickBlocksAt(entity.getBlockPos()) || !level.tickRateManager().runsNormally()) return false;
        Quote quote = quoteSide(entity);
        if (quote.ticks <= 0) return false;
        Tissue.Side side = entity.tissue.selectedSide();
        Colony group = TemporalWorld.get(level).colony(entity);
        if (quote.milliTu > FruitFeeding.available(group)
                || quote.milliTu > 0 && !group.spend(quote.milliTu)) return false;
        if (entity.tissue.rootProduction != null) entity.tissue.rootProduction.advance(quote.ticks);
        else side.advance(quote.ticks);
        entity.changed();
        return true;
    }

    private static long rootProductionPrice(RootProduction production, int elapsed) {
        int elapsedBefore = RootProduction.COOLDOWN - production.remainingTicks();
        return WorkCost.between(ROOT_FEE, elapsedBefore,
                elapsedBefore + Math.min(elapsed, production.remainingTicks()), RootProduction.COOLDOWN);
    }

    private static long growthPrice(Tissue tissue, int elapsed) {
        if (!tissue.supported() || tissue.growth == null) return 0;
        Tissue.Growth growth = tissue.growth;
        return WorkCost.between(ROOT_FEE, growth.ticks,
                growth.ticks + Math.min(elapsed, growth.remainingTicks()), Tissue.SIDE_TIME[growth.profile]);
    }

    private static long attachedPrice(Tissue tissue, int elapsed) {
        if (!tissue.supported()) return 0;
        long price = growthPrice(tissue, elapsed);
        for (Tissue.Side side : tissue.sides) price += attachedPrice(side.tissue, elapsed);
        return price;
    }

    public static boolean hasSpace(LivingEntity entity) {
        if (!(entity.getLevel() instanceof ServerLevel level)) return false;
        if (entity.amber() || entity.tissue.giant || !DevelopmentTime.woodySite(entity)
                || entity.tissue.naturalTicks >= DevelopmentTime.naturalLimit(entity)) return true;
        return TemporalWorld.get(level).trees.hasTransitionSpace(entity);
    }

    /** Caller authorizes inventory access. Requotes on the server and commits once, even while paused. */
    public static boolean complete(LivingEntity entity) {
        if (!(entity.getLevel() instanceof ServerLevel level) || entity.isRemoved()
                || !level.shouldTickBlocksAt(entity.getBlockPos()) || !level.tickRateManager().runsNormally()) return false;
        Quote quote = quote(entity);
        if (quote.ticks <= 0 || !hasSpace(entity)) return false;
        Colony group = TemporalWorld.get(level).colony(entity);
        if (quote.milliTu > FruitFeeding.available(group)
                || quote.milliTu > 0 && !group.spend(quote.milliTu)) return false;
        // Snapshot the currently allowed amber stage before young tissue can establish itself.
        DevelopmentTime.advanceStructure(entity, quote.ticks, DevelopmentTime.amberRemaining(entity));
        return true;
    }
}
