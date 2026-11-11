package dev.senluo.witchcraft.time;

import dev.senluo.witchcraft.compat.CropGrowth;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Server-thread only. Indexes loaded objects, never loads a chunk on behalf of a plant. */
public final class TemporalWorld {
    private static final int ROOT_ATTEMPT_TICKS = 30 * 20;
    private static final int ROOT_EXTEND_AGE = 30 * 20;
    // Owned by LevelEvent.Unload; values own their level, so weak keys would be misleading.
    private static final Map<ServerLevel, TemporalWorld> WORLDS = new IdentityHashMap<>();
    public static TemporalWorld get(ServerLevel level) { return WORLDS.computeIfAbsent(level, TemporalWorld::new); }
    public static void unload(ServerLevel level) { WORLDS.remove(level); ScheduledTime.unbind(level); FluidTime.unload(level); AccelerationSources.unload(level); }
    public final ServerLevel level;
    public final TreeLogic trees;
    public final ScheduledTime scheduled;
    public final DevelopmentClock development;
    public final CropTargets crops;
    public final MachineTargets machines;
    public final ProductionScheduler production;
    private final Map<Long, BlockEntity> network = new HashMap<>();
    private List<LivingEntity> orderedLiving = List.of();
    private List<RootEntity> orderedRoots = List.of();
    private boolean orderDirty = true;
    private final Map<Long, List<LivingEntity>> domains = new HashMap<>();
    private final Map<Long, Colony> colonies = new HashMap<>();
    private final HostRestores restores;
    private boolean dirty = true;
    private Set<Long> activeNetwork = Set.of();
    private long sequence;
    private int nodeCursor;
    private int rootCursor;
    private int rootStageCursor;
    private final Map<RootEntity,Long> missingParents = new WeakHashMap<>();
    private long acceleratedSearchTick = Long.MIN_VALUE;
    private int acceleratedSearches;
    private long lastWorldTick = Long.MIN_VALUE;
    private final Map<Long, Boolean> connectionChunks = new HashMap<>();
    private final Set<Long> parentConnectionChunks = new HashSet<>();
    private final Map<Long, Set<Colony>> connectionWatchers = new HashMap<>();
    private final Map<Colony, Set<Long>> colonyChunks = new IdentityHashMap<>();
    private final Set<BlockPos> convertingRoots = new HashSet<>();
    private long nativeChunkTick = Long.MIN_VALUE;
    private final Set<Long> nativeChunks = new HashSet<>();
    // A phase belongs to a live machine, not every position ever visited in this world.
    // Weak keys release unloaded/replaced machines without retaining their chunks.
    private final Map<BlockEntity,Integer> workPhases=new WeakHashMap<>();
    private final ConnectedTreeWood connectedWood = new ConnectedTreeWood();
    private final TreeBiomeGrowth biomeGrowth = new TreeBiomeGrowth();

    /** Physical wood/core connection; not a saved geometry claim or a root-soil colony connection. */
    public boolean connectedTreeWood(BlockPos pos) { return connectedWood.connected(level, pos); }
    public ConnectedTreeWood.Connection treeWoodConnection(BlockPos pos) { return connectedWood.connection(level, pos); }

    private TemporalWorld(ServerLevel level) {
        this.level = level; this.restores = HostRestores.get(level); this.trees = new TreeLogic(level); this.scheduled = ScheduledTime.bind(level);
        this.development = new DevelopmentClock(level); this.crops = new CropTargets(level);
        this.machines = new MachineTargets(level); this.production = new ProductionScheduler(this);
    }
    public void add(BlockEntity entity) {
        // ItemStack.useOn has not yet passed EntityPlaceEvent. onLoad commits accepted placements.
        // Publishing here would let a read-only event listener merge a provisional inventory/site.
        if (level.captureBlockSnapshots || level.restoringBlockSnapshots) return;
        BlockEntity previous = network.put(entity.getBlockPos().asLong(), entity);
        if(previous != entity)orderDirty = true;
        if (entity instanceof LivingEntity living && living.tree()) trees.register(living);
        else if (previous instanceof LivingEntity oldLiving && oldLiving.tree()) trees.unregister(entity.getBlockPos());
        if (entity instanceof LivingEntity || previous instanceof LivingEntity) invalidate();
        else invalidateConnections();
    }
    public void remove(BlockPos pos) {
        Colony existing = colonies.get(pos.asLong());
        if (existing != null && !level.restoringBlockSnapshots) existing.equalize();
        BlockEntity removed = network.remove(pos.asLong());
        if(removed != null)orderDirty = true;
        trees.unregister(pos);
        if (removed instanceof LivingEntity) invalidate();
        else invalidateConnections();
    }
    public void invalidate() { dirty = true; invalidateConnections(); }
    public void invalidateConnections() {
        missingParents.clear();
        connectedWood.invalidate();
        // A lifecycle topology change commits the last resolved decision before members split.
        for (Colony group : new HashSet<>(colonies.values())) {
            if (!level.restoringBlockSnapshots && !level.captureBlockSnapshots) group.synchronizeMode();
            group.releaseStorage();
        }
        colonies.clear();
        connectionChunks.clear();
        parentConnectionChunks.clear();
        connectionWatchers.clear(); colonyChunks.clear();
        crops.topologyChanged();
        machines.clear();
        production.invalidate();
    }
    private void forget(Colony group) {
        if (!level.restoringBlockSnapshots && !level.captureBlockSnapshots) group.synchronizeMode();
        for (LivingEntity member : group.members) colonies.remove(member.getBlockPos().asLong(), group);
        Set<Long> observed = colonyChunks.remove(group);
        if (observed != null) for (long chunk : observed) {
            Set<Colony> bucket = connectionWatchers.get(chunk);
            if (bucket != null && bucket.remove(group) && bucket.isEmpty()) {
                connectionWatchers.remove(chunk);
                if (!parentConnectionChunks.contains(chunk)) connectionChunks.remove(chunk);
            }
        }
        group.releaseStorage(); crops.forget(group); machines.forget(group); production.forget(group);
    }
    private void connectionsChanged(long chunk) {
        Set<Colony> affected = connectionWatchers.get(chunk);
        if (affected != null) for (Colony group : List.copyOf(affected)) forget(group);
        // Failed parent searches can have stopped before reaching an observed colony.
        missingParents.clear();
    }

    public void blockChanged(BlockPos pos, BlockState before, BlockState after) {
        if (before != after) production.cropChanged(pos, before, after);
        crops.blockChanged(pos, before, after);
        machines.blockChanged(pos, before, after);
        if (before.is(dev.senluo.witchcraft.time.TreeWood.LOGS) != after.is(dev.senluo.witchcraft.time.TreeWood.LOGS)) {
            long chunk = ChunkPos.asLong(pos);
            connectedWood.changed(chunk);
            connectionsChanged(chunk);
        }
    }

    public void chunkChanged(ChunkPos chunk) {
        connectedWood.changed(chunk.toLong());
        production.chunkChanged(chunk.toLong());
        connectionsChanged(chunk.toLong());
        crops.chunkChanged(chunk.toLong());
        machines.chunkChanged(chunk.toLong());
        trees.chunkChanged(chunk);
    }

    /** Simulation-distance changes do not necessarily load/unload a block entity. */
    private void refreshActivity() {
        Set<Long> current = new HashSet<>();
        Map<Long, Boolean> chunks = new HashMap<>();
        for (var entry : network.entrySet()) {
            BlockEntity entity = entry.getValue();
            if (entity.isRemoved() || entity instanceof LivingEntity living && !living.tissue.supported()) continue;
            BlockPos pos = entity.getBlockPos();
            boolean ticking = chunks.computeIfAbsent(ChunkPos.asLong(pos), key ->
                    level.hasChunkAt(pos) && level.shouldTickBlocksAt(pos));
            if (ticking) current.add(entry.getKey());
        }
        boolean changed = connectionChunks.entrySet().stream().anyMatch(entry -> {
            ChunkPos chunk = new ChunkPos(entry.getKey());
            return entry.getValue() != (level.hasChunk(chunk.x, chunk.z)
                    && level.shouldTickBlocksAt(chunk.getWorldPosition()));
        });
        if (changed || !current.equals(activeNetwork)) {
            activeNetwork = current;
            invalidateConnections();
        }
    }
    public void restoreHost(BlockPos pos, BlockState host) { restores.put(pos.immutable(), host); }
    /** Cheap broad phase; the existing index is rebuilt only on topology changes. */
    public boolean hasDomain(BlockPos pos) { index(); return domains.containsKey(ChunkPos.asLong(pos)); }
    public boolean beginChunk(long chunk) {
        if (ExtraTicks.active()) return false;
        if (nativeChunkTick != level.getGameTime()) { nativeChunkTick = level.getGameTime(); nativeChunks.clear(); }
        return nativeChunks.add(chunk);
    }
    public boolean absorbWork(BlockPos pos) {
        if (!level.hasChunkAt(pos) || !level.shouldTickBlocksAt(pos) || !level.tickRateManager().runsNormally()) return false;
        index();
        List<LivingEntity> candidates = domains.get(ChunkPos.asLong(pos));
        if (candidates == null) return false;
        BlockState state = level.getBlockState(pos);
        if (CropGrowth.timed(state)) return CropGrowth.canAttempt(level, pos, state)
                && absorbQuoted(pos, 1, SourceIncomeRates.CROP * (double) CropSupplyRates.TICK_WORK / CropSupplyRates.RANDOM_WORK);
        if (candidates.stream().noneMatch(e -> e.tree() && active(e) && covers(e, pos))) return false;
        BlockEntity entity = level.getBlockEntity(pos);
        double price = entity == null ? 0 : WorkSources.price(level, entity);
        if(price<=0)return false;
        LivingEntity receiver=receiver(pos,1, e -> !AccelerationSources.producedBy(e) && !AccelerationSources.protects(level, entity, e.owner()));
        if(receiver==null)return false;
        int phase=workPhases.merge(entity,1,(a,b)->(a+1)&3);
        if(receiver.tissue.giant ? phase==0 : (phase&1)==0)return false;
        return credit(receiver,1,price);
    }
    private boolean active(BlockEntity e) { return !e.isRemoved() && (!(e instanceof LivingEntity living) || living.tissue.supported()) && level.shouldTickBlocksAt(e.getBlockPos()) && level.hasChunkAt(e.getBlockPos()); }
    public LivingEntity node(BlockPos pos) { return network.get(pos.asLong()) instanceof LivingEntity e && active(e) ? e : null; }

    public Colony colony(LivingEntity origin) {
        if (!origin.tissue.supported()) return new Colony(List.of(), List.of());
        Colony cached = colonies.get(origin.getBlockPos().asLong());
        if (cached != null) return cached;
        Set<Long> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        List<LivingEntity> members = new ArrayList<>();
        List<BlockPos> roots = new ArrayList<>();
        List<BlockPos> logs = new ArrayList<>();
        Set<Long> observedChunks = new HashSet<>();
        queue.add(origin.getBlockPos());
        while (!queue.isEmpty() && visited.size() < 16384) {
            BlockPos pos = queue.removeFirst();
            if (!visited.add(pos.asLong())) continue;
            if (!activeConnection(pos, observedChunks)) continue;
            BlockEntity e = network.get(pos.asLong());
            if (e == null && !level.getBlockState(pos).is(dev.senluo.witchcraft.time.TreeWood.LOGS) || e != null && !active(e)) continue;
            if (e instanceof LivingEntity member && member != origin && !origin.compatible(member)) continue;
            if (e instanceof LivingEntity member) members.add(member);
            else if (e instanceof RootEntity) roots.add(pos);
            else logs.add(pos);
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) activeConnection(pos.offset(x, 0, z), observedChunks);
            for (BlockPos next : RootConnections.neighbors(level, pos)) if (activeConnection(next, observedChunks)) queue.add(next);
        }
        if (members.isEmpty()) members.add(origin);
        members.sort(Comparator.comparingLong(e -> e.getBlockPos().asLong()));
        Colony colony = new Colony(members, roots, logs);
        // Standard root segments are conduits, not a shared inventory or proof of ownership.
        // Cache only compatible living members; a neutral segment can never merge two owners' balances.
        for (LivingEntity member : members) {
            Colony previous = colonies.get(member.getBlockPos().asLong());
            if (previous != null) forget(previous);
        }
        colonyChunks.put(colony, Set.copyOf(observedChunks));
        for (long chunk : observedChunks) {
            connectionWatchers.computeIfAbsent(chunk, ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(colony);
            ChunkPos position = new ChunkPos(chunk);
            connectionChunks.put(chunk, level.hasChunk(position.x, position.z) && level.shouldTickBlocksAt(position.getWorldPosition()));
        }
        for (LivingEntity member : members) colonies.put(member.getBlockPos().asLong(), colony);
        return colony;
    }

    private boolean activeConnection(BlockPos pos, Set<Long> observed) {
        boolean active = level.hasChunkAt(pos) && level.shouldTickBlocksAt(pos);
        observed.add(ChunkPos.asLong(pos));
        return active;
    }
    private boolean activeConnection(BlockPos pos) {
        boolean active = level.hasChunkAt(pos) && level.shouldTickBlocksAt(pos);
        // Parent discovery is independent of an existing colony, including failed searches.
        long chunk = ChunkPos.asLong(pos);
        parentConnectionChunks.add(chunk);
        connectionChunks.put(chunk, active);
        return active;
    }

    private void index() {
        if (!dirty) return;
        domains.clear();
        for (BlockEntity value : network.values()) if (value instanceof LivingEntity e && !e.isRemoved() && e.tissue.supported() && !e.amber()) {
            BlockPos pos = e.getBlockPos();
            int radius = e.tissue.radius();
            int minX = (pos.getX() - radius) >> 4, maxX = (pos.getX() + radius) >> 4;
            int minZ = (pos.getZ() - radius) >> 4, maxZ = (pos.getZ() + radius) >> 4;
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
                domains.computeIfAbsent(ChunkPos.asLong(x, z), k -> new ArrayList<>()).add(e);
        }
        dirty = false;
    }
    public boolean covers(LivingEntity e, BlockPos source) {
        BlockPos p = e.getBlockPos();
        int radius = e.tissue.radius();
        return Math.abs((long)p.getX() - source.getX()) <= radius && Math.abs((long)p.getZ() - source.getZ()) <= radius
                && source.getY() >= level.getMinBuildHeight() && source.getY() < level.getMaxBuildHeight()
                && (e.tree() || Math.abs((long)p.getY() - source.getY()) <= radius);
    }
    public boolean absorbRandomSample(BlockPos pos) { return absorb(pos, 0, 0); }

    /** Kind 0 prices the receiving organism; kinds 1/2 use the crop/work source quote. */
    public boolean absorb(BlockPos pos, int kind, long price) {
        return absorbQuoted(pos, kind, price);
    }

    private boolean absorbQuoted(BlockPos pos, int kind, double price) {
        if (!level.hasChunkAt(pos) || !level.shouldTickBlocksAt(pos) || !level.tickRateManager().runsNormally()) return false;
        index();
        List<LivingEntity> candidates = domains.get(ChunkPos.asLong(pos));
        if (candidates == null || candidates.isEmpty()) return false;
        long serial = sequence++;
        int start = Math.floorMod(serial, candidates.size());
        LivingEntity best = null;
        double bestQuote = 0;
        BlockEntity target = level.getBlockEntity(pos);
        for (int i = 0; i < candidates.size(); i++) {
            LivingEntity e = candidates.get((start + i) % candidates.size());
            if (AccelerationSources.producedBy(e) || AccelerationSources.currentOwner(e.owner()) || AccelerationSources.protects(level, target, e.owner())
                    || !active(e) || !covers(e, pos) || kind != 0 && !e.tissue.sourceEnabled(kind == 2 ? 1 : 0)
                    || kind == 2 && !e.tree() || colony(e).room() <= 0) continue;
            double quote = kind == 0 ? RandomSampleRates.price(e.tissue) : price;
            if (quote > bestQuote) { best = e; bestQuote = quote; }
        }
        if (best != null && (kind != 1 || level.random.nextInt(4) == 0))
            return kind == 1 ? credit(best, 0, bestQuote) : colony(best).creditAvailable((long) bestQuote, best);
        return false;
    }

    /** New sources choose one strongest eligible receiver; sub-mTU fractions stay on its account. */
    public LivingEntity receiver(BlockPos pos,int source) {
        return receiver(pos, source, entity -> true);
    }

    /** Eligibility is applied before selecting a receiver, including overlapping owners' domains. */
    public LivingEntity receiver(BlockPos pos, int source, java.util.function.Predicate<LivingEntity> eligible) {
        if(!level.hasChunkAt(pos)||!level.shouldTickBlocksAt(pos)||!level.tickRateManager().runsNormally())return null;
        index();var candidates=domains.get(ChunkPos.asLong(pos));
        if(candidates==null||candidates.isEmpty())return null;
        int start=Math.floorMod(sequence++,candidates.size());LivingEntity best=null;
        for(int i=0;i<candidates.size();i++){
            LivingEntity e=candidates.get((start+i)%candidates.size());
            if(!active(e)||!covers(e,pos)||!e.tissue.sourceEnabled(source)||!e.tree()||source>=2&&!e.tissue.giant||!eligible.test(e)||colony(e).room()<=0)continue;
            if(best==null||e.tissue.giant&&!best.tissue.giant)best=e;
        }
        return best;
    }
    public boolean credit(LivingEntity receiver,int source,double milliTu) {
        if(receiver==null||!Double.isFinite(milliTu)||milliTu<=0||colony(receiver).room()<=0)return false;
        double sum=receiver.tissue.incomeRemainders[source]+milliTu;
        long whole=(long)Math.min(Tissue.MAX_VALUE,Math.floor(sum));
        if(whole>0&&!colony(receiver).creditAvailable(whole,receiver))return false;
        receiver.tissue.incomeRemainders[source]=sum-Math.floor(sum);receiver.setChanged();return true;
    }

    public void tick() {
        restores.flush(level);
        long tick = level.getGameTime();
        if (tick == lastWorldTick || !level.tickRateManager().runsNormally()) return;
        lastWorldTick = tick;
        if (tick % 20 == 0) refreshActivity();
        development.flush();
        refreshOrder();
        List<LivingEntity> producers = orderedLiving.isEmpty() ? List.of() : new ArrayList<>();
        for (LivingEntity entity : orderedLiving) if (active(entity)) {
            if (entity.canDistribute()) producers.add(entity);
            biomeGrowth.tick(level, entity, tick);
        }
        biomeGrowth.flush(level);
        production.flush(producers);
        scheduled.tick();
        FluidTime.get(level).tick();
        trees.flush();
        if (tick % 20 != 0) return;
        List<LivingEntity> nodes = livingNodes();
        Set<LivingEntity> developed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < Math.min(256, nodes.size()); i++) {
            LivingEntity e = nodes.get(Math.floorMod(nodeCursor + i, nodes.size()));
            if (e.lastGrowth == tick) continue;
            e.lastGrowth = tick;
            Colony group = colony(e);
            if (group.members.stream().noneMatch(developed::contains)) {
                developed.addAll(group.members);
                group.synchronizeMode();
                group.equalize();
            }
            trees.tick(e);
        }
        nodeCursor += Math.min(256, nodes.size());
        growRootSegments();
        for (int i = 0; i < Math.min(8, nodes.size()); i++) {
            LivingEntity e = nodes.get(Math.floorMod(rootCursor + i, nodes.size()));
            if (e.lastRootGrowth == Long.MIN_VALUE) e.lastRootGrowth = tick;
            else if (tick - e.lastRootGrowth >= ROOT_ATTEMPT_TICKS) tryRoot(e, nodes);
        }
        rootCursor += Math.min(8, nodes.size());
    }

    private void growRootSegments() {
        refreshOrder();
        List<RootEntity> roots = new ArrayList<>();
        for(RootEntity root : orderedRoots)if(active(root))roots.add(root);
        Map<RootEntity, LivingEntity> updates = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(256, roots.size()); i++) {
            RootEntity root = roots.get(Math.floorMod(rootStageCursor + i, roots.size()));
            LivingEntity parent = root.parent == null ? null : node(root.parent);
            if (parent == null || !colony(parent).rootSet.contains(root.getBlockPos())) {
                long now = level.getGameTime();
                if (now < missingParents.getOrDefault(root, Long.MIN_VALUE)) continue;
                parent = findParent(root);
                if (parent == null) missingParents.put(root, now + 100);
            }
            if (parent == null) continue;
            LivingEntity woody = colony(parent).members.stream().filter(e -> e.tree() && e.tissue.giant).findFirst().orElse(parent);
            updates.put(root, woody);
        }
        updates.forEach((root, parent) -> advanceRoot(root, parent, 20));
        rootStageCursor += Math.min(256, roots.size());
    }

    private void advanceRoot(RootEntity root, LivingEntity parent, int elapsed) {
        if (root.isRemoved()) return;
        if (parent.tree() && parent.tissue.giant && level.getBlockState(root.getBlockPos()).is(TemporalContent.ROOT.get())) {
            growWood(root.getBlockPos(), rootWood(root.getBlockPos(), parent));
            return;
        }
        root.parent = parent.getBlockPos();
        root.ticks = (int) Math.min(3600L, root.ticks + (long) elapsed);
        int age = Math.min(Math.min(3, parent.development()), root.ticks / 1200);
        // Placement may still be finishing an onPlace connection refresh. Preserve the
        // actual world's ports and moisture, not an older block-entity state snapshot.
        BlockState state = level.getBlockState(root.getBlockPos());
        if (age != state.getValue(RootBlock.AGE))
            level.setBlock(root.getBlockPos(), state.setValue(RootBlock.AGE, age), 3);
        root.setChanged();
    }

    public boolean convertingRoot(BlockPos pos) { return convertingRoots.contains(pos); }

    /** A material conversion, not a harvest: preserve chalk without emitting root/soil drops. */
    public boolean growWood(BlockPos pos, BlockState state) {
        if (!level.hasChunkAt(pos) || !level.shouldTickBlocksAt(pos)) return false;
        boolean guard = state.is(dev.senluo.witchcraft.time.TreeWood.LOGS) && level.getBlockState(pos).is(TemporalContent.ROOT.get())
                && convertingRoots.add(pos.immutable());
        try { return level.setBlock(pos, state, 3); }
        finally { if (guard) convertingRoots.remove(pos); }
    }

    private BlockState rootWood(BlockPos pos, LivingEntity parent) {
        BlockPos delta = pos.subtract(parent.getBlockPos());
        Direction.Axis axis = Math.abs(delta.getY()) >= Math.max(Math.abs(delta.getX()), Math.abs(delta.getZ()))
                ? Direction.Axis.Y : Math.abs(delta.getX()) >= Math.abs(delta.getZ()) ? Direction.Axis.X : Direction.Axis.Z;
        return TemporalContent.TIME_LOG.get().defaultBlockState().setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, axis);
    }

    public boolean canGrowRoots(LivingEntity source) {
        if (!active(source)) return false;
        if (canRootFoot(source)) return true;
        for (BlockPos pos : colony(source).roots) {
            if (network.get(pos.asLong()) instanceof RootEntity root && source.getBlockPos().equals(root.parent) && root.ticks < 3600)
                return true;
        }
        return rootTarget(source, livingNodes()) != null;
    }

    /** Extra development ticks age only this parent's connected roots and shorten its retry wait. */
    public void accelerateRoots(LivingEntity source) {
        accelerateRoots(source, 1);
    }

    public void accelerateRoots(LivingEntity source, int elapsed) {
        long total = source.acceleratedRootTicks + (long) Math.max(0, elapsed);
        source.acceleratedRootTicks = (int) (total % 20);
        int advance = (int) Math.min(Integer.MAX_VALUE - 20L, total / 20 * 20);
        // Preserve intermediate root maturation/extension boundaries, including the search quota.
        for (int remaining = advance; remaining > 0; remaining -= 20) accelerateRootStep(source);
    }

    private void accelerateRootStep(LivingEntity source) {
        int scanned = 0;
        for (BlockPos pos : colony(source).roots) {
            if (++scanned > 256) break;
            if (network.get(pos.asLong()) instanceof RootEntity root && active(root) && source.getBlockPos().equals(root.parent))
                advanceRoot(root, source, 20);
        }
        long tick = level.getGameTime();
        if (source.lastRootGrowth == Long.MIN_VALUE) source.lastRootGrowth = tick;
        source.lastRootGrowth = Math.max(tick - ROOT_ATTEMPT_TICKS, source.lastRootGrowth - 20);
        if (tick - source.lastRootGrowth < ROOT_ATTEMPT_TICKS) return;
        if (acceleratedSearchTick != tick) { acceleratedSearchTick = tick; acceleratedSearches = 0; }
        if (acceleratedSearches >= 8) return;
        acceleratedSearches++;
        tryRoot(source, livingNodes());
    }

    private List<LivingEntity> livingNodes() {
        refreshOrder();
        if(orderedLiving.isEmpty())return List.of();
        List<LivingEntity> result = new ArrayList<>();
        for(LivingEntity e : orderedLiving)if(active(e))result.add(e);
        return result;
    }
    private void refreshOrder() {
        if(!orderDirty)return;
        List<LivingEntity> living = new ArrayList<>();
        List<RootEntity> roots = new ArrayList<>();
        for(BlockEntity entity : network.values()){
            if(entity instanceof LivingEntity e)living.add(e);
            else if(entity instanceof RootEntity e)roots.add(e);
        }
        living.sort(Comparator.comparingLong(e -> e.getBlockPos().asLong()));
        roots.sort(Comparator.comparingLong(e -> e.getBlockPos().asLong()));
        orderedLiving=List.copyOf(living);orderedRoots=List.copyOf(roots);orderDirty=false;
        level.getProfiler().incrementCounter("kw_time_order_rebuilds");
    }
    private LivingEntity findParent(RootEntity root) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.add(root.getBlockPos());
        while (!queue.isEmpty() && visited.size() < 4096) {
            BlockPos p = queue.removeFirst();
            if (!visited.add(p.asLong())) continue;
            if (!activeConnection(p)) continue;
            BlockEntity entity = network.get(p.asLong());
            if (entity == null && !level.getBlockState(p).is(dev.senluo.witchcraft.time.TreeWood.LOGS) || entity != null && !active(entity)) continue;
            if (entity instanceof LivingEntity parent) return parent;
            queue.addAll(RootConnections.neighbors(level, p));
        }
        return null;
    }

    /** AABB intersection uses the same extent as absorption; amber has a local storage influence. */
    public boolean overlap(LivingEntity a, LivingEntity b) {
        int[] aa = bounds(a), bb = bounds(b);
        for (int axis = 0; axis < 3; axis++) if (aa[axis] > bb[axis + 3] || bb[axis] > aa[axis + 3]) return false;
        return true;
    }
    private int[] bounds(LivingEntity e) {
        BlockPos p = e.getBlockPos();
        int r = e.amber() ? 4 + e.tissue.amberStage : e.tissue.radius();
        return new int[]{p.getX()-r, e.tree() ? level.getMinBuildHeight() : p.getY()-r, p.getZ()-r,
                p.getX()+r, e.tree() ? level.getMaxBuildHeight()-1 : p.getY()+r, p.getZ()+r};
    }

    private LivingEntity rootTarget(LivingEntity source, List<LivingEntity> nodes) {
        int maxLength = 8 + 6*Math.min(4, source.development());
        BlockPos start = naturalRootAnchor(source);
        if (!naturalRootCell(start, source)) return null;
        return nodes.stream().filter(n -> n != source && source.compatible(n)
                && start.distManhattan(naturalRootAnchor(n)) + source.getBlockPos().distManhattan(start)
                    + n.getBlockPos().distManhattan(naturalRootAnchor(n)) <= maxLength
                && naturalRootCell(naturalRootAnchor(n), source) && overlap(source, n)
                && (!colony(source).members.contains(n)
                    || level.getBlockState(start).is(Blocks.FARMLAND)
                    || level.getBlockState(naturalRootAnchor(n)).is(Blocks.FARMLAND)))
                .min(Comparator.comparingDouble(n -> source.getBlockPos().distSqr(n.getBlockPos()))).orElse(null);
    }

    private BlockPos naturalRootAnchor(LivingEntity plant) {
        return plant.getBlockPos().below();
    }

    /** A first connection belongs to the plant itself; only outward routes require another organism. */
    private boolean canRootFoot(LivingEntity source) {
        BlockPos foot = naturalRootAnchor(source);
        return naturalRootCell(foot, source) && !RootConnections.isRoot(level.getBlockState(foot))
                && !level.getBlockState(foot).is(dev.senluo.witchcraft.time.TreeWood.LOGS);
    }

    private boolean naturalRootCell(BlockPos pos, LivingEntity source) {
        if (!level.hasChunkAt(pos) || !level.shouldTickBlocksAt(pos)) return false;
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity instanceof RootEntity) return true;
        BlockState state = level.getBlockState(pos);
        if (entity != null) return false;
        if (state.is(dev.senluo.witchcraft.time.TreeWood.LOGS)) return true;
        if (TissueItem.rootSoil(state)) return true;
        // Ordinary farmland is a growth cell only beneath a compatible rhizome.
        // Fields under other crops become root paths exclusively through manual placement.
        LivingEntity above = state.is(Blocks.FARMLAND) ? node(pos.above()) : null;
        return above != null && !above.amber() && source.compatible(above) && overlap(source, above);
    }

    private void tryRoot(LivingEntity source, List<LivingEntity> nodes) {
        source.lastRootGrowth = level.getGameTime();
        BlockPos start = naturalRootAnchor(source);
        if (canRootFoot(source)) {
            growRootCell(source, start, 1);
            return;
        }
        int maxLength = 8 + 6*Math.min(4, source.development());
        LivingEntity target = rootTarget(source, nodes);
        if (target == null) return;
        // Both ends begin below the plants. A complete soil route must exist first;
        // farmland below ordinary crops cannot be converted to complete that route.
        BlockPos end = naturalRootAnchor(target);
        int startDepth = source.getBlockPos().distManhattan(start);
        int endDepth = target.getBlockPos().distManhattan(end);
        record Step(BlockPos pos, int cost) {}
        PriorityQueue<Step> queue = new PriorityQueue<>(Comparator.comparingInt(Step::cost));
        Map<BlockPos, BlockPos> previous = new HashMap<>();
        Map<BlockPos, Integer> depth = new HashMap<>();
        Map<BlockPos, Integer> costs = new HashMap<>();
        queue.add(new Step(start, 0)); previous.put(start, start); depth.put(start, startDepth); costs.put(start, 0);
        while (!queue.isEmpty() && previous.size() < 2048) {
            Step step = queue.remove();
            BlockPos p = step.pos();
            if (step.cost() != costs.get(p)) continue;
            if (p.equals(end)) break;
            if (depth.get(p) + endDepth >= maxLength) continue;
            List<Direction> directions = new ArrayList<>(List.of(Direction.values()));
            directions.sort(Comparator.comparingInt(d -> p.relative(d).distManhattan(end)));
            for (Direction d : directions) {
                BlockPos next = p.relative(d);
                if (depth.get(p) + 1 + endDepth > maxLength || !naturalRootCell(next, source)) continue;
                int cost = costs.get(p) + 1;
                if (cost >= costs.getOrDefault(next, Integer.MAX_VALUE)) continue;
                previous.put(next, p); depth.put(next, depth.get(p) + 1); costs.put(next, cost); queue.add(new Step(next, cost));
            }
        }
        if (!previous.containsKey(end)) return;
        List<BlockPos> path = new ArrayList<>();
        for (BlockPos p = end; !p.equals(start); p = previous.get(p)) path.add(p);
        path.add(start);
        Collections.reverse(path);
        BlockPos before = source.getBlockPos();
        for (BlockPos p : path) {
            if (network.get(p.asLong()) instanceof RootEntity || level.getBlockState(p).is(dev.senluo.witchcraft.time.TreeWood.LOGS)) { before = p; continue; }
            if (network.get(before.asLong()) instanceof RootEntity root && root.ticks < ROOT_EXTEND_AGE) return;
            growRootCell(source, p, depth.get(p));
            return;
        }
    }

    private void growRootCell(LivingEntity source, BlockPos pos, int distance) {
        if (!naturalRootCell(pos, source)) return;
        BlockState host = level.getBlockState(pos);
        if (RootConnections.isRoot(host) || host.is(dev.senluo.witchcraft.time.TreeWood.LOGS)) return;
        BlockState grown = host.is(Blocks.FARMLAND) ? RootedFarmlandBlock.fromFarmland(host)
                : source.tree() && source.tissue.giant ? rootWood(pos, source)
                : TemporalContent.ROOT.get().defaultBlockState().setValue(RootBlock.EMBEDDED, true);
        boolean placed = grown.is(dev.senluo.witchcraft.time.TreeWood.LOGS) ? growWood(pos, grown) : level.setBlock(pos, grown, 3);
        if (placed && level.getBlockEntity(pos) instanceof RootEntity root) {
            root.parent = source.getBlockPos();
            root.owner = source.owner();
            root.distance = distance;
            root.setChanged(); add(root);
        }
    }
}
