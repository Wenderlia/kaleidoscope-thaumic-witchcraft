package dev.senluo.witchcraft.slash;

import java.util.*;
import dev.senluo.witchcraft.motion.TravelStep;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
    private record Contact(int part, double time, SlashGeometry.Contact hit, BlockPos block, Entity entity) {
        Contact(int part, Timing clock, SlashGeometry.Contact hit, BlockPos block, Entity entity) {
            this(part, clock == null ? hit.fraction() : clock.at(hit.fraction()), hit, block, entity);
        }
    }
    private static final Comparator<Contact> ORDER = Comparator.comparingDouble(Contact::time)
            .thenComparingInt(c -> c.block == null ? 1 : 0).thenComparingInt(Contact::part);
    private SlashCollision() {}

    /** Reject pathological spatial work before even enumerating chunk coordinates. */
    static boolean bounded(AABB bounds) {
        double cells = SlashQueries.volume(bounds);
        return Double.isFinite(cells) && cells <= MAX_CELLS;
    }

    static double resolve(SlashEntity slash, ServerPlayer player, SlashQueries.Coverage sweep, Budget budget) {
        if (slash.hitBlocks.size() >= SlashEntity.MAX_BLOCKS || slash.hitEntities.size() >= SlashEntity.MAX_TARGETS) return 0;
        List<Contact> contacts = new ArrayList<>();
        double limit = collect(slash, player, sweep, budget, null, 0, null, false, contacts);
        if (limit < 0) { budget.exhausted = true; return 0; }
        contacts.sort(ORDER);
        for (Contact contact : contacts) {
            double t = contact.hit.fraction();
            if (limit < 1 && t >= limit) break;
            if (interrupted(slash, player)) return t;
            if (apply(slash, player, contact, sweep.sweep(), 0, Settlement.ORDINARY).outcome == Outcome.BLOCKED
                    || slash.potential().remaining() <= 0 || slash.fragments() != null) return t;
        }
        return limit;
    }

    private static boolean interrupted(SlashEntity slash, ServerPlayer player) {
        return slash.isRemoved() || !player.isAlive() || player.level() != slash.level() || slash.potential().remaining() <= 0;
    }

    /** Same native settlement for linear and radial geometry; a stopped part cannot cancel its neighbours. */
    private enum Outcome { PASS, HIT, BLOCKED }
    private enum Settlement { ORDINARY, SECTION, FRAGMENT }
    private record Applied(Outcome outcome, double cut) {
        static final Applied PASS = new Applied(Outcome.PASS, 0);
        static final Applied HIT = new Applied(Outcome.HIT, 0);
        static final Applied BLOCKED = new Applied(Outcome.BLOCKED, 0);
    }
    private static Applied apply(SlashEntity slash, ServerPlayer player, Contact contact, SlashGeometry.Sweep sweep,
                                 double fragmentCost, Settlement settlement) {
        boolean fragment = settlement == Settlement.FRAGMENT;
        ServerLevel level = player.serverLevel();
        if (contact.block != null) {
                if (slash.passesBlocks()) return Applied.PASS;
                BlockPos pos = contact.block;
                if (!level.hasChunkAt(pos)) return Applied.BLOCKED;
                if (slash.hitBlocks.contains(pos.asLong())) return Applied.PASS;
                if (slash.hitBlocks.size() >= SlashEntity.MAX_BLOCKS) return Applied.BLOCKED;
                var state = level.getBlockState(pos);
                if (state.isAir() || SlashBlockPassage.allows(slash, state, pos)) return Applied.PASS;
                float hardness = state.getDestroySpeed(level, pos);
                var impact = new BlockHitResult(contact.hit.point(), Direction.getNearest(slash.forward().scale(-1)), pos, false);
                if (slash.restrained() || !slash.cuts(hardness, fragment)) {
                    slash.blockedContact(contact.hit.point(), state, hardness); return Applied.BLOCKED;
                }
                if (!level.mayInteract(player, pos)
                        || NeoForge.EVENT_BUS.post(new ProjectileImpactEvent(slash, impact)).isCanceled()
                        || CommonHooks.fireBlockBreak(level, player.gameMode.getGameModeForPlayer(), player, pos, state).isCanceled()) {
                    slash.contact(contact.hit.point()); return Applied.BLOCKED;
                }
                // An event may replace the contacted block. Never destroy its replacement using an old quote.
                if (level.getBlockState(pos) != state) return Applied.BLOCKED;
                // Native player removal/drop hooks retain inventory drops and enchantment semantics.
                // Cutting is magical harvesting: hardness/potential replaces tool-tier eligibility.
                var blockEntity = level.getBlockEntity(pos);
                state = state.getBlock().playerWillDestroy(level, pos, state, player);
                // Paid spell harvesting is independent of the caster's creative-mode hand mining.
                if (!state.onDestroyedByPlayer(level, pos, player, true, level.getFluidState(pos))) return Applied.BLOCKED;
                state.getBlock().destroy(level, pos, state);
                state.getBlock().playerDestroy(level, player, pos, state, blockEntity, slash.tool());
                slash.hitBlocks.add(pos.asLong());
                slash.potential(slash.potential().afterBlock(fragment ? fragmentCost : slash.blockCost()));
                slash.contact(contact.hit.point());
                if (settlement == Settlement.ORDINARY) SlashFission.scatter(slash, Vec3.atCenterOf(pos), slash.blockCost());
                return Applied.HIT;
            } else {
                Entity entity = contact.entity;
                if (!target(entity, player) || !fragment && slash.hitEntities.contains(identity(entity))) return Applied.PASS;
                if (!fragment && slash.hitEntities.size() >= SlashEntity.MAX_TARGETS) return Applied.BLOCKED;
                if (!fragment) slash.hitEntities.add(identity(entity));
                if (NeoForge.EVENT_BUS.post(new ProjectileImpactEvent(slash, new EntityHitResult(entity, contact.hit.point()))).isCanceled()) return Applied.PASS;
                boolean intercepted = SlashInterception.remove(entity, player);
                if (intercepted || entity instanceof LivingEntity || entity instanceof PartEntity<?>) {
                    if (!intercepted && settlement == Settlement.SECTION) {
                        double available = slash.potential().remaining();
                        double proportion = SlashWeaponEffects.sectionHit(slash, player, entity);
                        if (proportion <= 0) return Applied.PASS;
                        slash.potential(slash.potential().afterBlock(available * proportion));
                        livingHit(slash, player, entity, contact.hit.point(), sweep);
                        return new Applied(Outcome.HIT, proportion);
                    }
                    if (!intercepted && !(fragment
                            ? SlashWeaponEffects.fissionHit(slash, player, entity, fragmentCost, 1)
                            : SlashWeaponEffects.hit(slash, player, entity))) return Applied.PASS;
                    slash.potential(fragment ? slash.potential().afterBlock(fragmentCost) : slash.potential().afterEntity());
                    if (intercepted) slash.contact(contact.hit.point());
                    else livingHit(slash, player, entity, contact.hit.point(), sweep);
                    if (settlement == Settlement.ORDINARY) SlashFission.scatter(slash,
                            entity.getBoundingBox().getCenter(), SlashPotential.ENTITY_COST);
                    return Applied.HIT;
                }
            }
        return Applied.PASS;
    }

    private static void livingHit(SlashEntity slash, ServerPlayer caster, Entity target, Vec3 point, SlashGeometry.Sweep sweep) {
        SlashHitParticle.emit(slash, caster, target.getId(), point, sweep);
        SlashSounds.livingContact(slash, caster, point);
    }

    record Cut(int part, Vec3 point, double proportion) {}
    record Parts(double[] fractions, boolean[] accepted, boolean[] consumed, boolean[] stopped, Cut cut) {}

    /** A zero rate is instantaneous formation; other fronts share elapsed simulation time. */
    record Timing(TravelStep travel, double start, double rate) {
        double at(double fraction) { return rate == 0 ? start : start + travel.spentAt(fraction) / rate; }
        double before(double time, double limit) {
            if (at(0) >= time) return 0;
            if (at(limit) <= time) return limit;
            double work = Math.max(0, (time - start) * rate);
            if (start + work / rate > time) work = Math.max(0, Math.nextDown(work));
            return travel.fractionForWork(work, limit);
        }
    }

    /** Collect first, then settle globally in time order. Unchecked parts wait, never move through gaps. */
    static Parts resolveParts(SlashEntity slash, ServerPlayer player, List<SlashQueries.Coverage> sweeps, Budget budget,
                              List<Timing> timeline) {
        return resolveMany(slash, player, sweeps, budget, timeline, null,
                slash.fission() ? Settlement.SECTION : Settlement.ORDINARY);
    }

    static Parts resolveFragments(SlashEntity slash, ServerPlayer player, List<SlashQueries.Coverage> sweeps,
                                  Budget budget, double[] costs) {
        if (costs.length != sweeps.size()) throw new IllegalArgumentException("Fragment cost count");
        return resolveMany(slash, player, sweeps, budget, null, costs, Settlement.FRAGMENT);
    }

    /** For one sweep, a negative result means its work budget was insufficient. */
    private static double collect(SlashEntity slash, ServerPlayer player, SlashQueries.Coverage geometry, Budget budget,
                                  Map<BlockPos, List<AABB>> shapes, int part, Timing timing, boolean fragment,
                                  List<Contact> contacts) {
        var sweep = geometry.sweep();
        double field = budget.space.segmentEntry(sweep.from(), sweep.to(), sweep.blade(),
                sweep.halfBefore(), sweep.halfAfter(), sweep.padding());
        if (field <= 0) return 0;
        var cover = geometry.boxes();
        if (cover.isEmpty() || cover.size() > budget.queries) return -1;
        budget.queries -= cover.size();
        Set<BlockPos> cells = new LinkedHashSet<>();
        List<BlockPos> freshCells = new ArrayList<>();
        if (!slash.passesBlocks()) {
            int visits = geometry.countCellsUntilOver(budget.radialVisits);
            if (visits > budget.radialVisits) return -1;
            budget.radialVisits -= visits;
            for (AABB box : cover) for (BlockPos pos : SlashQueries.cells(box)) {
                BlockPos key = pos.immutable();
                if (!cells.add(key) || budget.radialCells.contains(key)) continue;
                freshCells.add(key);
                if (freshCells.size() > budget.cells) return -1;
            }
        }
        if (budget.intersections <= 0) return -1;
        List<Contact> local = new ArrayList<>();
        for (BlockPos pos : cells) {
            if (slash.hitBlocks.contains(pos.asLong())) continue;
            var boxes = shapes == null ? SlashBlockPassage.boxes(slash, player, pos)
                    : shapes.computeIfAbsent(pos, key -> SlashBlockPassage.boxes(slash, player, key));
            if (boxes.size() > MAX_SHAPES) return 0;
            SlashGeometry.Contact first = null;
            for (AABB box : boxes) {
                if (--budget.intersections < 0) return -1;
                var hit = sweep.contact(box.move(pos));
                if (hit != null && (first == null || hit.fraction() < first.fraction())) first = hit;
            }
            if (first != null) local.add(new Contact(part, timing, first, pos, null));
        }
        Set<Entity> entities = new LinkedHashSet<>();
        for (AABB box : cover) {
            List<Entity> found = new ArrayList<>();
            player.serverLevel().getEntities(EntityTypeTest.forClass(Entity.class), box,
                    e -> target(e, player) && (fragment || !slash.hitEntities.contains(identity(e))) && !entities.contains(e),
                    found, SlashEntity.MAX_TARGETS + 1 - entities.size());
            entities.addAll(found);
            if (entities.size() > SlashEntity.MAX_TARGETS) return -1;
        }
        Set<UUID> freshTargets = new HashSet<>();
        for (Entity entity : entities) if (!budget.radialTargets.contains(entity.getUUID())) freshTargets.add(entity.getUUID());
        if (freshTargets.size() > budget.targets) return -1;
        for (Entity entity : entities) {
            if (--budget.intersections < 0) return -1;
            var hit = sweep.contact(entity.getBoundingBox());
            if (hit != null) local.add(new Contact(part, timing, hit, null, entity));
        }
        budget.radialCells.addAll(freshCells); budget.cells -= freshCells.size();
        budget.radialTargets.addAll(freshTargets); budget.targets -= freshTargets.size();
        contacts.addAll(local);
        return field;
    }

    private static Parts resolveMany(SlashEntity slash, ServerPlayer player, List<SlashQueries.Coverage> sweeps,
                                     Budget budget, List<Timing> timeline, double[] fragmentCosts, Settlement settlement) {
        int count = sweeps.size();
        double[] limits = new double[count];
        boolean[] accepted = new boolean[count], consumed = new boolean[count], stopped = new boolean[count];
        List<Contact> all = new ArrayList<>();
        Map<BlockPos, List<AABB>> shapes = new HashMap<>();
        for (int part = 0; part < count; part++) {
            double limit = collect(slash, player, sweeps.get(part), budget, shapes, part,
                    timeline == null ? null : timeline.get(part), fragmentCosts != null, all);
            if (limit < 0) break;
            limits[part] = limit;
            accepted[part] = true;
            stopped[part] = limit < 1;
        }
        all.sort(ORDER);
        for (Contact hit : all) {
            int n = hit.part; double t = hit.hit.fraction();
            if (consumed[n] || limits[n] < 1 && t >= limits[n]) continue;
            if (interrupted(slash, player)) {
                stopAt(limits, timeline, hit.time);
                Arrays.fill(stopped, true);
                break;
            }
            Applied applied = apply(slash, player, hit, sweeps.get(n).sweep(), fragmentCosts == null ? 0 : fragmentCosts[n], settlement);
            Outcome outcome = applied.outcome();
            if (applied.cut() > 0) {
                double[] priorLimits = limits.clone();
                stopAt(limits, timeline, hit.time);
                // A cut is a re-collection boundary, not a wall. Preserve only stops already reached.
                for (int i = 0; i < limits.length; i++) if (limits[i] < priorLimits[i] - 1.0E-10) stopped[i] = false;
                return new Parts(limits, accepted, consumed, stopped, new Cut(n, hit.hit.point(), applied.cut()));
            }
            if (fragmentCosts != null && outcome == Outcome.HIT) consumed[n] = true;
            if (outcome == Outcome.BLOCKED || consumed[n]) {
                limits[n] = t; stopped[n] = true;
            }
            if (slash.potential().remaining() <= 0 || fragmentCosts == null && slash.fragments() != null) {
                stopAt(limits, timeline, hit.time);
                Arrays.fill(stopped, true);
                break;
            }
        }
        return new Parts(limits, accepted, consumed, stopped, null);
    }

    /** Convert a shared event time back into each independently quoted movement prefix. */
    private static void stopAt(double[] limits, List<Timing> timeline, double time) {
        for (int i = 0; i < limits.length; i++) {
            if (timeline == null) { limits[i] = Math.min(limits[i], time); continue; }
            limits[i] = timeline.get(i).before(time, limits[i]);
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
