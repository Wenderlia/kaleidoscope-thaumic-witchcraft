package dev.senluo.witchcraft.slash;

import java.util.*;
import java.util.function.DoubleUnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;

/** World adapter: bounded broad phase, exact swept contacts, ordered native side effects. */
final class SlashCollision {
    static final int MAX_CELLS = 2048, MAX_QUERIES = 2048;
    private static final int MAX_SHAPES = 64;
    /** Shared by every curved piece in one simulation tick, not replenished at each vertex. */
    static final class Budget {
        final dev.senluo.witchcraft.accessory.ProjectileBarrier.FlightSpace space;
        int cells = MAX_CELLS;
        int targets = SlashEntity.MAX_TARGETS;
        int intersections = 32768;
        int radialVisits = 8192;
        int queries = MAX_QUERIES;
        boolean exhausted;
        final Set<BlockPos> radialCells = new HashSet<>();
        final Set<UUID> radialTargets = new HashSet<>();
        Budget(dev.senluo.witchcraft.accessory.ProjectileBarrier.FlightSpace space) { this.space = space; }
    }
    private record Contact(SlashGeometry.Contact hit, BlockPos block, Entity entity) {}
    private SlashCollision() {}

    /** Reject pathological spatial work before even enumerating chunk coordinates. */
    static boolean bounded(AABB bounds) {
        double cells = (Math.floor(bounds.maxX) - Math.floor(bounds.minX) + 1)
                * (Math.floor(bounds.maxY) - Math.floor(bounds.minY) + 1)
                * (Math.floor(bounds.maxZ) - Math.floor(bounds.minZ) + 1);
        return Double.isFinite(cells) && cells <= MAX_CELLS;
    }

    static boolean loaded(ServerLevel level, AABB bounds) {
        for (int x = Mth.floor(bounds.minX) >> 4; x <= Mth.floor(bounds.maxX) >> 4; x++)
            for (int z = Mth.floor(bounds.minZ) >> 4; z <= Mth.floor(bounds.maxZ) >> 4; z++)
                if (!level.hasChunk(x, z)) return false;
        return true;
    }

    static double resolve(SlashEntity slash, ServerPlayer player, List<SlashGeometry.Sweep> sweeps, Budget budget) {
        if (slash.hitBlocks.size() >= SlashEntity.MAX_BLOCKS || slash.hitEntities.size() >= SlashEntity.MAX_TARGETS)
            return 0;
        Parts result = collect(slash, player, sweeps, budget, true, null, null);
        for (boolean accepted : result.accepted) if (!accepted) { budget.exhausted = true; return 0; }
        return Arrays.stream(result.fractions).min().orElse(0);
    }

    /** Same native settlement for linear and radial geometry; a stopped part cannot cancel its neighbours. */
    private enum Outcome { PASS, HIT, BLOCKED }
    private static Outcome apply(SlashEntity slash, ServerPlayer player, Contact contact, double fragmentCost) {
        boolean fragment = fragmentCost > 0;
        ServerLevel level = player.serverLevel();
        if (contact.block != null) {
                if (slash.passesBlocks()) return Outcome.PASS;
                BlockPos pos = contact.block;
                if (!level.hasChunkAt(pos)) return Outcome.BLOCKED;
                if (slash.hitBlocks.contains(pos.asLong())) return Outcome.PASS;
                if (slash.hitBlocks.size() >= SlashEntity.MAX_BLOCKS) return Outcome.BLOCKED;
                var state = level.getBlockState(pos);
                if (state.isAir() || SlashBlockPassage.allows(slash, state, pos)) return Outcome.PASS;
                float hardness = state.getDestroySpeed(level, pos);
                var impact = new BlockHitResult(contact.hit.point(), Direction.getNearest(slash.forward().scale(-1)), pos, false);
                if (slash.restrained() || !slash.cuts(hardness, fragment)) {
                    slash.blockedContact(contact.hit.point(), state, hardness); return Outcome.BLOCKED;
                }
                if (!level.mayInteract(player, pos)
                        || NeoForge.EVENT_BUS.post(new ProjectileImpactEvent(slash, impact)).isCanceled()
                        || CommonHooks.fireBlockBreak(level, player.gameMode.getGameModeForPlayer(), player, pos, state).isCanceled()) {
                    slash.contact(contact.hit.point()); return Outcome.BLOCKED;
                }
                // An event may replace the contacted block. Never destroy its replacement using an old quote.
                if (level.getBlockState(pos) != state) return Outcome.BLOCKED;
                // Native player removal/drop hooks retain inventory drops and enchantment semantics.
                // Cutting is magical harvesting: hardness/potential replaces tool-tier eligibility.
                var blockEntity = level.getBlockEntity(pos);
                state = state.getBlock().playerWillDestroy(level, pos, state, player);
                // Paid spell harvesting is independent of the caster's creative-mode hand mining.
                if (!state.onDestroyedByPlayer(level, pos, player, true, level.getFluidState(pos))) return Outcome.BLOCKED;
                state.getBlock().destroy(level, pos, state);
                state.getBlock().playerDestroy(level, player, pos, state, blockEntity, slash.tool());
                slash.hitBlocks.add(pos.asLong());
                slash.potential(slash.potential().afterBlock(fragment ? fragmentCost : slash.blockCost()));
                slash.contact(contact.hit.point());
                if (!fragment) SlashFission.impact(slash, player, null, Vec3.atCenterOf(pos), slash.blockCost());
                return Outcome.HIT;
            } else {
                Entity entity = contact.entity;
                if (!target(entity, player) || !fragment && slash.hitEntities.contains(identity(entity))) return Outcome.PASS;
                if (!fragment && slash.hitEntities.size() >= SlashEntity.MAX_TARGETS) return Outcome.BLOCKED;
                if (!fragment) slash.hitEntities.add(identity(entity));
                if (NeoForge.EVENT_BUS.post(new ProjectileImpactEvent(slash, new EntityHitResult(entity, contact.hit.point()))).isCanceled()) return Outcome.PASS;
                boolean intercepted = SlashInterception.remove(entity, player);
                if (intercepted || entity instanceof LivingEntity || entity instanceof PartEntity<?>) {
                    if (!intercepted && !(fragment
                            ? SlashWeaponEffects.fissionHit(slash, player, entity, fragmentCost, 1)
                            : SlashWeaponEffects.hit(slash, player, entity))) return Outcome.PASS;
                    slash.potential(fragment ? slash.potential().afterBlock(fragmentCost) : slash.potential().afterEntity());
                    slash.contact(contact.hit.point());
                    if (!fragment) SlashFission.impact(slash, player, intercepted ? null : entity,
                            entity.getBoundingBox().getCenter(), SlashPotential.ENTITY_COST);
                    return Outcome.HIT;
                }
            }
        return Outcome.PASS;
    }

    record Parts(double[] fractions, boolean[] accepted, boolean[] consumed) {}
    private record PartContact(int part, Contact contact) {}

    /** Collect first, then settle globally in time order. Unchecked parts wait, never move through gaps. */
    static Parts resolveParts(SlashEntity slash, ServerPlayer player, List<SlashGeometry.Sweep> sweeps, Budget budget,
                              List<DoubleUnaryOperator> timeline) {
        return collect(slash, player, sweeps, budget, false, timeline, null);
    }

    static Parts resolveFragments(SlashEntity slash, ServerPlayer player, List<SlashGeometry.Sweep> sweeps,
                                  Budget budget, double[] costs) {
        if (costs.length != sweeps.size()) throw new IllegalArgumentException("Fragment cost count");
        return collect(slash, player, sweeps, budget, false, null, costs);
    }

    private static Parts collect(SlashEntity slash, ServerPlayer player, List<SlashGeometry.Sweep> sweeps,
                                 Budget budget, boolean linked, List<DoubleUnaryOperator> timeline, double[] fragmentCosts) {
        ServerLevel level = player.serverLevel();
        double[] limits = new double[sweeps.size()]; boolean[] accepted = new boolean[sweeps.size()];
        boolean[] consumed = new boolean[sweeps.size()];
        List<PartContact> all = new ArrayList<>();
        // Cache only within collection, before the first callback or world mutation.
        Map<BlockPos, List<AABB>> shapes = new HashMap<>();
        parts: for (int n = 0; n < sweeps.size(); n++) {
            SlashGeometry.Sweep sweep = sweeps.get(n);
            double field = budget.space.segmentEntry(sweep.from(), sweep.to(), sweep.blade(),
                    sweep.halfBefore(), sweep.halfAfter(), sweep.padding());
            if (field <= 0) { accepted[n] = true; continue; }
            List<AABB> cover = SlashQueries.cover(sweep);
            if (cover.isEmpty()) break;
            if (cover.size() > budget.queries) break;
            budget.queries -= cover.size();
            Set<BlockPos> cells = new LinkedHashSet<>(); Set<BlockPos> freshCells = new HashSet<>();
            if (!slash.passesBlocks()) {
                double visits = cover.stream().mapToDouble(SlashQueries::volume).sum();
                if (visits > budget.radialVisits) break;
                budget.radialVisits -= (int) visits;
                for (AABB box : cover) for (BlockPos pos : SlashQueries.cells(box)) {
                    BlockPos key = pos.immutable(); cells.add(key);
                    if (!budget.radialCells.contains(key)) freshCells.add(key);
                    if (freshCells.size() > budget.cells) break parts;
                }
            }
            if (freshCells.size() > budget.cells || budget.intersections <= 0) break;
            List<Contact> local = new ArrayList<>(); boolean overflow = false;
            for (BlockPos pos : cells) {
                if (slash.hitBlocks.contains(pos.asLong())) continue;
                var boxes = shapes.computeIfAbsent(pos, key -> SlashBlockPassage.boxes(slash, player, key));
                if (boxes.size() > MAX_SHAPES) { accepted[n] = true; continue parts; }
                SlashGeometry.Contact first = null;
                for (AABB box : boxes) {
                    if (--budget.intersections < 0) { overflow = true; break; }
                    var hit = sweep.contact(box.move(pos));
                    if (hit != null && (first == null || hit.fraction() < first.fraction())) first = hit;
                }
                if (overflow) break;
                if (first != null) local.add(new Contact(first, pos, null));
            }
            if (overflow) break;
            // Thin-piece entity queries avoid scanning the empty centre of a large circular wave.
            Set<Entity> entities = new LinkedHashSet<>();
            for (AABB box : cover) {
                List<Entity> found = new ArrayList<>();
                level.getEntities(EntityTypeTest.forClass(Entity.class), box,
                        e -> target(e, player) && (fragmentCosts != null || !slash.hitEntities.contains(identity(e))) && !entities.contains(e),
                        found, SlashEntity.MAX_TARGETS + 1 - entities.size());
                entities.addAll(found);
                if (entities.size() > SlashEntity.MAX_TARGETS) break parts;
            }
            Set<UUID> freshTargets = new HashSet<>();
            for (Entity e : entities) if (!budget.radialTargets.contains(e.getUUID())) freshTargets.add(e.getUUID());
            if (freshTargets.size() > budget.targets) break;
            for (Entity e : entities) {
                if (--budget.intersections < 0) { overflow = true; break; }
                var hit = sweep.contact(e.getBoundingBox());
                if (hit != null) local.add(new Contact(hit, null, e));
            }
            if (overflow) break;
            budget.radialCells.addAll(freshCells); budget.cells -= freshCells.size();
            budget.radialTargets.addAll(freshTargets); budget.targets -= freshTargets.size();
            accepted[n] = true; limits[n] = field;
            for (Contact contact : local) all.add(new PartContact(n, contact));
        }
        // A linear stroke is one body. No side effects until all its pieces have been checked.
        if (linked) {
            for (boolean ready : accepted) if (!ready) return new Parts(limits, accepted, consumed);
            double earliest = Arrays.stream(limits).min().orElse(0);
            Arrays.fill(limits, earliest);
        }
        all.sort(Comparator.comparingDouble((PartContact p) -> time(timeline, p.part, p.contact.hit.fraction()))
                .thenComparingInt(p -> p.contact.block == null ? 1 : 0).thenComparingInt(PartContact::part));
        for (PartContact hit : all) {
            int n = hit.part; double t = hit.contact.hit.fraction();
            if (consumed[n] || limits[n] < 1 && t >= limits[n]) continue;
            if (slash.isRemoved() || !player.isAlive() || player.level() != slash.level() || slash.potential().remaining() <= 0) {
                stopAt(limits, timeline, time(timeline, n, t));
                break;
            }
            Outcome outcome = apply(slash, player, hit.contact, fragmentCosts == null ? 0 : fragmentCosts[n]);
            if (fragmentCosts != null && outcome == Outcome.HIT) consumed[n] = true;
            if (outcome == Outcome.BLOCKED || consumed[n]) {
                if (linked) Arrays.fill(limits, t); else limits[n] = t;
            }
            if (slash.potential().remaining() <= 0 || fragmentCosts == null && slash.fragments() != null) {
                stopAt(limits, timeline, time(timeline, n, t));
                break;
            }
        }
        return new Parts(limits, accepted, consumed);
    }

    private static double time(List<DoubleUnaryOperator> timeline, int part, double fraction) {
        return timeline == null ? fraction : timeline.get(part).applyAsDouble(fraction);
    }

    /** Convert a shared event time back into each independently quoted movement prefix. */
    private static void stopAt(double[] limits, List<DoubleUnaryOperator> timeline, double time) {
        for (int i = 0; i < limits.length; i++) {
            if (timeline == null) { limits[i] = Math.min(limits[i], time); continue; }
            var clock = timeline.get(i);
            if (clock.applyAsDouble(0) >= time) { limits[i] = 0; continue; }
            if (clock.applyAsDouble(limits[i]) <= time) continue;
            double low = 0, high = limits[i];
            for (int j = 0; j < 40; j++) {
                double middle = (low + high) * .5;
                if (clock.applyAsDouble(middle) <= time) low = middle; else high = middle;
            }
            limits[i] = low;
        }
    }

    private static UUID identity(Entity entity) { return (entity instanceof PartEntity<?> part ? part.getParent() : entity).getUUID(); }
    private static boolean target(Entity entity, ServerPlayer player) {
        Entity root = entity instanceof PartEntity<?> part ? part.getParent() : entity;
        return SlashInterception.target(root, player)
                || root instanceof LivingEntity && root != player && root.isAlive() && !root.isSpectator()
                && !root.isAlliedTo(player) && !(root instanceof Player other && !player.canHarmPlayer(other));
    }
}
