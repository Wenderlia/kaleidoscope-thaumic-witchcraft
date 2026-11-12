package dev.senluo.witchcraft.ritual;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Server-wide lifecycle/receipt ledger, driven by an external host. No scans, forced loads,
 * wall-clock catch-up, or automatic machine updates. A receipt owns its member associations and point demand. Restoring it never replays a paid action.
 */
public final class StructuralRitualStore extends SavedData {
    private static final int MAX_SESSIONS = 65_536, MAX_TERMS_BYTES = 16_384;
    private static final String NAME = "witchcraft_structural_rituals";
    private static final Factory<StructuralRitualStore> FACTORY = new Factory<>(StructuralRitualStore::new, StructuralRitualStore::load);
    public enum Phase { PREPARING, ACTIVE, ENDED }
    public enum Presence { PRESENT, UNLOADED, MISSING }
    public record Definition(ResourceLocation kind, int version, int duration, int[] steps, int pointCost,
                             Optional<RitualResearch.Requirement> research) {
        public Definition(ResourceLocation kind, int version, int duration, int[] steps, Optional<RitualResearch.Requirement> research) {
            this(kind, version, duration, steps, RitualRings.SMALL, research);
        }
        public Definition {
            java.util.Objects.requireNonNull(kind); java.util.Objects.requireNonNull(research);
            if (kind.toString().length() > 256 || version < 1 || duration < 1 || steps == null || steps.length < 1 || steps.length > 64
                    || pointCost < RitualRings.SMALL || pointCost > RitualRings.LARGE)
                throw new IllegalArgumentException("Invalid ritual definition");
            steps = steps.clone();
            int previous = 0;
            for (int tick : steps) {
                if (tick <= previous || tick > duration) throw new IllegalArgumentException("Invalid ritual milestones");
                previous = tick;
            }
            if (previous != duration) throw new IllegalArgumentException("The final milestone must commit activation");
        }
        @Override public int[] steps() { return steps.clone(); }
    }
    public static final Definition STEAM = new Definition(ResourceLocation.fromNamespaceAndPath(
            "kaleidoscope_witchcraft", "steam"), 1, 80, new int[]{20, 40, 60, 80}, RitualResearch.thaumcraft("RITUAL_TRANSPIRATION"));
    public static final Definition SUCTION = new Definition(ResourceLocation.fromNamespaceAndPath(
            "kaleidoscope_witchcraft", "suction_channel"), 1, 100, new int[]{20, 40, 60, 80, 100}, RitualRings.MEDIUM, RitualResearch.thaumcraft("RITUAL_SUCTION_CHANNEL"));
    public static final Definition PREPARATION = new Definition(ResourceLocation.fromNamespaceAndPath(
            "kaleidoscope_witchcraft", "preparation"), 1, 1, new int[]{1}, RitualResearch.thaumcraft("STRUCTURAL_RITUALS"));

    /** Stable deduplication token for adapters which pay/commit outside this ledger. */
    public record StepToken(UUID session, int index) {}
    public record PreparedStep(CompoundTag termsAfter, BooleanSupplier commit) {
        public PreparedStep {
            validateTerms(termsAfter); termsAfter = termsAfter.copy(); java.util.Objects.requireNonNull(commit);
        }
        @Override public CompoundTag termsAfter() { return termsAfter.copy(); }
    }
    @FunctionalInterface public interface StepAction {
        /** Quote first. A commit returning false must not have partially spent resources. Exceptions are not swallowed. */
        Optional<PreparedStep> prepare(StepToken token, CompoundTag currentTerms);
    }
    public record Progress(Phase phase, int elapsed, int committedSteps, boolean available, CompoundTag terms) {
        public Progress { terms = terms.copy(); }
        @Override public CompoundTag terms() { return terms.copy(); }
    }
    public record TickResult(Phase phase, boolean mayRun, int newlyCommittedStep) {}
    public record Handle(UUID id, RitualCenters.Reference center, Definition definition) {}
    public record Demand(UUID id, int cost, long order, boolean funded, BlockPos origin, BlockPos recipient) {}
    private record Address(ResourceLocation dimension, BlockPos position) {}
    private record MemberChunk(ResourceLocation dimension, long chunk) {}
    private static final class Session {
        final UUID id, initiator;
        RitualCenters.Reference center;
        Definition definition;
        List<RitualCenters.Member> members;
        boolean funded;
        CompoundTag terms, runtime = new CompoundTag();
        UUID reservation;
        Phase phase = Phase.PREPARING;
        int elapsed, step;
        // Runtime verification is intentionally not persisted. Every reload starts unavailable.
        boolean available, advancing, researchAllowed, permissionPending;
        long lastTick = Long.MIN_VALUE, lastResearchCheck = Long.MIN_VALUE;
        long startOrder;
        long memberRevision, permissionRevision;
        boolean observing;
        Map<BlockPos, Boolean> memberActivity = Map.of();
        RitualCenters.State observation;
        Session(UUID id, UUID initiator, RitualCenters.Reference center, Definition definition, CompoundTag terms) {
            this.id = id; this.initiator = initiator; this.center = center; this.definition = definition; this.terms = terms.copy();
            members = List.of(new RitualCenters.Member(center, center.position(), RitualCenters.Role.OWNER));
        }
        Address plane() { return terms.contains("Plane", Tag.TAG_LONG)
                ? new Address(center.dimension(), BlockPos.of(terms.getLong("Plane"))) : null; }
        boolean sameStructure(Definition other) {
            return definition.kind.equals(other.kind) && definition.version == other.version
                    && definition.duration == other.duration && Arrays.equals(definition.steps, other.steps)
                    && definition.pointCost == other.pointCost;
        }
    }
    // Insertion order is the durable start priority, including across unload/restart.
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();
    private final Map<ResourceLocation, LinkedHashSet<UUID>> dimensions = new HashMap<>();
    private final Map<Address, LinkedHashSet<UUID>> addresses = new HashMap<>();
    private final Map<MemberChunk, LinkedHashSet<UUID>> memberChunks = new HashMap<>();
    // Derived from authoritative receipts; not serialized and never used as a validity check.
    private final Map<Address, LinkedHashSet<UUID>> planes = new HashMap<>();
    private final Map<Address, LinkedHashSet<UUID>> columns = new HashMap<>();
    private final Map<Address, Integer> poolMasks = new HashMap<>();
    private long nextStartOrder;
    private final Map<UUID, UUID> reservations = new HashMap<>();
    private CompoundTag preserved;

    public static StructuralRitualStore get(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Structural rituals belong to the server thread");
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }
    public boolean supported() { return preserved == null; }
    /** Immutable attribution of the source ritual; never inferred from its center's placement. */
    public Optional<UUID> initiator(UUID id) {
        Session session = sessions.get(id);
        return supported() && session != null ? Optional.of(session.initiator) : Optional.empty();
    }
    public java.util.List<Handle> inDimension(ResourceLocation dimension) {
        var ids = dimensions.get(dimension);
        if (!supported() || ids == null) return java.util.List.of();
        return ids.stream().map(sessions::get)
                .map(s -> new Handle(s.id, s.center, s.definition)).toList();
    }
    /** Same spatial origin in durable start order; supply may come from additional reachable pools. */
    public List<Handle> atPlane(ResourceLocation dimension, BlockPos anchor) {
        if (!supported()) return List.of();
        var candidates = new java.util.ArrayList<Session>();
        var bound = planes.get(new Address(dimension, anchor));
        if (bound != null) for (UUID id : bound) {
            Session s = sessions.get(id);
            if (s.phase != Phase.ENDED) candidates.add(s);
        }
        candidates.sort(java.util.Comparator.comparingLong(s -> s.startOrder));
        return candidates.stream().map(s -> new Handle(s.id, s.center, s.definition)).toList();
    }
    public Optional<UUID> at(RitualCenters.Reference center) {
        return allAt(center).stream().map(Handle::id).findFirst();
    }
    public List<Handle> allAt(RitualCenters.Reference center) {
        var ids = addresses.get(new Address(center.dimension(), center.position()));
        if (!supported() || ids == null) return List.of();
        return ids.stream().map(sessions::get).filter(s -> s.members.stream().anyMatch(m -> m.reference().equals(center)))
                .map(s -> new Handle(s.id, s.center, s.definition)).toList();
    }
    public List<Handle> allAt(ResourceLocation dimension, BlockPos position) {
        var ids = addresses.get(new Address(dimension, position));
        if (!supported() || ids == null) return List.of();
        return ids.stream().map(sessions::get).map(s -> new Handle(s.id, s.center, s.definition)).toList();
    }
    /** An untyped preparation chooses its behavior before any milestone is advanced or paid. */
    public boolean select(ServerLevel level, UUID id, Definition definition, CompoundTag terms, UUID reservation) {
        checkThread(level); validateTerms(terms);
        Session s = sessions.get(id);
        if (!supported() || s == null || s.advancing || s.phase != Phase.PREPARING || s.step != 0 || s.elapsed != 0
                || s.terms.getBoolean("Armed")
                || !reservationFree(id, reservation)
                || RitualResearch.check(level, s.initiator, definition.research) != RitualResearch.Result.ALLOWED) return false;
        if (!RitualPoints.quote(level, id, definition.pointCost)) return false;
        if (!terms.contains("SpatialMask") && s.terms.contains("SpatialMask")) terms.putInt("SpatialMask", s.terms.getInt("SpatialMask"));
        s.definition = definition; replaceTerms(s, terms.copy());
        invalidateMembers(id);
        RitualPoints.changed(level, source(s));
        s.lastResearchCheck = Long.MIN_VALUE; s.researchAllowed = false;
        reserve(s, reservation); setDirty(); return true;
    }
    public Optional<UUID> reservationOwner(UUID resource) { return supported() ? Optional.ofNullable(reservations.get(resource)) : Optional.empty(); }
    private boolean reservationFree(UUID session, UUID resource) { return resource == null || !reservations.containsKey(resource) || session.equals(reservations.get(resource)); }
    private void reserve(Session s, UUID resource) {
        if (s.reservation != null) reservations.remove(s.reservation, s.id);
        s.reservation = resource;
        if (resource != null) reservations.put(resource, s.id);
    }
    public CompoundTag runtime(UUID id) {
        Session s = sessions.get(id);
        return supported() && s != null ? s.runtime.copy() : new CompoundTag();
    }
    public void runtime(ServerLevel level, UUID id, CompoundTag data) {
        checkThread(level); validateTerms(data);
        Session s = sessions.get(id);
        if (supported() && s != null && !s.advancing && !s.runtime.equals(data)) { s.runtime = data.copy(); setDirty(); }
    }
    /** Edit uncommitted preparation terms; payment still belongs to the atomic step callback. */
    public boolean prepareTerms(ServerLevel level, UUID id, CompoundTag terms) {
        Session s = sessions.get(id);
        return prepareTerms(level, id, terms, s == null ? null : s.reservation);
    }
    public boolean prepareTerms(ServerLevel level, UUID id, CompoundTag terms, UUID reservation) {
        checkThread(level); validateTerms(terms);
        Session s = sessions.get(id);
        if (!supported() || s == null || s.advancing || s.phase != Phase.PREPARING || !reservationFree(id, reservation)) return false;
        replaceTerms(s, terms.copy()); reserve(s, reservation); setDirty(); return true;
    }
    public void end(ServerLevel level, UUID id) {
        checkThread(level);
        Session s = sessions.get(id);
        if (supported() && s != null && !s.advancing) { s.phase = Phase.ENDED; s.available = false; s.funded = false; pending(s, false); s.permissionRevision++; reserve(s, null); setDirty(); RitualPoints.changed(level, source(s)); }
    }
    public Optional<UUID> begin(ServerLevel level, Definition definition, RitualCenters.Center observed,
                                UUID initiator, CompoundTag terms) {
        checkThread(level);
        java.util.Objects.requireNonNull(initiator); validateTerms(terms);
        if (!supported() || sessions.size() >= MAX_SESSIONS || !observed.mayPrepare()
                || !observed.reference().dimension().equals(level.dimension().location())
                || RitualResearch.check(level, initiator, definition.research) != RitualResearch.Result.ALLOWED) return Optional.empty();
        var plane = terms.contains("Plane", Tag.TAG_LONG) ? RitualPlane.at(level, BlockPos.of(terms.getLong("Plane"))).orElse(null)
                : RitualPlane.find(level, observed.axis()).orElse(null);
        if (plane == null) return Optional.empty();
        CompoundTag supplied = terms.copy(); supplied.putLong("Plane", plane.anchor().asLong());
        supplied.putInt("SpatialMask", plane.rings().completeMask());
        List<RitualCenters.Member> members = RitualCenters.compose(level, new RitualSite(observed, plane), definition.kind());
        if (members.isEmpty()) return Optional.empty();
        var player = level.getServer().getPlayerList().getPlayer(initiator);
        if (player != null && members.stream().anyMatch(m -> !level.mayInteract(player, m.reference().position()))) return Optional.empty();
        Session session = new Session(UUID.randomUUID(), initiator, observed.reference(), definition, supplied);
        session.members = members;
        // The unpublished candidate participates in the same quote as existing receipts.
        insert(session);
        RitualPoints.changed(level, plane.anchor());
        if (!RitualPoints.quote(level, session.id, definition.pointCost)) {
            session.phase = Phase.ENDED; finish(level, session.id);
            return Optional.empty();
        }
        session.funded = true; RitualPoints.changed(level, plane.anchor()); setDirty();
        return Optional.of(session.id);
    }
    public Optional<Progress> progress(UUID id) {
        Session s = sessions.get(id);
        return s == null || !supported() ? Optional.empty()
                : Optional.of(new Progress(s.phase, s.elapsed, s.step, s.available, s.terms));
    }
    public Phase phase(UUID id) { var s = sessions.get(id); return s == null ? Phase.ENDED : s.phase; }
    /** Null means the one-way legacy import has already finished. */
    Boolean memberImport(UUID id) {
        var s = sessions.get(id);
        return s != null && s.terms.getBoolean("ImportMembers") ? s.terms.getBoolean("ImportStores") : null;
    }
    RitualCenters.State observeMembers(ServerLevel level, UUID id, java.util.function.Supplier<RitualCenters.State> observe) {
        var s = sessions.get(id);
        if (s == null) return new RitualCenters.State(false, false, true);
        refreshMemberActivity(level, s);
        if (s.observation != null) return s.observation;
        if (s.observing) return new RitualCenters.State(false, false, false, true);
        s.observing = true;
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                long revision = s.memberRevision;
                var activity = new LinkedHashMap<BlockPos, Boolean>();
                for (var member : s.members) {
                    BlockPos pos = new ChunkPos(member.reference().position()).getWorldPosition();
                    activity.put(pos, level.hasChunkAt(pos) && level.shouldTickBlocksAt(pos));
                }
                if (RitualCenters.seeksTarget(s.definition.kind, s.members)) {
                    BlockPos pos = new ChunkPos(source(s)).getWorldPosition();
                    activity.put(pos, level.hasChunkAt(pos) && level.shouldTickBlocksAt(pos));
                }
                var result = observe.get();
                if (revision != s.memberRevision) continue;
                s.memberActivity = Map.copyOf(activity);
                if (!activityMatches(level, s)) { invalidateMembers(id); RitualPoints.changed(level, source(s)); continue; }
                s.observation = result;
                return result;
            }
            return new RitualCenters.State(false, false, false, true);
        } finally { s.observing = false; }
    }
    private static boolean activityMatches(ServerLevel level, Session s) {
        for (var entry : s.memberActivity.entrySet())
            if (entry.getValue() != (level.hasChunkAt(entry.getKey()) && level.shouldTickBlocksAt(entry.getKey()))) return false;
        return true;
    }
    private void refreshMemberActivity(ServerLevel level, Session s) {
        if (s.observation != null && !activityMatches(level, s)) {
            invalidateMembers(s.id); RitualPoints.changed(level, source(s));
        }
    }
    void invalidateMembers(UUID id) {
        var s = sessions.get(id);
        if (s != null) { s.observation = null; s.memberRevision++; s.permissionRevision++; }
    }
    void invalidatePermission(UUID id) { var s = sessions.get(id); if (s != null) s.permissionRevision++; }
    public long permissionVersion(ServerLevel level, UUID id) {
        var s = sessions.get(id); if (s == null) return Long.MIN_VALUE;
        refreshMemberActivity(level, s);
        RitualPoints.refreshActivity(level, source(s));
        return s.permissionRevision;
    }
    void membersChanged(ServerLevel level, BlockPos pos) {
        var ids = addresses.get(new Address(level.dimension().location(), pos));
        if (ids != null) for (UUID id : List.copyOf(ids)) {
            invalidateMembers(id); RitualPoints.changed(level, source(id));
        }
        // A legacy receipt may still be seeking its first target; retain that negative lookup's recovery path.
        var candidates = columns.get(new Address(level.dimension().location(), new BlockPos(pos.getX(), 0, pos.getZ())));
        if (candidates != null) for (UUID id : List.copyOf(candidates)) {
            var s = sessions.get(id);
            if (Math.abs((long) source(s).getY() - pos.getY()) <= RitualPlane.HEIGHT_RADIUS
                    && RitualCenters.seeksTarget(s.definition.kind, s.members)
                    && RitualCenters.targetCandidate(level, s.definition.kind, pos)) {
                invalidateMembers(id); RitualPoints.changed(level, source(s));
            }
        }
    }
    void membersChanged(ServerLevel level, long chunk) {
        var ids = memberChunks.get(new MemberChunk(level.dimension().location(), chunk));
        if (ids != null) for (UUID id : List.copyOf(ids)) {
            invalidateMembers(id); RitualPoints.changed(level, source(id));
        }
    }

    /** One call for one actual game tick. The host supplies fresh presence, shape and resource facts. */
    public TickResult advance(ServerLevel level, UUID id, Definition definition, Presence presence,
                              RitualShape.Match structure, StepAction action) {
        return advance(level, id, definition, presence, structure, true, action);
    }
    /** Resource availability pauses time; it does not masquerade as destruction or an invalid ring. */
    public TickResult advance(ServerLevel level, UUID id, Definition definition, Presence presence,
                              RitualShape.Match structure, boolean resourcesAvailable, StepAction action) {
        checkThread(level);
        Session s = sessions.get(id);
        if (!supported() || s == null) return new TickResult(Phase.ENDED, false, -1);
        if (s.advancing) throw new IllegalStateException("Reentrant ritual step");
        if (!s.center.dimension().equals(level.dimension().location()) || !s.sameStructure(definition)) {
            revoke(level, id);
            return result(s, -1);
        }
        if (s.phase == Phase.ENDED) return result(s, -1);
        // Paid active receipts keep running; unfinished preparations must adopt today's entry requirement.
        if (s.phase == Phase.PREPARING && !s.definition.research.equals(definition.research)) {
            s.definition = definition;
            s.lastResearchCheck = Long.MIN_VALUE; s.researchAllowed = false;
            setDirty();
        }
        var permission = permission(level, id);
        if (permission == RitualPoints.Permission.PENDING) return new TickResult(s.phase, false, -1);
        boolean permitted = resourcesAvailable && presence == Presence.PRESENT && structure == RitualShape.Match.VALID
                && permission == RitualPoints.Permission.ALLOWED;
        if (!permitted) { revoke(level, id); return result(s, -1); }
        if (!s.available) { s.available = true; s.permissionRevision++; }
        long now = level.getGameTime();
        if (s.lastTick == now) return new TickResult(s.phase, false, -1);
        s.lastTick = now;
        if (s.phase == Phase.ACTIVE) return result(s, -1);
        if (!researchAllowed(level, s, false)) { revoke(level, id); return result(s, -1); }
        if (s.elapsed < s.definition.steps[s.step]) { s.elapsed++; setDirty(); }
        if (s.elapsed < s.definition.steps[s.step]) return result(s, -1);
        boolean finalStep = s.step == s.definition.steps.length - 1;
        s.advancing = true;
        try {
            Optional<PreparedStep> prepared = java.util.Objects.requireNonNull(action.prepare(new StepToken(s.id, s.step), s.terms.copy()));
            if (prepared.isEmpty()) return result(s, -1);
            PreparedStep transaction = prepared.get();
            CompoundTag after = transaction.termsAfter(); // Validate/copy before any external commit.
            // Quoting is read-only. Recheck only a ready payment, so missing offerings never cause per-tick disk reads.
            if (!eligible(level, id)) return new TickResult(s.phase, false, -1);
            if (!researchAllowed(level, s, true)) { revoke(level, id); return result(s, -1); }
            if (!transaction.commit.getAsBoolean()) return result(s, -1);
            replaceTerms(s, after);
            int committed = s.step++;
            if (finalStep) { s.phase = Phase.ACTIVE; s.permissionRevision++; reserve(s, null); }
            setDirty();
            if (!eligible(level, id)) return new TickResult(s.phase, false, committed);
            return result(s, committed);
        } finally { s.advancing = false; }
    }
    private static boolean researchAllowed(ServerLevel level, Session s, boolean committing) {
        if (s.definition.research.isEmpty()) return true;
        long now = level.getGameTime();
        boolean recent = s.lastResearchCheck != Long.MIN_VALUE && now >= s.lastResearchCheck
                && now - s.lastResearchCheck < 20;
        // A quote may invoke other listeners. Payment rechecks even in the same tick; adapters share stable snapshots.
        if (recent && !committing) return s.researchAllowed;
        s.lastResearchCheck = now;
        s.researchAllowed = RitualResearch.check(level, s.initiator, s.definition.research) == RitualResearch.Result.ALLOWED;
        return s.researchAllowed;
    }
    /** Keep termination receipts until the host has released its reserved resources. */
    public boolean finish(ServerLevel level, UUID id) {
        Session s = sessions.get(id);
        if (!supported() || s == null || s.phase != Phase.ENDED || s.advancing) return false;
        reserve(s, null); unindexPlane(s); sessions.remove(id);
        removeIndex(dimensions, s.center.dimension(), id);
        unindexMembers(s);
        removeIndex(columns, column(s), id);
        RitualPoints.changed(level, source(s));
        if (!columns.containsKey(column(s))) {
            Address column = column(s);
            poolMasks.keySet().removeIf(p -> p.dimension.equals(column.dimension)
                    && p.position.getX() == column.position.getX() && p.position.getZ() == column.position.getZ());
            RitualPoints.forget(level, source(s));
        }
        setDirty();
        return true;
    }
    private static TickResult result(Session s, int step) { return new TickResult(s.phase, s.available && s.phase == Phase.ACTIVE, step); }
    private void insert(Session s) {
        s.startOrder = nextStartOrder++;
        sessions.put(s.id, s);
        dimensions.computeIfAbsent(s.center.dimension(), ignored -> new LinkedHashSet<>()).add(s.id);
        indexMembers(s);
        columns.computeIfAbsent(column(s), ignored -> new LinkedHashSet<>()).add(s.id);
        indexPlane(s);
        // Resource reservations have one owner; a duplicate claimant must request another resource.
        if (s.phase == Phase.PREPARING && s.reservation != null) reservations.putIfAbsent(s.reservation, s.id);
    }
    /** Receives an already-owned copy; unchanged geometry costs only a scalar comparison. */
    private void replaceTerms(Session s, CompoundTag terms) {
        boolean samePlane = s.terms.contains("Plane", Tag.TAG_LONG) == terms.contains("Plane", Tag.TAG_LONG)
                && s.terms.getLong("Plane") == terms.getLong("Plane");
        if (!samePlane) { unindexPlane(s); removeIndex(columns, column(s), s.id); }
        s.terms = terms;
        if (!samePlane) { indexPlane(s); columns.computeIfAbsent(column(s), unused -> new LinkedHashSet<>()).add(s.id); }
    }
    private void indexPlane(Session s) {
        Address plane = s.plane();
        if (plane != null) planes.computeIfAbsent(plane, ignored -> new LinkedHashSet<>()).add(s.id);
    }
    private void unindexPlane(Session s) {
        Address plane = s.plane();
        if (plane != null) removeIndex(planes, plane, s.id);
    }
    private static <K> void removeIndex(Map<K, LinkedHashSet<UUID>> index, K key, UUID id) {
        var ids = index.get(key);
        if (ids != null) { ids.remove(id); if (ids.isEmpty()) index.remove(key); }
    }
    private static BlockPos source(Session s) {
        return s.terms.contains("Plane", Tag.TAG_LONG) ? BlockPos.of(s.terms.getLong("Plane")) : s.center.position();
    }
    private static Address column(Session s) { BlockPos p = source(s); return new Address(s.center.dimension(), new BlockPos(p.getX(), 0, p.getZ())); }
    public List<Demand> demands(ServerLevel level, BlockPos column) {
        var ids = columns.get(new Address(level.dimension().location(), new BlockPos(column.getX(), 0, column.getZ())));
        if (ids == null) return List.of();
        return ids.stream().map(sessions::get).filter(s -> s.phase != Phase.ENDED)
                .map(s -> new Demand(s.id, s.definition.pointCost, s.startOrder, s.funded, source(s),
                        RitualCenters.execution(s.members).axis())).toList();
    }
    public void completeMemberImport(ServerLevel level, UUID id) {
        Session s = sessions.get(id);
        if (s != null) { s.terms.remove("ImportMembers"); s.terms.remove("ImportStores"); setDirty(); }
    }
    public List<RitualCenters.Member> members(UUID id) { var s = sessions.get(id); return s == null ? List.of() : s.members; }
    public void members(ServerLevel level, UUID id, List<RitualCenters.Member> members) {
        Session s = sessions.get(id);
        if (s == null || s.members.equals(members)) return;
        unindexMembers(s);
        s.members = List.copyOf(members);
        invalidateMembers(id);
        indexMembers(s);
        RitualPoints.changed(level, source(s)); setDirty();
    }
    private void indexMembers(Session s) {
        for (var member : s.members) {
            var ref = member.reference();
            addresses.computeIfAbsent(new Address(ref.dimension(), ref.position()), unused -> new LinkedHashSet<>()).add(s.id);
            memberChunks.computeIfAbsent(new MemberChunk(ref.dimension(), ChunkPos.asLong(ref.position())), unused -> new LinkedHashSet<>()).add(s.id);
        }
    }
    private void unindexMembers(Session s) {
        for (var member : s.members) {
            var ref = member.reference();
            removeIndex(addresses, new Address(ref.dimension(), ref.position()), s.id);
            removeIndex(memberChunks, new MemberChunk(ref.dimension(), ChunkPos.asLong(ref.position())), s.id);
        }
    }
    public Optional<Handle> handle(UUID id) { var s = sessions.get(id); return s == null ? Optional.empty() : Optional.of(new Handle(id, s.center, s.definition)); }
    public BlockPos source(UUID id) { return source(sessions.get(id)); }
    public int poolMask(ServerLevel level, BlockPos pos, int observed) {
        return poolMasks.getOrDefault(new Address(level.dimension().location(), pos), observed);
    }
    public void rememberPool(ServerLevel level, BlockPos pos, int observed) {
        Address key = new Address(level.dimension().location(), pos.immutable());
        if (observed == 0) { if (poolMasks.remove(key) != null) setDirty(); }
        else if (!java.util.Objects.equals(poolMasks.put(key, observed), observed)) setDirty();
    }
    List<Handle> publishFunding(ServerLevel level, List<Demand> demands, Set<UUID> grants, Set<UUID> awaiting) {
        var revoked = new java.util.ArrayList<Handle>();
        for (var demand : demands) {
            Session s = sessions.get(demand.id());
            if (s == null || s.phase == Phase.ENDED) continue;
            pending(s, awaiting.contains(s.id));
            boolean funded = grants.contains(s.id);
            if (s.funded != funded) { s.funded = funded; s.permissionRevision++; setDirty(); }
            if (!funded && s.available) {
                s.available = false; s.permissionRevision++;
                revoked.add(new Handle(s.id, s.center, s.definition));
            }
        }
        return List.copyOf(revoked);
    }
    boolean revoked(UUID id) { var s = sessions.get(id); return s != null && !s.available; }
    public void revoke(ServerLevel level, UUID id) {
        Session s = sessions.get(id);
        if (s == null) return;
        boolean wasAvailable = s.available; s.available = false; pending(s, false);
        if (wasAvailable) { s.permissionRevision++; RitualCenters.revoked(level, new Handle(id, s.center, s.definition)); }
    }
    private RitualPoints.Permission permission(ServerLevel level, UUID id) {
        Session s = sessions.get(id);
        if (s == null) return RitualPoints.Permission.DENIED;
        RitualPoints.Permission permission = RitualPoints.Permission.DENIED;
        if (supported() && s.phase != Phase.ENDED && !RitualCenters.cleanupPending(level, id)) {
            var state = RitualCenters.state(level, id);
            permission = state.pending() ? RitualPoints.Permission.PENDING
                    : state.ready() ? RitualPoints.permission(level, id) : RitualPoints.Permission.DENIED;
        }
        if (sessions.get(id) != s || s.phase == Phase.ENDED || RitualCenters.cleanupPending(level, id))
            permission = RitualPoints.Permission.DENIED;
        pending(s, permission == RitualPoints.Permission.PENDING);
        return permission;
    }
    private static void pending(Session s, boolean pending) {
        if (s.permissionPending != pending) { s.permissionPending = pending; s.permissionRevision++; }
    }
    /** The last permission query deferred; callers may skip work without discarding registrations. */
    public boolean pending(UUID id) {
        var s = sessions.get(id);
        return supported() && s != null && s.phase != Phase.ENDED && s.permissionPending;
    }
    public boolean eligible(ServerLevel level, UUID id) {
        var permission = permission(level, id);
        if (permission == RitualPoints.Permission.DENIED) revoke(level, id);
        return permission == RitualPoints.Permission.ALLOWED;
    }
    public boolean running(ServerLevel level, UUID id) {
        Session s = sessions.get(id);
        return s != null && s.phase == Phase.ACTIVE && s.available && eligible(level, id)
                && sessions.get(id) == s && s.phase == Phase.ACTIVE && s.available;
    }
    public RitualSite site(ServerLevel level, UUID id) {
        Session s = sessions.get(id); if (s == null) return null;
        var state = RitualCenters.state(level, id);
        if (!state.present()) return null;
        var execution = RitualCenters.execution(s.members);
        var center = new RitualCenters.Center(execution.reference(), execution.axis(), state.ready());
        int mask = s.terms.getInt("SpatialMask");
        if (mask == 0) {
            mask = RitualPlane.observe(level, source(s)).completeMask();
            if (mask != 0) { s.terms.putInt("SpatialMask", mask); setDirty(); }
        }
        return new RitualSite(center, new RitualSite.Footprint(source(s), mask), id);
    }
    private static void validateTerms(CompoundTag tag) {
        if (tag == null || tag.sizeInBytes() > MAX_TERMS_BYTES) throw new IllegalArgumentException("Ritual terms exceed the storage envelope");
    }
    private static void checkThread(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Structural rituals belong to the server thread");
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (preserved != null) return preserved.copy();
        tag.putInt("Version", 3);
        ListTag list = new ListTag();
        for (Session s : sessions.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", s.id); entry.putUUID("Initiator", s.initiator); entry.putUUID("CenterIdentity", s.center.identity());
            entry.putString("Dimension", s.center.dimension().toString()); entry.putLong("Position", s.center.position().asLong());
            entry.putString("Kind", s.definition.kind.toString()); entry.putInt("DefinitionVersion", s.definition.version);
            entry.putInt("Duration", s.definition.duration); entry.putIntArray("Milestones", s.definition.steps);
            entry.putInt("PointCost", s.definition.pointCost);
            entry.putBoolean("Funded", s.funded);
            entry.putLong("StartOrder", s.startOrder);
            ListTag members = new ListTag();
            for (var member : s.members) members.add(member.save());
            entry.put("Members", members);
            if (s.reservation != null) entry.putUUID("Reservation", s.reservation);
            s.definition.research.ifPresent(research -> {
                CompoundTag requirement = new CompoundTag();
                requirement.putString("Provider", research.provider().toString()); requirement.putString("Key", research.key());
                entry.put("Research", requirement);
            });
            entry.putString("Phase", s.phase.name()); entry.putInt("Elapsed", s.elapsed); entry.putInt("Committed", s.step);
            entry.put("Terms", s.terms.copy()); entry.put("Runtime", s.runtime.copy()); list.add(entry);
        }
        ListTag pools = new ListTag();
        poolMasks.forEach((address, mask) -> { CompoundTag row = new CompoundTag(); row.putString("Dimension", address.dimension.toString()); row.putLong("Position", address.position.asLong()); row.putInt("Mask", mask); pools.add(row); });
        tag.put("Pools", pools);
        tag.put("Sessions", list);
        return tag;
    }
    private static StructuralRitualStore load(CompoundTag tag, HolderLookup.Provider registries) {
        StructuralRitualStore result = new StructuralRitualStore();
        try {
            int version = tag.getInt("Version");
            if (!tag.contains("Version", Tag.TAG_INT) || version != 2 && version != 3 || !(tag.get("Sessions") instanceof ListTag list)
                    || list.size() > MAX_SESSIONS || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)
                throw new IllegalArgumentException("Unsupported structural ritual envelope");
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                if (!entry.hasUUID("Id") || !entry.hasUUID("Initiator") || !entry.hasUUID("CenterIdentity")
                        || !entry.contains("Position", Tag.TAG_LONG) || !entry.contains("DefinitionVersion", Tag.TAG_INT)
                        || !entry.contains("Duration", Tag.TAG_INT) || !entry.contains("Milestones", Tag.TAG_INT_ARRAY)
                        || !entry.contains("Elapsed", Tag.TAG_INT) || !entry.contains("Committed", Tag.TAG_INT)
                        || !entry.contains("Terms", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Incomplete structural ritual");
                ResourceLocation dimension = location(entry.getString("Dimension")), kind = location(entry.getString("Kind"));
                Optional<RitualResearch.Requirement> research = Optional.empty();
                if (entry.contains("Research")) {
                    if (!entry.contains("Research", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Invalid research requirement");
                    CompoundTag requirement = entry.getCompound("Research");
                    research = Optional.of(new RitualResearch.Requirement(location(requirement.getString("Provider")), requirement.getString("Key")));
                }
                if (!entry.contains(version == 2 ? "MinimumRing" : "PointCost", Tag.TAG_INT) || !entry.contains("Runtime", Tag.TAG_COMPOUND))
                    throw new IllegalArgumentException("Incomplete multi-ring receipt");
                Definition definition = new Definition(kind, entry.getInt("DefinitionVersion"), entry.getInt("Duration"),
                        entry.getIntArray("Milestones"), entry.getInt(version == 2 ? "MinimumRing" : "PointCost"), research);
                CompoundTag terms = entry.getCompound("Terms"); validateTerms(terms);
                Session s = new Session(entry.getUUID("Id"), entry.getUUID("Initiator"),
                        new RitualCenters.Reference(dimension, BlockPos.of(entry.getLong("Position")), entry.getUUID("CenterIdentity")), definition, terms);
                s.phase = Phase.valueOf(entry.getString("Phase")); s.elapsed = entry.getInt("Elapsed"); s.step = entry.getInt("Committed");
                if (entry.contains("Reservation") && !entry.hasUUID("Reservation")) throw new IllegalArgumentException("Invalid resource reservation");
                if (s.phase == Phase.PREPARING) s.reservation = entry.hasUUID("Reservation") ? entry.getUUID("Reservation")
                        : terms.getBoolean("Armed") && terms.hasUUID("Trigger") ? terms.getUUID("Trigger") : null;
                s.runtime = entry.getCompound("Runtime").copy(); validateTerms(s.runtime);
                if (version == 3) {
                    ListTag members = entry.getList("Members", Tag.TAG_COMPOUND);
                    if (members.isEmpty() || members.size() > 256) throw new IllegalArgumentException("Invalid ritual members");
                    var loaded = new java.util.ArrayList<RitualCenters.Member>();
                    for (int m = 0; m < members.size(); m++) loaded.add(RitualCenters.Member.load(dimension, members.getCompound(m)));
                    s.members = List.copyOf(loaded); s.funded = entry.getBoolean("Funded");
                } else {
                    // Migration facts are resolved once after the owning dimension is available.
                    terms.putBoolean("ImportMembers", true);
                    terms.putBoolean("ImportStores", terms.getBoolean("PairedCenter"));
                    terms.remove("PairedCenter"); terms.remove("BearingRing");
                    s.terms = terms.copy(); s.funded = true;
                }
                if (s.elapsed < 0 || s.elapsed > definition.duration || s.step < 0 || s.step > definition.steps.length
                        || s.step > 0 && s.elapsed < definition.steps[s.step - 1]
                        || s.phase == Phase.PREPARING && (s.step >= definition.steps.length || s.elapsed > definition.steps[s.step])
                        || s.phase == Phase.ACTIVE && (s.step != definition.steps.length || s.elapsed != definition.duration)
                        || result.sessions.containsKey(s.id))
                    throw new IllegalArgumentException("Inconsistent structural ritual receipt");
                result.insert(s);
                if (version == 3) { s.startOrder = entry.getLong("StartOrder"); result.nextStartOrder = Math.max(result.nextStartOrder, s.startOrder + 1); }
            }
            for (Tag value : tag.getList("Pools", Tag.TAG_COMPOUND)) {
                CompoundTag pool = (CompoundTag) value;
                result.poolMasks.put(new Address(location(pool.getString("Dimension")), BlockPos.of(pool.getLong("Position"))), pool.getInt("Mask") & 7);
            }
            if (version == 2) result.setDirty();
            return result;
        } catch (IllegalArgumentException invalidData) {
            result = new StructuralRitualStore(); result.preserved = tag.copy();
            org.slf4j.LoggerFactory.getLogger(StructuralRitualStore.class).error(
                    "Structural ritual data retained unchanged; execution disabled: {}", invalidData.getMessage());
            return result;
        }
    }
    private static ResourceLocation location(String value) {
        if (value.length() > 256) throw new IllegalArgumentException("Oversized ritual location");
        ResourceLocation parsed = ResourceLocation.tryParse(value);
        if (parsed == null) throw new IllegalArgumentException("Invalid ritual location");
        return parsed;
    }
}
