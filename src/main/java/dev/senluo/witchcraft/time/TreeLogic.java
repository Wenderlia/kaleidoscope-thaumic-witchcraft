package dev.senluo.witchcraft.time;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;

/** Tree-shape lifecycle and bounded world work; the portable shape owns material and provenance. */
public final class TreeLogic {
    private final ServerLevel level;
    private final TreeClaims claims;
    public final FruitNurturing nurturing;
    private final Map<BlockPos, BlockPos> owners = new HashMap<>();
    private final Set<BlockPos> cores = new HashSet<>();
    private final Map<BlockPos, Tissue> registeredTissues = new HashMap<>();
    private final Map<BlockPos,Set<BlockPos>> fruitsByCore = new HashMap<>();
    private final Map<BlockPos,Map<BlockPos,BlockState>> plans = new HashMap<>();
    private final Map<BlockPos,Set<BlockPos>> retiredByCore = new HashMap<>();
    private final Map<Long,Set<BlockPos>> woodCoreChunks = new HashMap<>();
    private final Set<BlockPos> dirtyWood = new LinkedHashSet<>();
    private final Set<BlockPos> cutTrees = new LinkedHashSet<>();
    private final Map<BlockPos,Set<BlockPos>> reopenSeeds = new HashMap<>();
    private final Map<BlockPos,Set<BlockPos>> livePaths = new HashMap<>();
    private final Map<BlockPos,Map<BlockPos,BlockState>> graftEdits = new HashMap<>();
    private final Map<BlockPos,Discovery> discoveries = new HashMap<>();
    private final Map<BlockPos,ArrayDeque<BlockPos>> liveFrontiers = new LinkedHashMap<>();
    private record Crown(long revision, List<BlockPos> positions) {}
    private final Map<BlockPos,Crown> crowns = new HashMap<>();
    private static final class Discovery {
        final Set<BlockPos> seen = new HashSet<>(), connected = new HashSet<>();
        final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        final Map<BlockPos,Integer> nodes = new LinkedHashMap<>();
        boolean reopening;
        Discovery(BlockPos origin) { seen.add(origin); connected.add(origin); queue.add(origin); }
    }
    private boolean writingShape;
    private final Map<net.minecraft.world.entity.player.Player,PruneAttempt> pruning = new WeakHashMap<>();
    public record PruneAttempt(BlockPos part, BlockPos core, UUID identity, long tick) {}
    private record ExteriorKey(BlockPos origin, boolean giant) {}
    // Transition preflight and current-tree repairs share geometry, but not their envelopes.
    private final Map<ExteriorKey,Set<BlockPos>> exteriorCache=new LinkedHashMap<>(32,.75F,true){
        @Override protected boolean removeEldestEntry(Map.Entry<ExteriorKey,Set<BlockPos>> entry){return size()>32;}
    };
    private final Set<ExteriorKey> exteriorComputing = new HashSet<>();
    private long geometryRevision;
    // Plans describe geometry. Pending cells, separately, describe unfinished work.
    private final Set<BlockPos> dirtyPlans = new HashSet<>();
    private final Map<BlockPos,Integer> phases = new HashMap<>();
    private final Map<BlockPos,Set<BlockPos>> pending = new HashMap<>();
    private final Map<BlockPos,Map<Long,Set<BlockPos>>> sleeping = new HashMap<>();
    private final LinkedHashSet<BlockPos> repairs = new LinkedHashSet<>();
    private static final DustParticleOptions GOLD = new DustParticleOptions(new Vector3f(0.94F, 0.70F, 0.25F), 1.0F);

    TreeLogic(ServerLevel level) { this.level = level; this.claims = TreeClaims.get(level); this.nurturing = new FruitNurturing(level, this); }

    private BlockPos ownerAt(BlockPos part) {
        BlockPos saved = claims.ownerAt(part);
        return saved != null ? saved : owners.get(part);
    }
    boolean ownedBy(LivingEntity core, BlockPos pos) { return core.getBlockPos().equals(ownerAt(pos)); }
    boolean mayUse(LivingEntity core, BlockPos pos) {
        BlockPos owner = ownerAt(pos); return owner == null || owner.equals(core.getBlockPos());
    }
    public enum LeafSupport { PRESENT, ABSENT, UNKNOWN }

    /** Read the same intact-tree geometry as regeneration, never current damage or repair funds. */
    public LeafSupport plannedLeaf(LivingEntity core, BlockPos pos) {
        BlockPos origin = core.getBlockPos();
        if (!claims.supported() || !core.tissue.supported() || registeredTissues.get(origin) != core.tissue)
            return LeafSupport.UNKNOWN;
        if (core.tissue.shapeSpace.pruned(pos.subtract(origin).asLong()) || !mayUse(core, pos))
            return LeafSupport.ABSENT;
        if (core.tissue.shapeSpace.calculatingCuts()) return LeafSupport.UNKNOWN;
        var plan = plans.get(origin);
        if (plan == null || dirtyPlans.contains(origin) || !Objects.equals(phases.get(origin), phase(core.tissue)))
            return LeafSupport.UNKNOWN;
        BlockState expected = plan.get(pos);
        return expected != null && expected.is(TemporalContent.TIME_LEAVES.get())
                ? LeafSupport.PRESENT : LeafSupport.ABSENT;
    }

    private boolean claimPart(LivingEntity core, BlockPos part) {
        if (!claims.claim(core, part)) return false;
        owners.put(part.immutable(), core.getBlockPos().immutable());
        return true;
    }

    private void enqueue(BlockPos core, BlockPos pos) {
        BlockPos key = pos.immutable();
        pending.computeIfAbsent(core, unused -> new LinkedHashSet<>()).add(key);
        repairs.add(key);
    }

    private void forget(BlockPos core, BlockPos pos) {
        var cells = pending.get(core);
        if (cells != null) cells.remove(pos);
        var buckets = sleeping.get(core);
        if (buckets != null) for (var bucket : buckets.values()) bucket.remove(pos);
        repairs.remove(pos);
    }

    private void defer(BlockPos core, BlockPos pos, long fee) {
        sleeping.computeIfAbsent(core, unused -> new HashMap<>())
                .computeIfAbsent(fee, unused -> new LinkedHashSet<>()).add(pos);
    }

    // Obstructed/missing-support cells can change eligibility when their surroundings change.
    private static final long GEOMETRY = -1;
    private void wakeGeometry(BlockPos origin) {
        var buckets = sleeping.get(origin);
        if (buckets == null) return;
        var cells = buckets.remove(GEOMETRY);
        if (cells != null) repairs.addAll(cells);
    }

    private void wake(LivingEntity core) {
        var buckets = sleeping.get(core.getBlockPos());
        if (buckets == null) return;
        long balance = TemporalWorld.get(level).colony(core).spendable();
        buckets.entrySet().removeIf(entry -> {
            if (!entry.getValue().isEmpty() && (entry.getKey() == GEOMETRY || entry.getKey() > balance)) return false;
            repairs.addAll(entry.getValue()); return true;
        });
        if (buckets.isEmpty()) sleeping.remove(core.getBlockPos());
    }

    static int phase(Tissue tissue) {
        return TreeShapeSpace.phase(tissue);
    }

    private void reconcilePlan(LivingEntity core) {
        BlockPos origin = core.getBlockPos();
        updateShape(core);
        var next = target(core);
        var old = plans.put(origin, next);
        for (var entry : next.entrySet()) {
            BlockPos pos = entry.getKey();
            if (!claimPart(core, pos)) continue;
            if (!level.hasChunkAt(pos) || !matchesPlan(level.getBlockState(pos), entry.getValue()))
                enqueue(origin,pos);
        }
        if (old != null) for (BlockPos pos : old.keySet()) if (!next.containsKey(pos) && !retiredByCore.getOrDefault(origin,Set.of()).contains(pos)) forget(origin,pos);
        phases.put(origin,phase(core.tissue));
    }

    /** Only currently executable paid repairs in the next native 30 seconds need a reserve. */
    public long restorationReserve(LivingEntity core) {
        long total = 0;
        int ready = 0;
        Map<BlockPos, BlockState> plan = plans.getOrDefault(core.getBlockPos(), Map.of());
        for (BlockPos pos : List.copyOf(pending.getOrDefault(core.getBlockPos(), Set.of()))) {
            BlockState expected = plan.get(pos);
            if (expected == null || expected.is(TemporalContent.FRUIT.get()) || !canRestoreConnected(core,pos)) continue;
            if (!level.hasChunkAt(pos) || !level.shouldTickBlocksAt(pos)
                    || !core.getBlockPos().equals(ownerAt(pos))) continue;
            BlockState state = level.getBlockState(pos);
            if (state.is(expected.getBlock())) continue;
            // A live fruit node already fulfils its ordinary-channel restoration plan.
            if (FruitWoods.nodeGrade(state) != 0 && matchesPlan(state, expected)) continue;
            if (TreeLeavesBlock.placedByPlayer(state)) continue;
            boolean chalk = canCoverChalk(core, pos, state);
            if (expected.is(TemporalContent.TIME_LEAVES.get())
                    && !expected.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)
                    && !chalk
                    && TreeLeavesBlock.supportDistance(level, pos) >= net.minecraft.world.level.block.LeavesBlock.DECAY_DISTANCE) continue;
            boolean formed = core.tissue.formedTree.contains(pos.subtract(core.getBlockPos()).asLong());
            boolean ownMaterial = formed && (state.is(dev.senluo.witchcraft.time.TreeWood.LOGS) || state.is(TemporalContent.TIME_LEAVES.get()));
            boolean soil = replaceableRootSoil(core, pos, state, expected);
            if (!state.isAir() && !ownMaterial && !soil && !chalk) continue;
            long relative = pos.subtract(core.getBlockPos()).asLong();
            boolean first = !core.tissue.formedTree.contains(relative)
                    || expected.is(dev.senluo.witchcraft.time.TreeWood.LOGS) && !core.tissue.formedLogs.contains(relative);
            if (first && (core.tissue.giantGrowing || core.initialGiantBuild)) continue;
            if (!exterior(core.getBlockPos()).contains(pos)) continue;
            total += expected.is(TemporalContent.TIME_LEAVES.get()) ? TreeBalance.LEAF_REPAIR : TreeBalance.LOG_REPAIR;
            if (chalk && !level.getBlockState(pos.below()).is(dev.senluo.witchcraft.time.TreeWood.LOGS)) total += TreeBalance.LOG_REPAIR;
            if (++ready >= 16 * 20 * FruitFeeding.RESTORATION_SECONDS) break;
        }
        return total;
    }

    public static Map<BlockPos, BlockState> shape(BlockPos origin) {
        return GiantTreeShape.small(origin);
    }

    public void addFruit(FruitEntity fruit) {
        if (fruit.corePos != null) fruitsByCore.computeIfAbsent(fruit.corePos.immutable(), p -> new HashSet<>())
                .add(fruit.getBlockPos().immutable());
    }
    public void removeFruit(FruitEntity fruit) {
        nurturing.fruitRemoved(fruit.corePos);
        var positions = fruitsByCore.get(fruit.corePos);
        if (positions != null) {
            positions.remove(fruit.getBlockPos());
            if (positions.isEmpty()) fruitsByCore.remove(fruit.corePos);
        }
    }
    public List<FruitEntity> fruits(LivingEntity core) {
        List<FruitEntity> result=new ArrayList<>();
        for(BlockPos pos:fruitsByCore.getOrDefault(core.getBlockPos(),Set.of()))
            if(level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof FruitEntity fruit
                    && !fruit.isRemoved() && core.tissue.identity.equals(fruit.coreIdentity))result.add(fruit);
        result.sort(Comparator.comparingLong(f->f.getBlockPos().asLong()));
        return result;
    }

    static Map<BlockPos,BlockState> template(Tissue tissue, int phase) {
        return TreeShapeSpace.template(tissue,phase);
    }

    private void updateShape(LivingEntity core) {
        TreeShapeSpace space = core.tissue.shapeSpace;
        int stage = phase(core.tissue);
        if (space.atPhase(stage) || space.calculatingCuts()) return;
        if (space.commit(space.preview(template(core.tissue,stage),stage))) {
            core.setChanged(); markDiscovery(core.getBlockPos());
            TemporalWorld.get(level).colony(core).reserveTick = Long.MIN_VALUE;
        }
    }

    private Map<BlockPos,BlockState> target(LivingEntity core) {
        Map<BlockPos,BlockState> result=new LinkedHashMap<>();
        core.tissue.shapeSpace.cells().forEach((p,state)->result.put(core.getBlockPos().offset(BlockPos.of(p)),state));
        return result;
    }

    private void markDiscovery(BlockPos origin) {
        dirtyWood.add(origin.immutable()); discoveries.remove(origin);
        livePaths.remove(origin); liveFrontiers.remove(origin);
    }

    private void recordEdit(BlockPos origin, BlockPos pos) {
        if (level.restoringBlockSnapshots) {
            var edits = graftEdits.get(origin); if (edits != null) edits.remove(pos);
            var seeds = reopenSeeds.get(origin); if (seeds != null) seeds.remove(pos);
            return;
        }
        graftEdits.computeIfAbsent(origin, p -> new HashMap<>()).put(pos.immutable(),level.getBlockState(pos));
        if (!level.captureBlockSnapshots && level.getBlockEntity(origin) instanceof LivingEntity core
                && !core.tissue.shapeSpace.calculatingCuts()
                && core.tissue.shapeSpace.has(pos.subtract(origin).asLong()))
            rememberPart(core,pos,level.getBlockState(pos),false,true);
    }

    private Set<BlockPos> markWoodAround(BlockPos changed) {
        Set<BlockPos> affected = new LinkedHashSet<>();
        int chunkX = changed.getX() >> 4, chunkZ = changed.getZ() >> 4;
        // Both artificial wood and the natural tree envelope fit within neighboring core chunks.
        for (int x = chunkX - 1; x <= chunkX + 1; x++) for (int z = chunkZ - 1; z <= chunkZ + 1; z++) {
            for (BlockPos origin : woodCoreChunks.getOrDefault(net.minecraft.world.level.ChunkPos.asLong(x,z), Set.of())) {
                if (level.hasChunkAt(origin) && level.getBlockEntity(origin) instanceof LivingEntity core
                        && (TreeShapeSpace.contains(core.tissue, changed.subtract(origin))
                        || origin.equals(ownerAt(changed)) || naturalLog(core, changed))) affected.add(origin);
            }
        }
        affected.forEach(this::markDiscovery);
        return affected;
    }

    private void invalidateFruitAround(BlockPos pos) {
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        for (int x = cx - 1; x <= cx + 1; x++) for (int z = cz - 1; z <= cz + 1; z++)
            for (BlockPos origin : woodCoreChunks.getOrDefault(net.minecraft.world.level.ChunkPos.asLong(x,z), Set.of()))
                if (Math.abs((long)pos.getX() - origin.getX()) <= 16 && Math.abs((long)pos.getZ() - origin.getZ()) <= 16
                        && pos.getY() >= origin.getY() - 5 && pos.getY() <= origin.getY() + 24) nurturing.invalidate(origin);
    }

    private boolean naturalLog(LivingEntity core, BlockPos pos) {
        if (!core.tissue.giant && !core.tissue.giantGrowing) return false;
        var part = GiantTreeShape.layout(core.tissue.treeFacing).get(pos.subtract(core.getBlockPos()));
        return part != null && part.state().is(dev.senluo.witchcraft.time.TreeWood.LOGS);
    }

    private LivingEntity inscriptionOwner(BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof LivingEntity core && core.tree() && core.tissue.supported()) return core;
        BlockPos origin = ownerAt(pos);
        return origin != null && level.hasChunkAt(origin) && level.getBlockEntity(origin) instanceof LivingEntity core
                && core.tree() && core.tissue.supported() ? core : null;
    }

    public void rememberInscription(BlockPos pos, dev.senluo.witchcraft.ritual.ChalkMarks.Mark mark) {
        LivingEntity core = inscriptionOwner(pos);
        if (core != null && core.tissue.shapeSpace.rememberCarving(core.tissue, pos.subtract(core.getBlockPos()), mark.asCarving())) core.setChanged();
    }
    void restoreInscription(LivingEntity core, BlockPos pos) {
        dev.senluo.witchcraft.ritual.RitualChalk.restoreCarving(level, pos,
                core.tissue.shapeSpace.carvings().get(pos.subtract(core.getBlockPos()).asLong()));
    }

    /** Explicit placement can rejoin an entire discarded component, including its existing far end. */
    public void placedWood(BlockPos pos, net.minecraft.world.entity.LivingEntity placer) {
        for (BlockPos origin : markWoodAround(pos)) {
            if (!(level.getBlockEntity(origin) instanceof LivingEntity core)) continue;
            // A local ordering barrier: an earlier cut must finish before this edit can reconnect it.
            settleCuts(core);
            recordEdit(origin,pos);
            if (placer instanceof net.minecraft.world.entity.player.Player player
                    && !level.restoringBlockSnapshots
                    && dev.senluo.witchcraft.ritual.TreeOwnership.mayManage(player,core.tissue))
                reopenSeeds.computeIfAbsent(origin,p -> new HashSet<>()).add(pos.immutable());
        }
    }

    public void beforeWoodRemoval(BlockPos pos) {
        // Known cells are O(1). A just-placed, not-yet-observed component is captured once before mining.
        BlockPos owner=ownerAt(pos);
        if(owner!=null && plans.getOrDefault(owner,Map.of()).containsKey(pos)
                && !graftEdits.getOrDefault(owner,Map.of()).containsKey(pos)
                && reopenSeeds.getOrDefault(owner,Set.of()).isEmpty())return;
        for(BlockPos origin:markWoodAround(pos))
            if(level.getBlockEntity(origin) instanceof LivingEntity core) {
                settleCuts(core);
                observeConnectedWood(core, Integer.MAX_VALUE);
            }
    }

    private void rememberWood(LivingEntity core,BlockPos pos,BlockState state) { rememberPart(core,pos,state,false,false); }

    public void rememberSign(net.minecraft.world.level.block.entity.SignBlockEntity sign) {
        if (!TreeSignMemory.supports(sign.getBlockState())) return;
        BlockPos origin=ownerAt(sign.getBlockPos());
        if (origin != null && level.hasChunkAt(origin) && level.getBlockEntity(origin) instanceof LivingEntity core
                && core.tissue.shapeSpace.rememberSign(sign.getBlockPos().subtract(origin).asLong(),TreeSignMemory.snapshot(sign))) core.setChanged();
    }

    private void rememberPart(LivingEntity core,BlockPos pos,BlockState state,boolean reopen,boolean edit) {
        BlockPos relative=pos.subtract(core.getBlockPos());
        if(!TreeShapeSpace.material(state)||!mayUse(core,pos)||!TreeShapeSpace.contains(core.tissue,relative))return;
        if(reopen)core.tissue.shapeSpace.reattach(core.tissue,relative);
        if(core.tissue.shapeSpace.pruned(relative.asLong())||!claimPart(core,pos))return;
        boolean changed=edit ? core.tissue.shapeSpace.remember(core.tissue,relative,state)
                : core.tissue.shapeSpace.observe(core.tissue,relative,state);
        if (!core.tissue.shapeSpace.has(relative.asLong())) return;
        if (TreeSignMemory.supports(state) && level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign)
            changed |= core.tissue.shapeSpace.rememberSign(relative.asLong(),TreeSignMemory.snapshot(sign));
        changed |= core.tissue.formedTree.add(relative.asLong());
        if(state.is(TreeWood.LOGS)) changed |= core.tissue.formedLogs.add(relative.asLong());
        var chunk=dev.senluo.witchcraft.ritual.RitualChalk.chunk(level,pos);
        var marks=chunk==null?null:chunk.getExistingDataOrNull(dev.senluo.witchcraft.ritual.RitualContent.CHALK_MARKS);
        if(state.is(TreeWood.LOGS)&&marks!=null&&marks.supported()&&marks.get(pos)!=null&&marks.get(pos).medium()!=0)
            changed |= core.tissue.shapeSpace.rememberCarving(core.tissue,relative,marks.get(pos).asCarving());
        var plan=plans.get(core.getBlockPos());
        if(plan!=null)plan.put(pos.immutable(),core.tissue.shapeSpace.cells().get(relative.asLong()));
        var retired=retiredByCore.get(core.getBlockPos());if(retired!=null)retired.remove(pos);
        if(changed){core.setChanged();TemporalWorld.get(level).colony(core).reserveTick=Long.MIN_VALUE;}
    }

    /** Event-driven discovery of all allowed materials, including an entire formerly detached component. */
    private int observeConnectedWood(LivingEntity core, int budget) {
        BlockPos origin=core.getBlockPos();
        if(core.tissue.shapeSpace.calculatingCuts() || !dirtyWood.contains(origin))return 0;
        commitCuts(core);
        Discovery job = discoveries.computeIfAbsent(origin, Discovery::new);
        int used = 0;
        while(!job.queue.isEmpty() && used < budget) {
            BlockPos p=job.queue.removeFirst(); used++;
            for(BlockPos d:TreeShapeSpace.NEIGHBORS){
                BlockPos n=p.offset(d),relative=n.subtract(origin);
                if(!TreeShapeSpace.contains(core.tissue,relative)||!job.seen.add(n)||!level.hasChunkAt(n)||!mayUse(core,n))continue;
                BlockState state=level.getBlockState(n);
                boolean edit = TreeShapeSpace.sameForm(state,graftEdits.getOrDefault(origin,Map.of()).get(n));
                boolean seed = edit && reopenSeeds.getOrDefault(origin,Set.of()).contains(n);
                if(!TreeShapeSpace.material(state)||core.tissue.shapeSpace.pruned(relative.asLong())&&!job.reopening&&!seed)continue;
                if (seed && !job.reopening) {
                    // Revisit skipped boundaries once a real, authorized bridge has reached the core.
                    job.reopening = true; job.seen.clear(); job.seen.addAll(job.connected); job.seen.add(n);
                    job.queue.clear(); job.queue.addAll(job.connected);
                }
                rememberPart(core,n,state,job.reopening,edit);
                if(!core.tissue.shapeSpace.has(relative.asLong()))continue;
                job.connected.add(n);job.queue.addLast(n);
                int grade=FruitWoods.nodeGrade(state);if(grade>0)job.nodes.put(relative,grade);
            }
            if (discoveries.get(origin) != job) return used;
        }
        if (job.queue.isEmpty()) {
            discoveries.remove(origin); dirtyWood.remove(origin); reopenSeeds.remove(origin); graftEdits.remove(origin);
            livePaths.put(origin,job.connected);
            nurturing.observed(core,job.nodes);
            invalidateExterior(origin); wakeGeometry(origin);
        }
        return used;
    }

    private void settleCuts(LivingEntity core) {
        core.tissue.shapeSpace.settleCuts(); commitCuts(core);
    }

    /** Save/transplant is a local ordering barrier; no other tree is visited. */
    void beforeSnapshot(LivingEntity core) {
        if (level.captureBlockSnapshots || level.restoringBlockSnapshots
                || registeredTissues.get(core.getBlockPos()) != core.tissue) return;
        settleCuts(core);
        if (dirtyWood.contains(core.getBlockPos())) observeConnectedWood(core,Integer.MAX_VALUE);
    }

    private void advanceCuts(){
        int turns = Math.min(4,cutTrees.size());
        for (int i=0; i<turns; i++) {
            BlockPos origin = cutTrees.iterator().next(); cutTrees.remove(origin);
            if(level.hasChunkAt(origin)&&level.getBlockEntity(origin) instanceof LivingEntity core){
                core.tissue.shapeSpace.advanceCuts(128);
                commitCuts(core);
                if (core.tissue.shapeSpace.pendingSplit()) cutTrees.add(origin);
            } else cutTrees.add(origin);
        }
    }

    private void advanceConnections() {
        int turns = Math.min(4,dirtyWood.size());
        for (int i=0; i<turns; i++) {
            BlockPos origin = dirtyWood.iterator().next();
            if (level.hasChunkAt(origin) && level.getBlockEntity(origin) instanceof LivingEntity core)
                observeConnectedWood(core,64);
            if (dirtyWood.remove(origin)) dirtyWood.add(origin);
        }
        turns = Math.min(4,liveFrontiers.size());
        for (int i=0; i<turns; i++) {
            BlockPos origin = liveFrontiers.keySet().iterator().next();
            if (level.hasChunkAt(origin) && level.getBlockEntity(origin) instanceof LivingEntity core)
                advanceLive(core,64);
            var queue = liveFrontiers.remove(origin);
            if (queue != null) liveFrontiers.put(origin,queue);
        }
    }

    private void commitCuts(LivingEntity core){
        BlockPos origin=core.getBlockPos();
        if(core.tissue.shapeSpace.calculatingCuts())return;
        if(!core.tissue.shapeSpace.pendingSplit()){cutTrees.remove(origin);return;}
        Set<Long> removed=core.tissue.shapeSpace.takeRemoved();
        var plan=plans.get(origin);var retired=retiredByCore.get(origin);
        for(long p:removed){BlockPos pos=origin.offset(BlockPos.of(p));
            if(plan!=null)plan.remove(pos);if(retired!=null)retired.remove(pos);
            owners.remove(pos,origin);forget(origin,pos);
        }
        cutTrees.remove(origin);markDiscovery(origin);invalidateExterior(origin);nurturing.invalidate(origin);
        core.setChanged();TemporalWorld.get(level).colony(core).reserveTick=Long.MIN_VALUE;
        // Fruit entities already check the committed leaf target on every native tick.
        // Reuse that callback; a save barrier must not remove world blocks during serialization.
    }

    /** Each new physical connection is expanded once, within the world's shared traversal budget. */
    private int advanceLive(LivingEntity core, int budget) {
        BlockPos origin = core.getBlockPos();
        var queue = liveFrontiers.get(origin); var result = livePaths.get(origin);
        if (queue == null || result == null || dirtyWood.contains(origin)) return 0;
        var plan = plans.getOrDefault(origin,Map.of()); int used = 0;
        while(!queue.isEmpty() && used < budget){BlockPos p=queue.removeFirst(); used++;
            for(BlockPos d:TreeShapeSpace.NEIGHBORS){BlockPos n=p.offset(d);
                if (!plan.containsKey(n)) continue;
                if (pending.getOrDefault(origin,Set.of()).contains(n)) repairs.add(n);
                if(result.contains(n)||!level.hasChunkAt(n)||!mayUse(core,n))continue;
                if(TreeShapeSpace.material(level.getBlockState(n))){result.add(n);queue.addLast(n);}
            }
        }
        if (queue.isEmpty()) liveFrontiers.remove(origin);
        return used;
    }
    private boolean canRestoreConnected(LivingEntity core,BlockPos pos){
        if(core.tissue.shapeSpace.calculatingCuts() || dirtyWood.contains(core.getBlockPos()))return false;
        Set<BlockPos> live=livePaths.getOrDefault(core.getBlockPos(),Set.of());
        for(BlockPos d:TreeShapeSpace.NEIGHBORS)if(live.contains(pos.offset(d)))return true;
        return false;
    }

    private Set<BlockPos> exterior(BlockPos origin) {
        LivingEntity core = level.getBlockEntity(origin) instanceof LivingEntity living ? living : null;
        boolean giant = core != null && (core.tissue.giant || core.tissue.giantGrowing);
        return exterior(origin, core, giant);
    }
    private Set<BlockPos> exterior(BlockPos origin, LivingEntity core, boolean giant) {
        ExteriorKey key = new ExteriorKey(origin.immutable(), giant);
        Set<BlockPos> cached = exteriorCache.get(key);
        if (cached != null) return cached;
        if (!exteriorComputing.add(key)) return Set.of();
        long revision = geometryRevision;
        try {
            // World reads can process chunk lifecycle callbacks. Never run them inside a Map remapping function.
            Set<BlockPos> calculated = calculateExterior(key.origin(), core, giant);
            if (revision != geometryRevision) return Set.of();
            exteriorCache.put(key, calculated);
            return calculated;
        } finally {
            exteriorComputing.remove(key);
        }
    }

    private void invalidateExterior(BlockPos origin) {
        geometryRevision++;
        exteriorCache.remove(new ExteriorKey(origin, false));
        exteriorCache.remove(new ExteriorKey(origin, true));
    }
    private Set<BlockPos> giantExterior(LivingEntity core) {
        return exterior(core.getBlockPos(), core, true);
    }
    private Set<BlockPos> calculateExterior(BlockPos origin, LivingEntity core, boolean giant) {
        int radius = (giant ? TreeShapeSpace.GIANT_RADIUS : TreeShapeSpace.TREE_RADIUS) + 1;
        int width = radius * 2 + 1, bottom = giant ? -4 : 0, height = giant ? 29 : 7;
        int total = width * height * width, tail = 0;
        boolean[] open = new boolean[total], seen = new boolean[total];
        int[] queue = new int[total];
        for (int x=0; x<width; x++) for (int y=0; y<height; y++) for (int z=0; z<width; z++) {
            int i=(x*height+y)*width+z;
            BlockPos pos=origin.offset(x-radius,y+bottom,z-radius);
            if (!level.isInWorldBounds(pos) || !level.hasChunkAt(pos)) continue;
            BlockState state=level.getBlockState(pos);
            boolean own=origin.equals(ownerAt(pos));
            open[i]=state.isAir() || state.is(TemporalContent.FRUIT.get())
                    || own && TreeShapeSpace.material(state)
                    || giant && (pos.getY() <= origin.getY() && TreeGrowthMaterial.substrate(state)
                        || canCoverChalk(core, pos, state));
            if (open[i] && (x==0 || x==width-1 || y==0 || y==height-1 || z==0 || z==width-1)) {
                seen[i]=true; queue[tail++]=i;
            }
        }
        Set<BlockPos> result=new HashSet<>();
        for (int head=0; head<tail; head++) {
            int i=queue[head],x=i/(height*width),y=i/width%height,z=i%width;
            result.add(origin.offset(x-radius,y+bottom,z-radius));
            for (int axis=0; axis<3; axis++) for (int sign=-1; sign<=1; sign+=2) {
                int c=axis==0?x:axis==1?y:z, limit=axis==1?height:width;
                if (c+sign<0 || c+sign>=limit) continue;
                int next=i+sign*(axis==0?height*width:axis==1?width:1);
                if (open[next] && !seen[next]) {seen[next]=true;queue[tail++]=next;}
            }
        }
        return result;
    }

    public void invalidateGeometry(){geometryRevision++;exteriorCache.clear(); for (var core : cores) { markDiscovery(core); wakeGeometry(core); nurturing.invalidate(core); }}
    public void chunkChanged(net.minecraft.world.level.ChunkPos chunk) {
        geometryRevision++;
        // A distant player's chunk activity must not reset every tree's stable fruit routes.
        // The exterior adds one cell beyond the 16-block graft radius: at a chunk edge it reaches two chunks away.
        int reach = (TreeShapeSpace.GIANT_RADIUS + 1 + 15) / 16;
        for (int x=chunk.x-reach;x<=chunk.x+reach;x++) for (int z=chunk.z-reach;z<=chunk.z+reach;z++)
            for (BlockPos origin : woodCoreChunks.getOrDefault(net.minecraft.world.level.ChunkPos.asLong(x,z), Set.of())) {
                markDiscovery(origin); invalidateExterior(origin); wakeGeometry(origin); nurturing.invalidate(origin);
            }
    }
    public void geometryChanged(BlockPos pos,BlockState old,BlockState state) {
        // In-flight computations have no cache entry yet; they must also observe invalidation.
        if (!old.equals(state)) geometryRevision++;
        boolean leafChange = (old.is(TemporalContent.TIME_LEAVES.get()) || state.is(TemporalContent.TIME_LEAVES.get()))
                && (old.getBlock() != state.getBlock()
                    || old.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)
                        != state.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)
                    || old.getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS)
                        != state.getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS));
        boolean axisChange = old.hasProperty(net.minecraft.world.level.block.RotatedPillarBlock.AXIS)
                && state.hasProperty(net.minecraft.world.level.block.RotatedPillarBlock.AXIS)
                && old.getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS)
                    != state.getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS);
        // Native distance ticks and visual GREAT_TREE updates do not require a component rebuild.
        if (!writingShape && !nurturing.staining(pos) && (!old.is(state.getBlock()) || leafChange || axisChange)
                && (TreeShapeSpace.material(old) || TreeShapeSpace.material(state))) {
            Set<BlockPos> affected = markWoodAround(pos);
            if (TreeShapeSpace.material(state) || level.restoringBlockSnapshots) for (BlockPos origin : affected)
                recordEdit(origin,pos);
        }
        if (!nurturing.staining(pos) && (!old.is(state.getBlock()) || leafChange)
                && !old.is(TemporalContent.FRUIT.get()) && !state.is(TemporalContent.FRUIT.get())) invalidateFruitAround(pos);
        if (!writingShape && !old.equals(state) && !sleeping.isEmpty()) {
            int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
            for (int x=cx-2; x<=cx+2; x++) for(int z=cz-2; z<=cz+2; z++)
                for (BlockPos origin : woodCoreChunks.getOrDefault(net.minecraft.world.level.ChunkPos.asLong(x,z), Set.of()))
                    if (Math.abs((long)pos.getX()-origin.getX())<=17 && Math.abs((long)pos.getZ()-origin.getZ())<=17
                            && pos.getY()>=origin.getY()-5 && pos.getY()<=origin.getY()+26) wakeGeometry(origin);
        }
        BlockPos woodOwner = ownerAt(pos);
        var cachedPlan = plans.get(woodOwner);
        // A door opening or stair/fence neighbour update edits one recorded form, not its connectivity.
        if (old.is(state.getBlock()) && state.getBlock() instanceof YearsBuildingEdits.Part
                && woodOwner != null && level.hasChunkAt(woodOwner) && level.getBlockEntity(woodOwner) instanceof LivingEntity core
                && core.tissue.shapeSpace.transform(core.tissue,pos.subtract(woodOwner),old,state)) {
            if (cachedPlan != null) cachedPlan.put(pos.immutable(),core.tissue.shapeSpace.cells().get(pos.subtract(woodOwner).asLong()));
            core.setChanged();
        }
        if (cachedPlan != null && cachedPlan.containsKey(pos)) {
            if (!matchesPlan(state, cachedPlan.get(pos))) enqueue(woodOwner,pos);
            else forget(woodOwner,pos);
        }
        if (nurturing.staining(pos) && woodOwner != null && level.hasChunkAt(woodOwner)
                && level.getBlockEntity(woodOwner) instanceof LivingEntity core
                && core.tissue.shapeSpace.transform(core.tissue,pos.subtract(woodOwner),old,state)) {
            if (cachedPlan != null) cachedPlan.put(pos.immutable(),core.tissue.shapeSpace.cells().get(pos.subtract(woodOwner).asLong()));
            core.setChanged(); forget(woodOwner,pos);
        }
        for(ExteriorKey key:List.copyOf(exteriorCache.keySet())) {
            BlockPos origin=key.origin();
            if(Math.abs((long)pos.getX()-origin.getX())>TreeShapeSpace.GIANT_RADIUS+1
                    ||Math.abs((long)pos.getZ()-origin.getZ())>TreeShapeSpace.GIANT_RADIUS+1
                    ||pos.getY()<origin.getY()-4||pos.getY()>origin.getY()+24)continue;
            boolean own=origin.equals(ownerAt(pos));
            boolean before=old.isAir()||old.is(TemporalContent.FRUIT.get())||own&&TreeShapeSpace.material(old);
            boolean after=state.isAir()||state.is(TemporalContent.FRUIT.get())||own&&TreeShapeSpace.material(state);
            if(before!=after || TreeGrowthMaterial.substrate(old)!=TreeGrowthMaterial.substrate(state)
                    || old.is(dev.senluo.witchcraft.ritual.RitualContent.CHALK_SURFACE.get())
                    || state.is(dev.senluo.witchcraft.ritual.RitualContent.CHALK_SURFACE.get())) exteriorCache.remove(key);
        }
    }

    public void register(LivingEntity core) {
        if (!core.tissue.supported()) { unregister(core.getBlockPos()); return; }
        Tissue previous = registeredTissues.get(core.getBlockPos());
        if (previous == core.tissue && cores.contains(core.getBlockPos())) return;
        if (previous != null && previous != core.tissue) unregister(core.getBlockPos());
        if (!claims.register(core)) return;
        if (!core.tree() || !cores.add(core.getBlockPos().immutable())) return;
        registeredTissues.put(core.getBlockPos().immutable(), core.tissue);
        woodCoreChunks.computeIfAbsent(net.minecraft.world.level.ChunkPos.asLong(core.getBlockPos()), p -> new HashSet<>())
                .add(core.getBlockPos().immutable());
        markDiscovery(core.getBlockPos());
        restoreInscription(core, core.getBlockPos());
        if(core.tissue.giantGrowing)core.initialGiantBuild=true;
        updateShape(core);
        Map<BlockPos,BlockState> plan=target(core);
        plans.put(core.getBlockPos().immutable(),plan);
        if (core.tissue.formedTree.isEmpty() && !core.tissue.giantGrowing) {
            for(BlockPos part:plan.keySet()) {
                long offset=part.subtract(core.getBlockPos()).asLong();core.tissue.formedTree.add(offset);
                if(plan.get(part).is(dev.senluo.witchcraft.time.TreeWood.LOGS))core.tissue.formedLogs.add(offset);
            }
        }
        phases.put(core.getBlockPos(), phase(core.tissue));
        for (BlockPos part : plan.keySet()) {
            if (claimPart(core, part)) enqueue(core.getBlockPos(), part);
        }
    }

    public void unregister(BlockPos core) {
        nurturing.forget(core);
        registeredTissues.remove(core);
        if (!cores.remove(core)) return;
        livePaths.remove(core); liveFrontiers.remove(core); discoveries.remove(core); graftEdits.remove(core);
        crowns.remove(core);
        cutTrees.remove(core); reopenSeeds.remove(core); dirtyWood.remove(core); dirtyPlans.remove(core); phases.remove(core); sleeping.remove(core); pending.remove(core);
        long chunk = net.minecraft.world.level.ChunkPos.asLong(core);
        var indexed = woodCoreChunks.get(chunk);
        if (indexed != null) { indexed.remove(core); if (indexed.isEmpty()) woodCoreChunks.remove(chunk); }
        var plan=plans.remove(core);
        var retired=retiredByCore.remove(core);
        if(retired!=null)for(BlockPos pos:retired)if(owners.remove(pos,core))repairs.remove(pos);
        invalidateExterior(core);
        if(plan==null)return;
        for (BlockPos part : plan.keySet()) if (core.equals(ownerAt(part))) {
            owners.remove(part);
            repairs.remove(part);
        }
    }

    /** Release the world site only after actual core removal, not a chunk unload. */
    public void releaseSite(LivingEntity core) {
        BlockPos origin = core.getBlockPos();
        unregister(origin);
        dev.senluo.witchcraft.ritual.SustainedRitual.coreRemoved(level, origin, core.tissue.identity);
        claims.remove(origin, core.tissue.identity);
        // A former neighbour may now discover connected wood that belonged to this core.
        // Either recording envelope can reach sixteen blocks from its core.
        int cx = origin.getX() >> 4, cz = origin.getZ() >> 4;
        for (int x = cx - 2; x <= cx + 2; x++) for (int z = cz - 2; z <= cz + 2; z++)
            for (BlockPos neighbour : woodCoreChunks.getOrDefault(net.minecraft.world.level.ChunkPos.asLong(x, z), Set.of()))
                if (Math.abs((long)neighbour.getX() - origin.getX()) <= 2 * TreeShapeSpace.GIANT_RADIUS
                        && Math.abs((long)neighbour.getZ() - origin.getZ()) <= 2 * TreeShapeSpace.GIANT_RADIUS) { markDiscovery(neighbour); dirtyPlans.add(neighbour); }
    }

    public void removed(BlockPos part) { BlockPos core = ownerAt(part); if (core != null && cores.contains(core)) { enqueue(core,part); repairs.addFirst(part.immutable()); } }

    /** Capture the owner's intent before normal mining; tool type is irrelevant. */
    public void preparePrune(BlockPos pos, net.minecraft.world.entity.player.Player player) {
        pruning.remove(player);
        BlockState state = level.getBlockState(pos);
        if (!player.isShiftKeyDown() || !TreeShapeSpace.material(state)) return;
        BlockPos origin = ownerAt(pos);
        if (origin == null || !level.hasChunkAt(origin) || !(level.getBlockEntity(origin) instanceof LivingEntity core)
                || !core.hasOwner() || !dev.senluo.witchcraft.ritual.TreeOwnership.mayManage(player, core.tissue)) return;
        pruning.put(player, new PruneAttempt(pos.immutable(), origin.immutable(), core.tissue.identity, level.getGameTime()));
    }

    public PruneAttempt takePrune(BlockPos pos, net.minecraft.world.entity.player.Player player) {
        PruneAttempt attempt = pruning.remove(player);
        return attempt != null && attempt.part().equals(pos) && attempt.tick() == level.getGameTime() ? attempt : null;
    }

    /** Only the successful, authoritative removal can commit a permanent exclusion. */
    public void finishPrune(PruneAttempt attempt, net.minecraft.world.entity.player.Player player) {
        if (attempt == null || !level.hasChunkAt(attempt.core())
                || !(level.getBlockEntity(attempt.core()) instanceof LivingEntity core)
                || !core.tissue.identity.equals(attempt.identity()) || !core.hasOwner()
                || !dev.senluo.witchcraft.ritual.TreeOwnership.mayManage(player, core.tissue)) return;
        BlockPos pos = attempt.part();
        if (!core.tissue.shapeSpace.prune(core.tissue, pos.subtract(core.getBlockPos()))) return;
        var plan=plans.get(attempt.core());if(plan!=null)plan.remove(pos);
        forget(attempt.core(),pos);
        cutTrees.add(attempt.core());markDiscovery(attempt.core());core.setChanged();
    }

    public boolean begin(LivingEntity source) {
        Tissue tissue = source.tissue;
        if (!tissue.supported() || source.amber() || tissue.tree || !tissue.established() || tissue.rootStage != 4) return false;
        if (tissue.treeGrowing) return true;
        if (!DevelopmentTime.woodySite(source) || tissue.naturalTicks < TreeBalance.ENTRY_TICKS) return false;
        tissue.treeGrowing = true;
        tissue.treeTicks = tissue.naturalTicks;
        source.changed();
        return true;
    }

    public String beginGiant(LivingEntity core) {
        Tissue t=core.tissue;
        if(!t.supported())return "data_unavailable";
        if(t.giant)return "max_stage";
        if(t.giantGrowing)return "giant_growing";
        if(!core.tree() || t.naturalTicks < TreeBalance.GIANT_TICKS)return "tree_time_short";
        if(t.bond == null || !t.bond.supported())return "ownership.owner_only";
        if(!t.readyForGiant())return "giant_sides";
        if (!hasTransitionSpace(core)) return "tree_wait_space";
        t.giantGrowing=true;t.giantTicks=t.naturalTicks;
        core.initialGiantBuild=true;
        invalidateExterior(core.getBlockPos());
        reconcilePlan(core);
        core.changed();return "giant_growing";
    }

    private boolean giantReady(LivingEntity core) {
        if(core.tissue.shapeSpace.coreOnly())return true;
        BlockPos origin = core.getBlockPos();
        if (dirtyWood.contains(origin)) return false;
        var candidate = core.tissue.shapeSpace.cells();
        Set<BlockPos> connected = livePaths.getOrDefault(origin,Set.of());
        for(BlockPos relative:GiantTreeShape.required(core.tissue.treeFacing))if(candidate.containsKey(relative.asLong())) {
            BlockPos p=origin.offset(relative);
            if(!connected.contains(p) || !level.hasChunkAt(p)
                    || !matchesPlan(level.getBlockState(p),candidate.get(relative.asLong())))return false;
        }
        Crown crown = crowns.get(origin);
        if (crown == null || crown.revision() != core.tissue.shapeSpace.revision()) {
            List<BlockPos> positions = new ArrayList<>();
            candidate.forEach((key,state) -> {
                BlockPos relative = BlockPos.of(key);
                if (relative.getY()>8 && state.is(TemporalContent.TIME_LEAVES.get())) positions.add(relative);
            });
            crown = new Crown(core.tissue.shapeSpace.revision(),List.copyOf(positions)); crowns.put(origin,crown);
        }
        int leaves=0, required=Math.min(8,crown.positions().size());
        for (BlockPos relative : crown.positions()) {
            BlockPos p = origin.offset(relative);
            if (connected.contains(p) && level.hasChunkAt(p) && matchesPlan(level.getBlockState(p),candidate.get(relative.asLong()))) leaves++;
            if (leaves >= required) return true;
        }
        return leaves >= required;
    }

    /** The natural transition and paid preflight use exactly the same loaded-space rules. */
    public boolean hasTransitionSpace(LivingEntity source) {
        if (!source.tissue.supported() || source.amber() || !DevelopmentTime.woodySite(source)) return false;
        BlockPos origin = source.getBlockPos();
        if (!source.tree()) {
            Set<BlockPos> reachable = exterior(origin);
            return shape(origin).keySet().stream().noneMatch(pos -> !level.isInWorldBounds(pos)
                    || !level.hasChunkAt(pos) || !level.shouldTickBlocksAt(pos) || !reachable.contains(pos)
                    || ownerAt(pos) != null || !level.getBlockState(pos).isAir());
        }
        if(source.tissue.shapeSpace.coreOnly())return true;
        if (source.tissue.shapeSpace.calculatingCuts() || dirtyWood.contains(origin)) return false;
        var candidate = source.tissue.shapeSpace.preview(template(source.tissue,3),3).cells();
        Set<BlockPos> accessible = giantExterior(source);
        for (BlockPos relative : GiantTreeShape.required(source.tissue.treeFacing)) if (candidate.containsKey(relative.asLong())) {
            BlockPos pos = origin.offset(relative);
            BlockState expected = candidate.get(relative.asLong());
            if (!level.isInWorldBounds(pos) || !level.hasChunkAt(pos) || !level.shouldTickBlocksAt(pos)
                    || !accessible.contains(pos)) return false;
            BlockState state = level.getBlockState(pos);
            boolean own = origin.equals(ownerAt(pos)) && source.tissue.formedTree.contains(relative.asLong());
            if (!state.isAir() && !replaceableRootSoil(source, pos, state, expected)
                    && !canCoverChalk(source, pos, state)
                    && !(own && TreeShapeSpace.material(state))) return false;
        }
        return true;
    }

    public boolean form(LivingEntity source) {
        Tissue tissue = source.tissue;
        if (!tissue.supported() || source.amber() || tissue.tree || !tissue.treeGrowing || tissue.treeTicks < TreeBalance.ENTRY_TICKS) return false;
        BlockPos origin = source.getBlockPos();
        var parts = shape(origin);
        if (!hasTransitionSpace(source)) return false;
        // Retain the very same organism and its account; the old crop must not drop it.
        source.transforming = true;
        if (!level.setBlock(origin, TemporalContent.CORE.get().defaultBlockState().setValue(LivingBlock.AGE,4), 3)) {
            source.transforming = false;
            return false;
        }
        LivingEntity core = (LivingEntity) level.getBlockEntity(origin);
        tissue.tree = true;
        tissue.treeGrowing = false;
        tissue.treeTicks = 0;
        GiantRoots.synchronize(tissue);
        core.tissue = tissue;
        core.cropCostCredit = source.cropCostCredit;
        core.changed();
        TemporalWorld.get(level).add(core);
        boolean before = writingShape; writingShape = true;
        try { for (var part : parts.entrySet()) level.setBlock(part.getKey(), part.getValue(), 3); }
        finally { writingShape = before; }
        markDiscovery(origin);
        sparkle(origin.above(2));
        return true;
    }

    /** Called from the bounded native once-per-second scheduler, never a TIAB extra tick. */
    public void tick(LivingEntity entity) {
        if (!entity.tissue.supported()) return;
        if (entity.tree() && entity.tissue.shapeSpace.calculatingCuts()) return;
        if (!entity.tree()) { begin(entity); form(entity); return; }
        register(entity);
        if (!claims.supported() || !cores.contains(entity.getBlockPos())) return;
        commitCuts(entity);
        if (!entity.tissue.giant && !entity.tissue.giantGrowing && entity.tissue.naturalTicks >= TreeBalance.GIANT_TICKS)
            beginGiant(entity);
        if (dirtyPlans.remove(entity.getBlockPos()) || !Objects.equals(phases.get(entity.getBlockPos()),phase(entity.tissue)))
            reconcilePlan(entity);
        // Pending leaps keep their age and formed wood, but cannot finish or grow
        // further while the owner or one of the six mature direct roots is missing.
        if(entity.tissue.giantGrowing && entity.tissue.readyForGiant()
                && entity.tissue.giantTicks>=TreeBalance.GIANT_TICKS && giantReady(entity)) {
            entity.tissue.giant=true;entity.tissue.giantGrowing=false;entity.tissue.giantTicks=0;
            GiantRoots.restore(entity.tissue);
            reconcilePlan(entity);
            entity.changed();TemporalWorld.get(level).invalidate();sparkle(entity.getBlockPos().above(8));
        }
        wake(entity);
        nurturing.tick(entity);
        List<FruitEntity> fruit = fruits(entity);
        FruitHarvest.tick(entity, fruit);
        entity.cropFarming.tick(entity);
        entity.shears.settle(entity);
        queueRetiredParts(entity);
    }

    private void queueRetiredParts(LivingEntity core) {
        if(!core.tissue.giant && !core.tissue.giantGrowing)return;
        BlockPos origin=core.getBlockPos();
        if (retiredByCore.containsKey(origin)) return;
        Set<BlockPos> retired=retiredByCore.computeIfAbsent(origin.immutable(),p->{
            var current=GiantTreeShape.layout(core.tissue.treeFacing);
            Set<BlockPos> old=new HashSet<>();
            for(long packed:core.tissue.formedTree) {
                BlockPos relative=BlockPos.of(packed);
                if(!current.containsKey(relative) && !core.tissue.shapeSpace.has(packed)
                        && !core.tissue.shapeSpace.pruned(packed))old.add(origin.offset(relative));
            }
            return old;
        });
        for(BlockPos pos:retired) {
            claimPart(core, pos);
            if(origin.equals(ownerAt(pos)))enqueue(origin,pos);
        }
    }

    private static boolean matchesPlan(BlockState actual, BlockState planned) {
        if (planned.getBlock() instanceof YearsBuildingEdits.Part) return TreeShapeSpace.sameForm(actual,planned);
        if (planned.is(TemporalContent.FRUIT.get())) return actual.is(planned.getBlock());
        if (FruitWoods.nodeGrade(actual) != 0) return FruitWoods.regrowth(actual).equals(planned);
        return actual.is(TemporalContent.TIME_LEAVES.get()) && planned.is(actual.getBlock())
                ? actual.equals(TreeLeavesBlock.reconcile(actual, planned)) : actual.equals(planned);
    }

    /** Share a sixteen-position native-tick budget between restoration and retiring obsolete tree parts. */
    public void flush() {
        advanceCuts();
        advanceConnections();
        nurturing.flush();
        pruning.values().removeIf(attempt -> attempt.tick() != level.getGameTime());
        int count = 0;
        Map<BlockPos,Set<BlockPos>> accessible=new HashMap<>();
        while (!repairs.isEmpty() && count++ < 16) {
            BlockPos part = repairs.removeFirst();
            BlockPos origin = ownerAt(part);
            if (origin == null || !cores.contains(origin)) continue;
            if (!level.hasChunkAt(origin) || !level.hasChunkAt(part) || !level.shouldTickBlocksAt(origin) || !level.shouldTickBlocksAt(part)) {
                defer(origin,part,0); continue;
            }
            if (!(level.getBlockEntity(origin) instanceof LivingEntity core) || !core.tree() || !core.tissue.tree || !core.tissue.supported()) continue;
            if(core.tissue.shapeSpace.calculatingCuts() || dirtyWood.contains(origin)){defer(origin,part,GEOMETRY);continue;}
            if (registeredTissues.get(origin) != core.tissue) { register(core); continue; }
            BlockState expected = plans.getOrDefault(origin,Map.of()).get(part);
            BlockState current = level.getBlockState(part);
            long relative=part.subtract(origin).asLong();
            boolean formed=core.tissue.formedTree.contains(relative);
            if (core.tissue.giantGrowing && !core.tissue.readyForGiant() && (!formed || expected == null)) { defer(origin,part,0); continue; }
            if (expected == null) {
                Set<BlockPos> retired=retiredByCore.getOrDefault(origin,Set.of());
                if(!retired.contains(part)){forget(origin,part);continue;}
                if(level.getBlockState(part.below()).is(TemporalContent.FRUIT.get())){defer(origin,part,GEOMETRY);continue;}
                // Only recorded tree material is retired; leave replacements and other structures intact.
                if(current.is(dev.senluo.witchcraft.time.TreeWood.LOGS) || current.is(TemporalContent.TIME_LEAVES.get())
                        && !TreeLeavesBlock.placedByPlayer(current)) {
                    if(!level.removeBlock(part,false)){defer(origin,part,0);continue;}
                }
                retired.remove(part);owners.remove(part,origin);forget(origin,part);
                continue;
            }
            if(expected.is(TemporalContent.FRUIT.get())){forget(origin,part);continue;}
            if (expected.getBlock() instanceof net.minecraft.world.level.block.DoorBlock && !current.is(expected.getBlock())) {
                count += restoreDoor(core,part,expected,17-count,accessible)-1;
                continue;
            }
            if (TreeLeavesBlock.placedByPlayer(current) && !current.is(expected.getBlock())) { defer(origin,part,GEOMETRY); continue; }
            // A live node remains functional. Only restore the ordinary channel after it is gone.
            if (FruitWoods.nodeGrade(current) != 0 && matchesPlan(current, expected)) {
                restoreInscription(core, part);
                forget(origin,part); continue;
            }
            if(current.is(expected.getBlock())) {
                BlockState restored = current.is(TemporalContent.TIME_LEAVES.get())
                        ? TreeLeavesBlock.reconcile(current, expected) : expected;
                if(formed&&!matchesPlan(current,restored)) {
                    boolean before = writingShape; writingShape = true;
                    try { level.setBlock(part,restored,3); } finally { writingShape = before; }
                    if (restored.is(TemporalContent.TIME_LEAVES.get())) level.scheduleTick(part, restored.getBlock(), 1);
                }
                if (expected.is(dev.senluo.witchcraft.time.TreeWood.LOGS)) restoreInscription(core, part);
                forget(origin,part); continue;
            }
            boolean ownMaterial=formed && (current.is(TemporalContent.TIME_LEAVES.get()) || current.is(dev.senluo.witchcraft.time.TreeWood.LOGS));
            boolean soil = replaceableRootSoil(core, part, current, expected);
            boolean chalk = canCoverChalk(core, part, current);
            if(!current.isAir()&&!ownMaterial&&!soil&&!chalk){defer(origin,part,GEOMETRY);continue;}
            if (expected.getBlock() instanceof YearsBuildingEdits.Part) {
                expected = net.minecraft.world.level.block.Block.updateFromNeighbourShapes(expected,level,part);
                if (expected.isAir() || !expected.canSurvive(level,part)) { defer(origin,part,GEOMETRY); continue; }
            }
            Colony group = TemporalWorld.get(level).colony(core);
            if(!canRestoreConnected(core,part)){defer(origin,part,GEOMETRY);continue;}
            boolean log=expected.is(dev.senluo.witchcraft.time.TreeWood.LOGS);
            boolean leaf=expected.is(TemporalContent.TIME_LEAVES.get());
            if (leaf) {
                int distance = chalk ? 1 : TreeLeavesBlock.supportDistance(level, part);
                if (!expected.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)
                        && distance >= net.minecraft.world.level.block.LeavesBlock.DECAY_DISTANCE) { defer(origin,part,GEOMETRY); continue; }
                expected = expected.setValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE, distance);
            }
            boolean first=!formed || log&&!core.tissue.formedLogs.contains(relative);
            long fee = (core.tissue.giantGrowing||core.initialGiantBuild)&&first ? 0 : leaf ? TreeBalance.LEAF_REPAIR : TreeBalance.LOG_REPAIR;
            if (group.spendable() < fee) { defer(origin,part,fee); continue; }
            if (!accessible.computeIfAbsent(origin,this::exterior).contains(part)) { defer(origin,part,GEOMETRY); continue; }
            if (chalk) {
                BlockPos support = part.below();
                BlockState supportState = level.getBlockState(support);
                if (!supportState.is(dev.senluo.witchcraft.time.TreeWood.LOGS)) {
                    long supportFee = core.tissue.giantGrowing || core.initialGiantBuild ? 0 : TreeBalance.LOG_REPAIR;
                    if (group.spendable() < supportFee) { defer(origin,part,supportFee); continue; }
                    if (!growPart(support,
                            TemporalContent.TIME_LOG.get().defaultBlockState())) { defer(origin,part,0); continue; }
                    if (!group.spend(supportFee)) throw new IllegalStateException("Tree inscription support lost its reserved budget");
                    rememberWood(core, support, level.getBlockState(support));
                    // A second block is a second unit of the existing 16-cell budget. Requote next pass.
                    repairs.addFirst(part);
                    continue;
                }
                rememberWood(core, support, supportState);
                boolean covered, before = writingShape; writingShape = true;
                try { covered = dev.senluo.witchcraft.ritual.RitualChalk.coverChalk(level, part, expected); }
                finally { writingShape = before; }
                if (!covered) { defer(origin,part,0); continue; }
            } else if (!growPart(part, expected)) { defer(origin,part,0); continue; }
            if (expected.getBlock() instanceof YearsBuildingEdits.Part) {
                BlockState remembered=plans.get(origin).get(part);
                if (core.tissue.shapeSpace.transform(core.tissue,part.subtract(origin),remembered,level.getBlockState(part)))
                    plans.get(origin).put(part,core.tissue.shapeSpace.cells().get(relative));
            }
            if (TreeSignMemory.supports(expected)) TreeSignMemory.restore(level,part,core.tissue.shapeSpace.sign(relative));
            if (!group.spend(fee)) throw new IllegalStateException("Tree restoration lost its reserved budget");
            core.tissue.formedTree.add(relative);core.setChanged();
            if(log) {
                core.tissue.formedLogs.add(relative);
                rememberWood(core, part, expected);
                restoreInscription(core, part);
            }
            Set<BlockPos> live=livePaths.get(origin);
            if (live != null && live.add(part)) liveFrontiers.computeIfAbsent(origin,p -> new ArrayDeque<>()).add(part);
            for(BlockPos d:TreeShapeSpace.NEIGHBORS){BlockPos n=part.offset(d);
                if(pending.getOrDefault(origin,Set.of()).contains(n))repairs.add(n);}
            forget(origin,part);
            sparkle(part);
        }
    }

    private boolean growPart(BlockPos pos,BlockState state){
        boolean before=writingShape;writingShape=true;
        try{return TemporalWorld.get(level).growWood(pos,state);}finally{writingShape=before;}
    }

    /** A native door is one placement with two cells; preflight both before either is written. */
    private int restoreDoor(LivingEntity core, BlockPos requested, BlockState expected, int budget,
                            Map<BlockPos,Set<BlockPos>> accessible) {
        var half=net.minecraft.world.level.block.DoorBlock.HALF;
        var lowerHalf=net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER;
        BlockPos origin=core.getBlockPos(), lower=expected.getValue(half)==lowerHalf?requested:requested.below(), upper=lower.above();
        var plan=plans.getOrDefault(origin,Map.of());
        BlockState bottom=plan.get(lower), top=plan.get(upper);
        if (bottom==null || top==null || !bottom.is(expected.getBlock()) || !top.is(expected.getBlock())
                || bottom.getValue(half)!=lowerHalf || top.getValue(half)==lowerHalf
                || !level.hasChunkAt(lower) || !level.hasChunkAt(upper) || !level.shouldTickBlocksAt(lower) || !level.shouldTickBlocksAt(upper)
                || !mayUse(core,lower) || !mayUse(core,upper) || !bottom.canSurvive(level,lower)) {
            defer(origin,requested,GEOMETRY); return 1;
        }
        BlockState oldBottom=level.getBlockState(lower), oldTop=level.getBlockState(upper);
        if ((!oldBottom.isAir() && !oldBottom.is(expected.getBlock())) || (!oldTop.isAir() && !oldTop.is(expected.getBlock()))
                || !canRestoreConnected(core,lower) && !canRestoreConnected(core,upper)
                || !accessible.computeIfAbsent(origin,this::exterior).contains(lower) || !accessible.get(origin).contains(upper)) {
            defer(origin,requested,GEOMETRY); return 1;
        }
        if (budget<2) { repairs.addFirst(requested); return 1; }
        long fee=0;
        for (BlockPos p : new BlockPos[]{lower,upper}) if (level.getBlockState(p).isAir()
                && !((core.tissue.giantGrowing||core.initialGiantBuild) && !core.tissue.formedTree.contains(p.subtract(origin).asLong()))) fee+=TreeBalance.LOG_REPAIR;
        Colony group=TemporalWorld.get(level).colony(core);
        if (group.spendable()<fee) { defer(origin,requested,fee); return 1; }
        boolean powered=level.hasNeighborSignal(lower)||level.hasNeighborSignal(upper);
        bottom=bottom.setValue(net.minecraft.world.level.block.DoorBlock.POWERED,powered);
        if (powered) bottom=bottom.setValue(net.minecraft.world.level.block.DoorBlock.OPEN,true);
        top=bottom.setValue(half,net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
        boolean before=writingShape; writingShape=true;
        try {
            // Known-shape writes suppress the transient one-half state. Native notifications follow the complete pair.
            level.setBlock(lower,bottom,18); level.setBlock(upper,top,18);
            if (!level.getBlockState(lower).is(expected.getBlock()) || !level.getBlockState(upper).is(expected.getBlock())) {
                if (level.getBlockState(lower).is(expected.getBlock())) level.setBlock(lower,oldBottom,18);
                if (level.getBlockState(upper).is(expected.getBlock())) level.setBlock(upper,oldTop,18);
                defer(origin,requested,GEOMETRY); return 2;
            }
            bottom.updateNeighbourShapes(level,lower,3); top.updateNeighbourShapes(level,upper,3);
            level.updateNeighborsAt(lower,bottom.getBlock()); level.updateNeighborsAt(upper,top.getBlock());
        } finally { writingShape=before; }
        if (!group.spend(fee)) throw new IllegalStateException("Tree door restoration lost its reserved budget");
        for (BlockPos p : new BlockPos[]{lower,upper}) {
            core.tissue.formedTree.add(p.subtract(origin).asLong()); forget(origin,p);
            Set<BlockPos> live=livePaths.get(origin);
            if(live!=null && live.add(p)) liveFrontiers.computeIfAbsent(origin,k->new ArrayDeque<>()).add(p);
            for(BlockPos d:TreeShapeSpace.NEIGHBORS){BlockPos n=p.offset(d);if(pending.getOrDefault(origin,Set.of()).contains(n))repairs.add(n);}
            sparkle(p);
        }
        core.setChanged(); return 2;
    }

    private boolean replaceableRootSoil(LivingEntity core, BlockPos pos, BlockState state, BlockState expected) {
        if (!core.tree() || !(core.tissue.giantGrowing || core.tissue.giant || core.tissue.naturalTicks >= TreeBalance.GIANT_TICKS)) return false;
        return expected.is(dev.senluo.witchcraft.time.TreeWood.LOGS) && pos.getY() <= core.getBlockPos().getY()
                && TreeGrowthMaterial.substrate(state);
    }

    private boolean canCoverChalk(LivingEntity core, BlockPos pos, BlockState state) {
        if (core == null || !state.is(dev.senluo.witchcraft.ritual.RitualContent.CHALK_SURFACE.get())
                || !(core.tissue.giant || core.tissue.giantGrowing || core.tissue.naturalTicks >= TreeBalance.GIANT_TICKS)) return false;
        BlockPos support = pos.below(), owner = ownerAt(support);
        if (!level.isInWorldBounds(support) || !level.hasChunkAt(support) || !level.shouldTickBlocksAt(support)
                || owner != null && !owner.equals(core.getBlockPos())
                || !TreeShapeSpace.contains(true, support.subtract(core.getBlockPos()))
                || core.tissue.shapeSpace.pruned(support.subtract(core.getBlockPos()).asLong())) return false;
        BlockState host = level.getBlockState(support);
        return (host.is(dev.senluo.witchcraft.time.TreeWood.LOGS) || TreeGrowthMaterial.substrate(host))
                && dev.senluo.witchcraft.ritual.RitualChalk.coveringCarving(level, pos) != null;
    }

    private void sparkle(BlockPos pos) {
        level.sendParticles(GOLD,pos.getX()+0.5,pos.getY()+0.5,pos.getZ()+0.5,12,0.45,0.45,0.45,0.02);
    }
}
