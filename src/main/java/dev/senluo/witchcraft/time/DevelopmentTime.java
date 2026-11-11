package dev.senluo.witchcraft.time;

import net.minecraft.server.level.ServerLevel;

/** Natural structure uses simulated development time; production has its own paid work clock. */
public final class DevelopmentTime {
    public static final int BONE_TICKS = Tissue.ROOT_TICKS / 4;

    private DevelopmentTime() {}

    public static boolean woodySite(LivingEntity entity) {
        if (entity.amber() || entity.getLevel() == null) return false;
        return entity.tree()
                || entity.getLevel().getBlockState(entity.getBlockPos().below()).is(dev.senluo.witchcraft.time.TreeWood.LOGS)
                || TissueItem.woodySoil(entity.getLevel().getBlockState(entity.getBlockPos().below()));
    }

    static int naturalLimit(LivingEntity entity) {
        if (entity.amber()) return 0;
        return woodySite(entity) ? entity.tree() ? TreeBalance.GIANT_TICKS : TreeBalance.ENTRY_TICKS : Tissue.ROOT_TICKS;
    }

    public static boolean hasStructureRemaining(LivingEntity entity) {
        if (!entity.tissue.supported()) return false;
        Tissue tissue = entity.tissue;
        if (tissue.naturalTicks < naturalLimit(entity)) return true;
        if (!tissue.established() && tissue.growth.remainingTicks() > 0) return true;
        if (entity.amber()) return amberRemaining(entity) > 0 || attachedRemaining(tissue);
        return attachedRemaining(tissue);
    }

    private static boolean attachedRemaining(Tissue tissue) { return attachedTicks(tissue) > 0; }

    static int attachedTicks(Tissue tissue) {
        if (!tissue.supported()) return 0;
        int remaining = Math.max(tissue.growth == null ? 0 : tissue.growth.remainingTicks(), TreeBalance.remaining(tissue));
        if (tissue.rootProduction != null) {
            remaining = Math.max(remaining, tissue.rootProduction.remainingTicks());
            remaining = Math.max(remaining, attachedTicks(tissue.rootProduction.output().tissue));
        }
        for (Tissue.Side side : tissue.sides) remaining = Math.max(remaining, attachedTicks(side.tissue));
        return remaining;
    }

    public static boolean canFertilize(LivingEntity entity) {
        return entity.tissue.supported() && (entity.amber() ? attachedRemaining(entity.tissue) : hasStructureRemaining(entity));
    }

    /** Bone meal feeds living tissue; mineral cultivation still uses its natural clock or paid catalysis. */
    public static int fertilize(LivingEntity entity) {
        return advanceStructure(entity, BONE_TICKS, 0);
    }

    public static boolean hasRemaining(LivingEntity entity) {
        if (hasStructureRemaining(entity)) return true;
        return entity.tissue.supported() && entity.tree() && entity.getLevel() instanceof ServerLevel level
                && SupplyMode.rate(TemporalWorld.get(level).colony(entity).productionMode()) > 0
                && TemporalWorld.get(level).trees.fruits(entity).stream().anyMatch(FruitEntity::canGrow);
    }

    public static void tick(LivingEntity entity) {
        if (entity.getLevel() instanceof ServerLevel level) TemporalWorld.get(level).development.pulse(entity);
    }

    static void advance(LivingEntity entity, int elapsed) {
        if (!entity.tissue.supported() || !(entity.getLevel() instanceof ServerLevel level) || entity.isRemoved()
                || !level.shouldTickBlocksAt(entity.getBlockPos()) || !level.tickRateManager().runsNormally()) return;
        if (!entity.amber() && GiantRoots.restore(entity.tissue)) entity.changed();
        advanceStructure(entity, elapsed);
        if (elapsed > 1) TemporalWorld.get(level).accelerateRoots(entity, elapsed - 1);
    }

    /** Raw development for the natural clock and explicit integrations; it does not perform a TU purchase. */
    public static int advanceStructure(LivingEntity entity, int elapsed) {
        return advanceStructure(entity, elapsed, elapsed);
    }

    static int advanceStructure(LivingEntity entity, int elapsed, int amberTicks) {
        if (!entity.tissue.supported() || elapsed <= 0) return 0;
        Tissue tissue = entity.tissue;
        int beforeStage = tissue.rootStage;
        int advanced = advanceAttached(tissue, elapsed);
        if (entity.amber()) advanced = Math.max(advanced, advanceAmber(entity, Math.min(elapsed, amberTicks)));
        else {
            int natural = Math.min(elapsed, Math.max(0, naturalLimit(entity) - tissue.naturalTicks));
            tissue.naturalTicks += natural;
            advanced = Math.max(advanced, natural);
            if (tissue.naturalTicks >= Tissue.ROOT_TICKS) {
                tissue.rootStage = 4;
            }
            if (tissue.treeGrowing) tissue.treeTicks = tissue.naturalTicks;
            if (tissue.giantGrowing) tissue.giantTicks = tissue.naturalTicks;
        }
        boolean inherited = !entity.amber() && GiantRoots.synchronize(tissue);
        if (advanced > 0 || beforeStage != tissue.rootStage || inherited) entity.changed();
        if (beforeStage != tissue.rootStage && entity.getLevel() instanceof ServerLevel server)
            TemporalWorld.get(server).invalidate();
        return advanced;
    }

    private static int advanceAttached(Tissue tissue, int elapsed) {
        if (!tissue.supported()) return 0;
        int advanced = advance(tissue.growth, elapsed);
        if (tissue.rootProduction != null) {
            advanced = Math.max(advanced, tissue.rootProduction.advance(elapsed));
            advanced = Math.max(advanced, advanceAttached(tissue.rootProduction.output().tissue, elapsed));
        }
        for (Tissue.Side side : tissue.sides) advanced = Math.max(advanced, advanceAttached(side.tissue, elapsed));
        return advanced;
    }

    private static int advance(Tissue.Growth growth, int ticks) {
        if (growth == null) return 0;
        int advanced = Math.min(ticks, growth.remainingTicks());
        growth.advance(advanced);
        return advanced;
    }

    static int amberRemaining(LivingEntity entity) {
        Tissue tissue = entity.tissue;
        if (!entity.amber() || !tissue.established() || tissue.amberStage >= 3
                || TissueItem.stoneGrade(entity.host) < tissue.amberStage + 1) return 0;
        return Math.max(0, Tissue.AMBER_TIME[tissue.amberStage + 1] - tissue.trainingTicks);
    }

    private static int advanceAmber(LivingEntity entity, int ticks) {
        int advanced = 0;
        while (ticks > 0 && amberRemaining(entity) > 0) {
            int step = Math.min(ticks, amberRemaining(entity));
            entity.tissue.training = true;
            entity.tissue.train(TissueItem.stoneGrade(entity.host), step);
            ticks -= step;
            advanced += step;
        }
        return advanced;
    }
}
