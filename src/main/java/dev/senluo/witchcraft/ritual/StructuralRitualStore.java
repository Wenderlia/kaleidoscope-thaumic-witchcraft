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
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Server-wide lifecycle/receipt ledger, driven by an external host. No scans, forced loads,
 * wall-clock catch-up, or automatic machine updates. A center identity is pinned to one address,
 * and may host up to three ordered sessions. Restoring a receipt never replays a paid action.
 */
public final class StructuralRitualStore extends SavedData {
    private static final int MAX_SESSIONS = 65_536, MAX_TERMS_BYTES = 16_384;
    private static final String NAME = "witchcraft_structural_rituals";
    private static final Factory<StructuralRitualStore> FACTORY = new Factory<>(StructuralRitualStore::new, StructuralRitualStore::load);
    public enum Phase { PREPARING, ACTIVE, ENDED }
    public enum Presence { PRESENT, UNLOADED, MISSING }
    public record Definition(ResourceLocation kind, int version, int duration, int[] steps, int minimumRing,
                             Optional<RitualResearch.Requirement> research) {
        public Definition(ResourceLocation kind, int version, int duration, int[] steps, Optional<RitualResearch.Requirement> research) {
            this(kind, version, duration, steps, RitualRings.SMALL, research);
        }
        public Definition {
            java.util.Objects.requireNonNull(kind); java.util.Objects.requireNonNull(research);
            if (kind.toString().length() > 256 || version < 1 || duration < 1 || steps == null || steps.length < 1 || steps.length > 64
                    || minimumRing < RitualRings.SMALL || minimumRing > RitualRings.LARGE)
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
    private record Address(ResourceLocation dimension, BlockPos position) {}
    private static final class Session {
        final UUID id, initiator;
        RitualCenters.Reference center;
        Definition definition;
        CompoundTag terms, runtime = new CompoundTag();
        UUID reservation;
        Phase phase = Phase.PREPARING;
        int elapsed, step;
        // Runtime verification is intentionally not persisted. Every reload starts unavailable.
        boolean available, advancing, researchAllowed;
        long lastTick = Long.MIN_VALUE, lastResearchCheck = Long.MIN_VALUE;
        long startOrder;
        Session(UUID id, UUID initiator, RitualCenters.Reference center, Definition definition, CompoundTag terms) {
            this.id = id; this.initiator = initiator; this.center = center; this.definition = definition; this.terms = terms.copy();
        }
        Address address() { return new Address(center.dimension(), center.position()); }
        Address plane() { return terms.contains("Plane", Tag.TAG_LONG)
                ? new Address(center.dimension(), BlockPos.of(terms.getLong("Plane"))) : null; }
        boolean sameStructure(Definition other) {
            return definition.kind.equals(other.kind) && definition.version == other.version
                    && definition.duration == other.duration && Arrays.equals(definition.steps, other.steps)
                    && definition.minimumRing == other.minimumRing;
        }
    }
    // Insertion order is the durable start priority, including across unload/restart.
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();
    private final Map<ResourceLocation, LinkedHashSet<UUID>> dimensions = new HashMap<>();
    private final Map<UUID, Address> centers = new HashMap<>();
    private final Map<Address, LinkedHashSet<UUID>> addresses = new HashMap<>();
    // Derived from authoritative receipts; not serialized and never used as a validity check.
    private final Map<Address, LinkedHashSet<UUID>> planes = new HashMap<>();
    private final Map<ResourceLocation, LinkedHashSet<UUID>> unboundPlanes = new HashMap<>();
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
    /** Local candidates in durable start order. The host resolves only receipts without a bound plane. */
    public List<Handle> atPlane(ResourceLocation dimension, BlockPos anchor, Predicate<Handle> matchesUnbound) {
        if (!supported()) return List.of();
        var candidates = new java.util.ArrayList<Session>();
        var bound = planes.get(new Address(dimension, anchor));
        if (bound != null) for (UUID id : bound) {
            Session s = sessions.get(id);
            if (s.phase != Phase.ENDED) candidates.add(s);
        }
        var unbound = unboundPlanes.get(dimension);
        if (unbound != null) for (UUID id : unbound) {
            Session s = sessions.get(id);
            if (s.phase != Phase.ENDED && matchesUnbound.test(new Handle(s.id, s.center, s.definition))) candidates.add(s);
        }
        candidates.sort(java.util.Comparator.comparingLong(s -> s.startOrder));
        return candidates.stream().map(s -> new Handle(s.id, s.center, s.definition)).toList();
    }
    /** Ring allocation needs this scalar, not a copied transaction/NBT snapshot. */
    public int bearingRing(UUID id) {
        Session s = sessions.get(id);
        return supported() && s != null ? s.terms.getInt(GoldenRitualSlots.PINNED_RING) : 0;
    }
    public Optional<UUID> at(RitualCenters.Reference center) {
        return allAt(center).stream().map(Handle::id).findFirst();
    }
    public List<Handle> allAt(RitualCenters.Reference center) {
        var ids = addresses.get(new Address(center.dimension(), center.position()));
        if (!supported() || ids == null) return List.of();
        return ids.stream().map(sessions::get).filter(s -> s.center.equals(center))
                .map(s -> new Handle(s.id, s.center, s.definition)).toList();
    }
    public List<Handle> allAt(ResourceLocation dimension, BlockPos position) {
        var ids = addresses.get(new Address(dimension, position));
        if (!supported() || ids == null) return List.of();
        return ids.stream().map(sessions::get).map(s -> new Handle(s.id, s.center, s.definition)).toList();
    }
    /** Explicit composite-center recovery at the SAME address, only when every remaining receipt opts in. */
    public boolean rebindAt(ServerLevel level, RitualCenters.Center observed, Set<UUID> resumableSessions) {
        checkThread(level);
        var reference = observed.reference();
        if (!supported() || !observed.mayPrepare() || !reference.dimension().equals(level.dimension().location())) return false;
        var address = new Address(reference.dimension(), reference.position());
        var ids = addresses.get(address);
        if (ids == null || centers.containsKey(reference.identity()) && !centers.get(reference.identity()).equals(address)) return false;
        for (var id : ids) {
            var s = sessions.get(id);
            if (s.advancing || !resumableSessions.contains(id)) return false;
        }
        for (var id : ids) {
            var s = sessions.get(id);
            if (s.center.equals(reference)) continue;
            centers.remove(s.center.identity(), address);
            s.center = reference; s.available = false; setDirty();
        }
        centers.put(reference.identity(), address);
        return true;
    }
    /** An untyped preparation chooses its behavior before any milestone is advanced or paid. */
    public boolean select(ServerLevel level, UUID id, Definition definition, CompoundTag terms, UUID reservation) {
        checkThread(level); validateTerms(terms);
        Session s = sessions.get(id);
        if (!supported() || s == null || s.advancing || s.phase != Phase.PREPARING || s.step != 0 || s.elapsed != 0
                || s.terms.getBoolean("Armed")
                || !reservationFree(id, reservation)
                || RitualResearch.check(level, s.initiator, definition.research) != RitualResearch.Result.ALLOWED) return false;
        s.definition = definition; replaceTerms(s, terms.copy());
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
    /** Bind missing geometry once, including paid receipts; never reset progress or move an existing binding. */
    public boolean bindSite(ServerLevel level, UUID id, BlockPos plane, boolean paired) {
        checkThread(level);
        Session s = sessions.get(id);
        if (!supported() || s == null || s.advancing || s.phase == Phase.ENDED
                || !s.center.dimension().equals(level.dimension().location())) return false;
        if (s.terms.contains("Plane") && (!s.terms.contains("Plane", Tag.TAG_LONG) || s.terms.getLong("Plane") != plane.asLong())
                || s.terms.contains("PairedCenter") && (!s.terms.contains("PairedCenter", Tag.TAG_BYTE)
                || s.terms.getBoolean("PairedCenter") != paired)) return false;
        if (s.terms.contains("Plane") && s.terms.contains("PairedCenter")) return true;
        CompoundTag terms = s.terms.copy(); terms.putLong("Plane", plane.asLong()); terms.putBoolean("PairedCenter", paired); validateTerms(terms);
        replaceTerms(s, terms); s.available = false; setDirty(); return true;
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
        if (supported() && s != null && !s.advancing) { s.phase = Phase.ENDED; s.available = false; reserve(s, null); setDirty(); }
    }
    public Optional<UUID> begin(ServerLevel level, Definition definition, RitualCenters.Center observed,
                                UUID initiator, CompoundTag terms) {
        checkThread(level);
        java.util.Objects.requireNonNull(initiator); validateTerms(terms);
        if (!supported() || sessions.size() >= MAX_SESSIONS || !observed.mayPrepare()
                || !observed.reference().dimension().equals(level.dimension().location())
                || RitualResearch.check(level, initiator, definition.research) != RitualResearch.Result.ALLOWED) return Optional.empty();
        var center = observed.reference();
        Address address = new Address(center.dimension(), center.position());
        if (centers.containsKey(center.identity()) && !centers.get(center.identity()).equals(address)) return Optional.empty();
        var occupants = addresses.get(address);
        if (occupants != null && (occupants.size() >= 3 || occupants.stream()
                .anyMatch(id -> !sessions.get(id).center.equals(center)))) return Optional.empty();
        Session session = new Session(UUID.randomUUID(), initiator, center, definition, terms);
        insert(session); setDirty();
        return Optional.of(session.id);
    }
    public Optional<Progress> progress(UUID id) {
        Session s = sessions.get(id);
        return s == null || !supported() ? Optional.empty()
                : Optional.of(new Progress(s.phase, s.elapsed, s.step, s.available, s.terms));
    }

    /** One call for one actual game tick. The host supplies a fresh, non-forcing center/shape observation. */
    public TickResult advance(ServerLevel level, UUID id, Definition definition, Presence presence,
                              RitualCenters.Reference observed, RitualShape.Match structure, StepAction action) {
        return advance(level, id, definition, presence, observed, structure, true, action);
    }
    /** Resource availability pauses time; it does not masquerade as destruction or an invalid ring. */
    public TickResult advance(ServerLevel level, UUID id, Definition definition, Presence presence,
                              RitualCenters.Reference observed, RitualShape.Match structure, boolean resourcesAvailable, StepAction action) {
        checkThread(level);
        Session s = sessions.get(id);
        if (!supported() || s == null) return new TickResult(Phase.ENDED, false, -1);
        if (s.advancing) throw new IllegalStateException("Reentrant ritual step");
        if (!s.center.dimension().equals(level.dimension().location()) || !s.sameStructure(definition)) {
            s.available = false;
            return result(s, -1);
        }
        if (s.phase == Phase.ENDED) return result(s, -1);
        // Paid active receipts keep running; unfinished preparations must adopt today's entry requirement.
        if (s.phase == Phase.PREPARING && !s.definition.research.equals(definition.research)) {
            s.definition = definition;
            s.lastResearchCheck = Long.MIN_VALUE; s.researchAllowed = false;
            setDirty();
        }
        // Only a verified lookup at the original address may report replacement. A moved clone cannot end its source.
        if (presence == Presence.MISSING || presence == Presence.PRESENT && observed != null
                && s.center.position().equals(observed.position()) && s.center.dimension().equals(observed.dimension())
                && !s.center.identity().equals(observed.identity())) {
            s.phase = Phase.ENDED; s.available = false; reserve(s, null); setDirty();
            return result(s, -1);
        }
        s.available = resourcesAvailable && presence == Presence.PRESENT && s.center.equals(observed) && structure == RitualShape.Match.VALID;
        long now = level.getGameTime();
        if (!s.available) return result(s, -1);
        if (s.lastTick == now) return new TickResult(s.phase, false, -1);
        s.lastTick = now;
        if (s.phase == Phase.ACTIVE) return result(s, -1);
        if (!researchAllowed(level, s, false)) { s.available = false; return result(s, -1); }
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
            if (!researchAllowed(level, s, true)) { s.available = false; return result(s, -1); }
            if (!transaction.commit.getAsBoolean()) return result(s, -1);
            replaceTerms(s, after);
            int committed = s.step++;
            if (finalStep) { s.phase = Phase.ACTIVE; reserve(s, null); }
            setDirty();
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
    public boolean finish(UUID id) {
        Session s = sessions.get(id);
        if (!supported() || s == null || s.phase != Phase.ENDED || s.advancing) return false;
        reserve(s, null); unindexPlane(s); sessions.remove(id);
        removeIndex(dimensions, s.center.dimension(), id);
        var ids = addresses.get(s.address());
        if (ids != null) {
            ids.remove(id);
            if (ids.isEmpty()) { addresses.remove(s.address()); centers.remove(s.center.identity(), s.address()); }
        }
        setDirty();
        return true;
    }
    private static TickResult result(Session s, int step) { return new TickResult(s.phase, s.available && s.phase == Phase.ACTIVE, step); }
    private void insert(Session s) {
        s.startOrder = nextStartOrder++;
        sessions.put(s.id, s); centers.put(s.center.identity(), s.address());
        dimensions.computeIfAbsent(s.center.dimension(), ignored -> new LinkedHashSet<>()).add(s.id);
        addresses.computeIfAbsent(s.address(), ignored -> new LinkedHashSet<>()).add(s.id);
        indexPlane(s);
        // Resource reservations have one owner; a duplicate claimant must request another resource.
        if (s.phase == Phase.PREPARING && s.reservation != null) reservations.putIfAbsent(s.reservation, s.id);
    }
    /** Receives an already-owned copy; unchanged geometry costs only a scalar comparison. */
    private void replaceTerms(Session s, CompoundTag terms) {
        boolean samePlane = s.terms.contains("Plane", Tag.TAG_LONG) == terms.contains("Plane", Tag.TAG_LONG)
                && s.terms.getLong("Plane") == terms.getLong("Plane");
        if (!samePlane) unindexPlane(s);
        s.terms = terms;
        if (!samePlane) indexPlane(s);
    }
    private void indexPlane(Session s) {
        Address plane = s.plane();
        if (plane != null) planes.computeIfAbsent(plane, ignored -> new LinkedHashSet<>()).add(s.id);
        else unboundPlanes.computeIfAbsent(s.center.dimension(), ignored -> new LinkedHashSet<>()).add(s.id);
    }
    private void unindexPlane(Session s) {
        Address plane = s.plane();
        if (plane != null) removeIndex(planes, plane, s.id);
        else removeIndex(unboundPlanes, s.center.dimension(), s.id);
    }
    private static <K> void removeIndex(Map<K, LinkedHashSet<UUID>> index, K key, UUID id) {
        var ids = index.get(key);
        if (ids != null) { ids.remove(id); if (ids.isEmpty()) index.remove(key); }
    }
    private static void validateTerms(CompoundTag tag) {
        if (tag == null || tag.sizeInBytes() > MAX_TERMS_BYTES) throw new IllegalArgumentException("Ritual terms exceed the storage envelope");
    }
    private static void checkThread(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Structural rituals belong to the server thread");
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (preserved != null) return preserved.copy();
        tag.putInt("Version", 2);
        ListTag list = new ListTag();
        for (Session s : sessions.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", s.id); entry.putUUID("Initiator", s.initiator); entry.putUUID("CenterIdentity", s.center.identity());
            entry.putString("Dimension", s.center.dimension().toString()); entry.putLong("Position", s.center.position().asLong());
            entry.putString("Kind", s.definition.kind.toString()); entry.putInt("DefinitionVersion", s.definition.version);
            entry.putInt("Duration", s.definition.duration); entry.putIntArray("Milestones", s.definition.steps);
            entry.putInt("MinimumRing", s.definition.minimumRing);
            if (s.reservation != null) entry.putUUID("Reservation", s.reservation);
            s.definition.research.ifPresent(research -> {
                CompoundTag requirement = new CompoundTag();
                requirement.putString("Provider", research.provider().toString()); requirement.putString("Key", research.key());
                entry.put("Research", requirement);
            });
            entry.putString("Phase", s.phase.name()); entry.putInt("Elapsed", s.elapsed); entry.putInt("Committed", s.step);
            entry.put("Terms", s.terms.copy()); entry.put("Runtime", s.runtime.copy()); list.add(entry);
        }
        tag.put("Sessions", list);
        return tag;
    }
    private static StructuralRitualStore load(CompoundTag tag, HolderLookup.Provider registries) {
        StructuralRitualStore result = new StructuralRitualStore();
        try {
            int version = tag.getInt("Version");
            if (!tag.contains("Version", Tag.TAG_INT) || version != 2 || !(tag.get("Sessions") instanceof ListTag list)
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
                if (!entry.contains("MinimumRing", Tag.TAG_INT) || !entry.contains("Runtime", Tag.TAG_COMPOUND))
                    throw new IllegalArgumentException("Incomplete multi-ring receipt");
                Definition definition = new Definition(kind, entry.getInt("DefinitionVersion"), entry.getInt("Duration"),
                        entry.getIntArray("Milestones"), entry.getInt("MinimumRing"), research);
                CompoundTag terms = entry.getCompound("Terms"); validateTerms(terms);
                Session s = new Session(entry.getUUID("Id"), entry.getUUID("Initiator"),
                        new RitualCenters.Reference(dimension, BlockPos.of(entry.getLong("Position")), entry.getUUID("CenterIdentity")), definition, terms);
                s.phase = Phase.valueOf(entry.getString("Phase")); s.elapsed = entry.getInt("Elapsed"); s.step = entry.getInt("Committed");
                if (entry.contains("Reservation") && !entry.hasUUID("Reservation")) throw new IllegalArgumentException("Invalid resource reservation");
                if (s.phase == Phase.PREPARING) s.reservation = entry.hasUUID("Reservation") ? entry.getUUID("Reservation")
                        : terms.getBoolean("Armed") && terms.hasUUID("Trigger") ? terms.getUUID("Trigger") : null;
                s.runtime = entry.getCompound("Runtime").copy(); validateTerms(s.runtime);
                var occupants = result.addresses.get(s.address());
                if (s.elapsed < 0 || s.elapsed > definition.duration || s.step < 0 || s.step > definition.steps.length
                        || s.step > 0 && s.elapsed < definition.steps[s.step - 1]
                        || s.phase == Phase.PREPARING && (s.step >= definition.steps.length || s.elapsed > definition.steps[s.step])
                        || s.phase == Phase.ACTIVE && (s.step != definition.steps.length || s.elapsed != definition.duration)
                        || result.sessions.containsKey(s.id)
                        || result.centers.containsKey(s.center.identity()) && !result.centers.get(s.center.identity()).equals(s.address())
                        || occupants != null && (occupants.size() >= 3 || !result.sessions.get(occupants.iterator().next()).center.equals(s.center)))
                    throw new IllegalArgumentException("Inconsistent structural ritual receipt");
                result.insert(s);
            }
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
