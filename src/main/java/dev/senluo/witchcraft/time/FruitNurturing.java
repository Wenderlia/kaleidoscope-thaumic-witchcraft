package dev.senluo.witchcraft.time;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** World adapter for fixed fruit sites. Discovery is event-invalidated and shares a native-tick budget. */
public final class FruitNurturing {
    private static final int VISITS_PER_TICK = 256;
    private static final int SLICE = 32;
    private static final int ACTIVE_SEARCHES = 8;
    private final ServerLevel level;
    private final TreeLogic trees;
    private final Map<BlockPos, Entry> entries = new HashMap<>();
    private final ArrayDeque<Search> searches = new ArrayDeque<>();
    private final ArrayDeque<Search> active = new ArrayDeque<>();
    private BlockPos staining;
    private static final class Entry {
        final LivingEntity core;
        Map<BlockPos, Integer> sources = Map.of();
        final Set<UUID> ready = new HashSet<>();
        long revision;
        long queued = -1;
        boolean waiting;
        long retryAt = Long.MAX_VALUE;
        boolean giant;
        Entry(LivingEntity core) { this.core = core; this.giant = core.tissue.giant; }
    }
    FruitNurturing(ServerLevel level, TreeLogic trees) { this.level = level; this.trees = trees; }

    void observed(LivingEntity core, Map<BlockPos, Integer> nodes) {
        Entry entry = entries.computeIfAbsent(core.getBlockPos().immutable(), p -> new Entry(core));
        entry.sources = Map.copyOf(nodes);
        invalidate(core.getBlockPos());
    }
    void forget(BlockPos core) {
        Entry entry = entries.remove(core);
        if (entry != null) {
            entry.revision++;
            searches.removeIf(search -> search.entry == entry);
            active.removeIf(search -> search.entry == entry);
        }
    }
    void invalidate(BlockPos core) {
        Entry entry = entries.get(core);
        if (entry != null) { entry.revision++; entry.ready.clear(); }
    }
    boolean staining(BlockPos pos) { return pos.equals(staining); }
    void fruitRemoved(BlockPos core) {
        Entry entry = entries.get(core);
        if (entry != null && entry.waiting) entry.queued = -1;
    }
    public boolean permits(FruitEntity fruit) {
        Entry entry = entries.get(fruit.corePos);
        if (entry == null || entry.core.isRemoved() || !entry.core.tissue.identity.equals(fruit.coreIdentity)
                || fruit.source == null || !entry.ready.contains(fruit.sideIdentity)) return false;
        FruitSites.Site site = entry.core.tissue.fruitSites.get(fruit.source);
        return site != null && site.identity.equals(fruit.sideIdentity) && site.grade == fruit.targetGrade
                && site.fruit != null && entry.core.getBlockPos().offset(site.fruit).equals(fruit.getBlockPos());
    }
    void tick(LivingEntity core) {
        Entry entry = entries.get(core.getBlockPos());
        if (entry == null) return;
        if (entry.giant != core.tissue.giant) { entry.giant = core.tissue.giant; invalidate(core.getBlockPos()); }
        // A loaded chunk can regain simulation eligibility without a load event.
        if (level.getGameTime() >= entry.retryAt) { entry.queued = -1; entry.retryAt = Long.MAX_VALUE; }
        if (entry.queued != entry.revision) {
            entry.queued = entry.revision;
            entry.waiting = false;
            Map<BlockPos, Integer> sources = new TreeMap<>(Comparator.comparingLong(BlockPos::asLong));
            sources.putAll(entry.sources);
            if (core.tissue.giant) sources.put(BlockPos.ZERO, 5);
            // Retain one live search per source and drop obsolete generations immediately.
            searches.removeIf(search -> search.entry == entry);
            active.removeIf(search -> search.entry == entry);
            for (var source : sources.entrySet()) {
                FruitSites.Site previous = core.tissue.fruitSites.get(source.getKey());
                if (previous != null && previous.grade != source.getValue() && previous.fruit != null) {
                    BlockPos old = core.getBlockPos().offset(previous.fruit);
                    if (!loaded(old) || level.getBlockEntity(old) instanceof FruitEntity fruit
                            && previous.identity.equals(fruit.sideIdentity) && !fruit.harvested) {
                        // Its prepaid crop keeps its grade until harvest; do not allocate a second site.
                        entry.waiting = true;
                        entry.retryAt = Math.min(entry.retryAt, level.getGameTime() + 100);
                        entry.ready.add(previous.identity);
                        continue;
                    }
                }
                FruitSites.Site site = core.tissue.fruitSites.ensure(source.getKey(), source.getValue());
                if (site == null) continue;
                if (previous != site) core.setChanged();
                if (entry.ready.contains(site.identity)) continue;
                searches.addLast(new Search(entry, source.getKey(), site));
            }
        }
        for (var source : entry.sources.keySet()) spawn(entry, source);
        if (core.tissue.giant) spawn(entry, BlockPos.ZERO);
    }
    private boolean loaded(BlockPos pos) { return level.hasChunkAt(pos) && level.shouldTickBlocksAt(pos); }
    private boolean current(Entry entry) {
        BlockPos pos = entry.core.getBlockPos();
        return !entry.core.isRemoved() && loaded(pos) && level.getBlockEntity(pos) == entry.core
                && entry.core.tree() && entry.core.tissue.supported();
    }
    private boolean available(Entry entry, FruitSites.Site site, BlockPos fruit) {
        if (!loaded(fruit) || !loaded(fruit.above())
                || entry.core.tissue.shapeSpace.pruned(fruit.subtract(entry.core.getBlockPos()).asLong())
                || trees.plannedLeaf(entry.core, fruit.above()) != TreeLogic.LeafSupport.PRESENT
                || !level.getBlockState(fruit.above()).is(TemporalContent.TIME_LEAVES.get())) return false;
        BlockState state = level.getBlockState(fruit);
        if (state.isAir()) return true;
        return level.getBlockEntity(fruit) instanceof FruitEntity existing && !existing.harvested
                && entry.core.tissue.identity.equals(existing.coreIdentity) && site.identity.equals(existing.sideIdentity);
    }
    private void spawn(Entry entry, BlockPos source) {
        if (!current(entry)) return;
        var site = entry.core.tissue.fruitSites.get(source);
        if (site == null || site.fruit == null || !entry.ready.contains(site.identity)) return;
        BlockPos pos = entry.core.getBlockPos().offset(site.fruit);
        if (!available(entry, site, pos) || !level.getBlockState(pos).isAir()) return;
        if (level.setBlock(pos, TemporalContent.FRUIT.get().defaultBlockState(), 3)
                && level.getBlockEntity(pos) instanceof FruitEntity fruit) fruit.bindNode(entry.core, source, site);
    }
    void flush() {
        if (!level.tickRateManager().runsNormally()) return;
        int budget = VISITS_PER_TICK;
        while (budget > 0) {
            while (active.size() < ACTIVE_SEARCHES && !searches.isEmpty()) {
                Search next = searches.removeFirst();
                if (next.valid()) active.addLast(next);
            }
            if (active.isEmpty()) break;
            Search search = active.removeFirst();
            if (!search.valid()) continue;
            int used = search.advance(Math.min(SLICE, budget));
            budget -= Math.max(1, used);
            if (!search.finished && search.valid()) active.addLast(search);
            else if (!search.entry.ready.contains(search.site.identity)) {
                search.entry.waiting = true;
                if (search.unknown) search.entry.retryAt = Math.min(search.entry.retryAt, level.getGameTime() + 100);
            }
        }
    }

    private final class Search {
        final Entry entry;
        final long revision;
        final BlockPos source;
        final FruitSites.Site site;
        ArrayDeque<BlockPos> frontier;
        Map<BlockPos, BlockPos> parent;
        boolean unknown, finished, initialized;
        BlockPos preferred;
        Search(Entry entry, BlockPos source, FruitSites.Site site) {
            this.entry = entry; this.revision = entry.revision; this.source = source; this.site = site;
        }
        private void initialize() {
            initialized = true;
            frontier = new ArrayDeque<>(); parent = new HashMap<>();
            if (site.fruit != null) {
                BlockPos old = entry.core.getBlockPos().offset(site.fruit);
                if (!loaded(old) || !loaded(old.above())) { unknown = true; finished = true; return; }
                if (available(entry, site, old)) preferred = old.above();
                else if (level.getBlockEntity(old) instanceof FruitEntity fruit && site.identity.equals(fruit.sideIdentity)) {
                    // Never create a second fruit while the first still holds paid work.
                    finished = true; return;
                }
            }
            BlockPos start = entry.core.getBlockPos().offset(source);
            frontier.add(start); parent.put(start, start);
        }
        boolean valid() { return entry.revision == revision && entries.get(entry.core.getBlockPos()) == entry && current(entry); }
        boolean inTree(BlockPos pos) {
            BlockPos relative = pos.subtract(entry.core.getBlockPos());
            return relative.equals(BlockPos.ZERO) || (TreeShapeSpace.contains(entry.core.tissue, relative)
                    || trees.ownedBy(entry.core, pos)) && !entry.core.tissue.shapeSpace.pruned(relative.asLong());
        }
        boolean permitted(BlockPos pos, BlockState state) {
            return pos.equals(entry.core.getBlockPos()) || state.is(TemporalContent.TIME_LEAVES.get())
                    || (site.grade == 5 ? state.is(TreeWood.LOGS) : FruitWoods.permits(state, site.grade));
        }
        int advance(int budget) {
            if (!initialized) initialize();
            int used = 0;
            while (!finished && used < budget && !frontier.isEmpty()) {
                BlockPos pos = frontier.removeFirst(); used++;
                if (!loaded(pos)) { unknown = true; continue; }
                BlockState state = level.getBlockState(pos);
                if (!inTree(pos) || !trees.mayUse(entry.core, pos) || !permitted(pos, state)) continue;
                boolean leaf = state.is(TemporalContent.TIME_LEAVES.get());
                if (leaf && (preferred == null || preferred.equals(pos)) && available(entry, site, pos.below())) {
                    // A previously fixed site needs a proven loaded path, not a fresh nearest-site proof.
                    if (!unknown || preferred != null) commit(pos);
                    finished = true; break;
                }
                for (BlockPos offset : TreeShapeSpace.NEIGHBORS) {
                    BlockPos next = pos.offset(offset);
                    if (parent.containsKey(next) || !inTree(next) || !trees.mayUse(entry.core, next)) continue;
                    if (!loaded(next)) { unknown = true; continue; }
                    BlockState nextState = level.getBlockState(next);
                    // Leaf crowns never bridge into another disconnected wood branch.
                    if (leaf && !nextState.is(TemporalContent.TIME_LEAVES.get()) || !permitted(next, nextState)) continue;
                    if (parent.size() >= TreeShapeSpace.MAX_PARTS) { finished = true; return used; }
                    parent.put(next, pos); frontier.addLast(next);
                }
            }
            if (frontier.isEmpty()) {
                if (preferred != null && !unknown && !finished) {
                    // Only a proven broken old path permits choosing a new nearest leaf.
                    preferred = null; parent.clear();
                    BlockPos start = entry.core.getBlockPos().offset(source);
                    frontier.add(start); parent.put(start,start);
                } else finished = true;
            }
            return used;
        }
        private void commit(BlockPos leaf) {
            ArrayList<BlockPos> path = new ArrayList<>();
            for (BlockPos pos = leaf;; pos = parent.get(pos)) {
                if (pos == null || !loaded(pos) || !inTree(pos) || !permitted(pos, level.getBlockState(pos))) return;
                path.add(pos);
                if (pos.equals(parent.get(pos))) break;
            }
            // Inspect the entire source-to-leaf path before any visual mutation.
            BlockPos start = entry.core.getBlockPos().offset(source);
            if (site.grade < 5 && FruitWoods.nodeGrade(level.getBlockState(start)) != site.grade) return;
            if (!available(entry, site, leaf.below())) return;
            try {
                for (BlockPos pos : path) {
                    BlockState old = level.getBlockState(pos), next = FruitWoods.channel(old, site.grade);
                    staining = pos;
                    if (next != old) {
                        if (!level.setBlock(pos, next, 3) || !level.getBlockState(pos).equals(next)) return;
                        trees.restoreInscription(entry.core, pos);
                    }
                }
            } finally { staining = null; }
            if (!valid()) return;
            BlockPos relative = leaf.below().subtract(entry.core.getBlockPos());
            // A live old fruit cannot be abandoned by path reselection.
            if (site.fruit != null && !site.fruit.equals(relative)) {
                BlockPos old = entry.core.getBlockPos().offset(site.fruit);
                if (!loaded(old) || level.getBlockEntity(old) instanceof FruitEntity fruit && site.identity.equals(fruit.sideIdentity)) return;
            }
            site.fruit = relative; entry.core.setChanged(); entry.ready.add(site.identity);
            spawn(entry, source);
        }
    }
}
