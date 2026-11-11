package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.ritual.InfusionRingInfluence;
import dev.senluo.witchcraft.ritual.RitualPlane;
import dev.senluo.witchcraft.time.TreeWood;
import dev.tc4port.thaumcraft.api.event.InfusionEvent;
import dev.tc4port.thaumcraft.api.event.InfusionScanEvent;
import dev.tc4port.thaumcraft.api.machine.InfusionMatrixApi;
import dev.tc4port.thaumcraft.api.machine.InfusionRunState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import java.util.*;

public final class InfusionInfluence {
    public static final String CREDIT = "kaleidoscope_witchcraft:infusion_ring_credit";
    // Identity matters: two native callbacks with identical snapshots are still two opportunities.
    private static final class Opportunity {
        final BlockEntity matrix;
        final InfusionEvent.Work event;
        final int bonus;
        Opportunity(BlockEntity matrix, InfusionEvent.Work event, int bonus) {
            this.matrix = matrix; this.event = event; this.bonus = bonus;
        }
    }
    // Preserve native order and synchronous cancellation, with O(1) membership/removal during drain.
    private static final Map<ServerLevel, LinkedHashSet<Opportunity>> pending = new WeakHashMap<>();
    private static final Set<BlockEntity> executing = Collections.newSetFromMap(new IdentityHashMap<>());

    static void register() {
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(InfusionInfluence::scan);
        bus.addListener(EventPriority.LOWEST, InfusionInfluence::work);
        bus.addListener((InfusionEvent.Start event) -> {
            reset(event);
            var matrix = event.level().getBlockEntity(event.matrix());
            if (matrix != null) { matrix.getPersistentData().remove(CREDIT); matrix.setChanged(); }
        });
        bus.addListener((InfusionEvent.End event) -> reset(event));
        bus.addListener((ChunkEvent.Unload event) -> {
            var queue = pending.get(event.getLevel());
            if (queue != null) queue.removeIf(extra -> ChunkPos.asLong(extra.matrix.getBlockPos()) == event.getChunk().getPos().toLong());
        });
        bus.addListener((LevelEvent.Unload event) -> pending.remove(event.getLevel()));
        bus.addListener(InfusionInfluence::drain);
    }

    private static void reset(InfusionEvent event) {
        var queue = pending.get(event.level());
        if (queue != null) queue.removeIf(extra -> extra.matrix.getBlockPos().equals(event.matrix()));
    }

    private static InfusionRingInfluence rings(ServerLevel level, BlockPos pos) {
        return InfusionRings.lookup(level, pos).map(plane -> InfusionRingInfluence.of(plane.rings())).orElse(InfusionRingInfluence.NONE);
    }

    private static void work(InfusionEvent.Work event) {
        var matrix = event.level().getBlockEntity(event.matrix());
        if (matrix == null || !InfusionGeas.allowPulse(matrix, event.state()) || !ReservoirInfusion.allowPulse(event)) {
            event.setCanceled(true); return;
        }
        var state = event.state();
        if (executing.contains(matrix) || state.remainingExperienceLevels() > 0
                || state.remainingIngredients() == 0 && state.remainingEssentia().amounts().isEmpty()) return;
        // Work is posted before resource checks. Settle only after the native pulse has returned.
        pending.computeIfAbsent(event.level(), ignored -> new LinkedHashSet<>()).add(
                new Opportunity(matrix, event, rings(event.level(), event.matrix()).bonusTwentieths()));
    }

    private static void drain(LevelTickEvent.Post tick) {
        if (!(tick.getLevel() instanceof ServerLevel level)) return;
        var queue = pending.get(level);
        if (queue == null || queue.isEmpty()) return;
        var batch = List.copyOf(queue);
        var following = new IdentityHashMap<BlockEntity, InfusionRunState>();
        var completed = new InfusionRunState[batch.size()];
        // Consecutive pre-pulse snapshots delimit distinct native opportunities in this tick.
        // Only the final opportunity reads current state; no progress is counted twice.
        for (int i = batch.size() - 1; i >= 0; i--) {
            var opportunity = batch.get(i);
            completed[i] = following.containsKey(opportunity.matrix) ? following.get(opportunity.matrix)
                    : InfusionMatrixApi.state(level, opportunity.matrix.getBlockPos()).orElse(null);
            following.put(opportunity.matrix, opportunity.event.state());
        }
        for (int i = 0; i < batch.size(); i++) {
            var opportunity = batch.get(i);
            var matrix = opportunity.matrix;
            var pos = matrix.getBlockPos();
            var after = completed[i];
            if (!queue.contains(opportunity)) continue;
            if (opportunity.event.isCanceled() || matrix.isRemoved() || !level.hasChunkAt(pos)
                    || !level.shouldTickBlocksAt(pos) || level.getBlockEntity(pos) != matrix
                    || after == null || !after.crafting()
                    || !after.recipeId().equals(opportunity.event.state().recipeId())
                    || !absorptionProgress(opportunity.event.state(), after)) {
                queue.remove(opportunity); continue;
            }
            int bonus = opportunity.bonus;
            if (bonus == 0) { queue.remove(opportunity); continue; }
            int credit = Math.clamp(matrix.getPersistentData().getInt(CREDIT), 0, 19) + bonus;
            var current = InfusionMatrixApi.state(level, pos).orElse(null);
            if (credit >= 20 && absorbable(after) && current != null && current.active()
                    && current.crafting() && absorbable(current) && InfusionGeas.allowPulse(matrix, current)) {
                executing.add(matrix);
                try { if (InfusionMatrixApi.craftCycle(level, pos)) credit -= 20; }
                finally { executing.remove(matrix); }
            }
            // Start/End may synchronously clear this opportunity. Never write old credit into a new craft.
            if (!queue.remove(opportunity)) continue;
            matrix.getPersistentData().putInt(CREDIT, Math.min(credit, 19));
            matrix.setChanged();
        }
    }

    private static boolean absorptionProgress(InfusionRunState before, InfusionRunState after) {
        if (before.remainingExperienceLevels() > 0 || after.remainingExperienceLevels() > 0) return false;
        long previousEssentia = before.remainingEssentia().amounts().values().stream().mapToLong(Integer::longValue).sum();
        long essentia = after.remainingEssentia().amounts().values().stream().mapToLong(Integer::longValue).sum();
        return essentia < previousEssentia || after.remainingIngredients() < before.remainingIngredients()
                || previousEssentia == 0 && after.remainingIngredients() > 0 && after.absorptionTicks() > 0
                && (before.absorptionTicks() == 0 || after.absorptionTicks() < before.absorptionTicks());
    }

    private static boolean absorbable(InfusionRunState state) {
        return state.remainingExperienceLevels() == 0
                && (state.remainingIngredients() > 0 || !state.remainingEssentia().amounts().isEmpty());
    }

    private static void scan(InfusionScanEvent event) {
        var level = event.level();
        var axis = event.matrix();
        RitualPlane.invalidate(level, axis);
        float single = (float)HostParameters.value("machines/infusion/stabilizer_symmetry");
        float paired = (float)HostParameters.value("machines/infusion/mirrored_stabilizer_symmetry");
        float delta = rings(level, axis).stability(single, paired);
        var positions = new HashSet<BlockPos>();
        // Only our wood can change qualification. Its ordinary mirror can change contribution too.
        for (int cx = (axis.getX()-12)>>4; cx <= (axis.getX()+12)>>4; cx++)
            for (int cz = (axis.getZ()-12)>>4; cz <= (axis.getZ()+12)>>4; cz++) {
                var chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                int low = Math.max(level.getMinBuildHeight(), axis.getY()-10);
                int high = Math.min(level.getMaxBuildHeight()-1, axis.getY()+5);
                for (int sy = low>>4; sy <= high>>4; sy++) {
                    var section = chunk.getSection(chunk.getSectionIndex(sy<<4));
                    if (!section.maybeHas(state -> state.is(TreeWood.LOGS))) continue;
                    for (var pos : BlockPos.betweenClosed(Math.max(cx<<4, axis.getX()-12), Math.max(sy<<4, low), Math.max(cz<<4, axis.getZ()-12),
                            Math.min((cx<<4)+15, axis.getX()+12), Math.min((sy<<4)+15, high), Math.min((cz<<4)+15, axis.getZ()+12))) {
                        if (chunk.getBlockState(pos).is(TreeWood.LOGS)) { positions.add(pos.immutable()); positions.add(mirror(axis, pos)); }
                    }
                }
            }
        positions.removeAll(event.pedestals());
        for (var pos : positions) {
            if (pos.getX() == axis.getX() && pos.getZ() == axis.getZ() || !level.hasChunkAt(pos)) continue;
            var opposite = mirror(axis, pos);
            if (!level.hasChunkAt(opposite)) continue;
            var state = level.getBlockState(pos); var other = level.getBlockState(opposite);
            if (InfusionStabilizers.ordinary(state)) delta -= single - (InfusionStabilizers.ordinary(other) ? paired : 0);
            if (InfusionStabilizers.matches(level, pos, state)) delta += single - (InfusionStabilizers.matches(level, opposite, other) ? paired : 0);
        }
        event.setStabilizerSymmetry(event.stabilizerSymmetry() + delta);
    }

    private static BlockPos mirror(BlockPos axis, BlockPos pos) {
        return new BlockPos(2 * axis.getX() - pos.getX(), pos.getY(), 2 * axis.getZ() - pos.getZ());
    }
    private InfusionInfluence() {}
}
