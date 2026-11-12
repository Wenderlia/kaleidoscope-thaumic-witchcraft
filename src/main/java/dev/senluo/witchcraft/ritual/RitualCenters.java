package dev.senluo.witchcraft.ritual;

import java.util.*;
import java.util.function.*;
import net.minecraft.nbt.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Identity and function adapters. Saving stations and work members share one receipt lifecycle. */
public final class RitualCenters {
    public enum Function { SAVE, ACTIVATE }
    public enum Role { OWNER, STORAGE, TARGET }
    public record Member(Reference reference, BlockPos axis, Role role) {
        public Member { axis = axis.immutable(); Objects.requireNonNull(reference); Objects.requireNonNull(role); }
        CompoundTag save() {
            CompoundTag tag = new CompoundTag(); tag.putUUID("Identity", reference.identity());
            tag.putLong("Position", reference.position().asLong()); tag.putLong("Axis", axis.asLong()); tag.putString("Role", role.name());
            return tag;
        }
        static Member load(ResourceLocation dimension, CompoundTag tag) {
            if (!tag.hasUUID("Identity") || !tag.contains("Position", Tag.TAG_LONG) || !tag.contains("Axis", Tag.TAG_LONG))
                throw new IllegalArgumentException("Invalid ritual member");
            return new Member(new Reference(dimension, BlockPos.of(tag.getLong("Position")), tag.getUUID("Identity")),
                    BlockPos.of(tag.getLong("Axis")), Role.valueOf(tag.getString("Role")));
        }
    }
    /** Member observations report lifecycle facts, never a substitute execution center. */
    public record State(boolean present, boolean ready, boolean gone, boolean pending) {
        public State(boolean present, boolean ready, boolean gone) { this(present, ready, gone, false); }
    }
    public interface Behavior {
        StructuralRitualStore.Definition definition();
        /** Lower values are dispatched first; equal values use the stable recipe ID. */
        int activationPriority();
        default ResourceLocation kind() { return definition().kind(); }
        InteractionResult activate(ServerLevel level, RitualSite site, ServerPlayer player);
        /** Recipe priority is outside plane choice. A rejected candidate may leave another legal plane usable. */
        default InteractionResult activateCandidates(ServerLevel level, List<RitualSite> sites, ServerPlayer player) {
            boolean rejected = false;
            for (var site : sites) {
                var result = activate(level, site, player);
                if (result != InteractionResult.PASS && result != InteractionResult.FAIL) return result;
                rejected |= result == InteractionResult.FAIL;
            }
            return rejected ? InteractionResult.FAIL : InteractionResult.PASS;
        }
        /** Physical work/parameter function, independent of the clicked or saving object. */
        default Predicate<BlockEntity> target() { return null; }
        default void tick(ServerLevel level, RitualSite site, StructuralRitualStore.Handle handle) {}
        /** A committed terminal result can finish even after its working prerequisites disappear. */
        default boolean finished(ServerLevel level, StructuralRitualStore.Handle handle) { return false; }
        default void unavailable(ServerLevel level, StructuralRitualStore.Handle handle) {}
        default void removed(ServerLevel level, StructuralRitualStore.Handle handle) {}
    }
    private static final Map<ResourceLocation, Predicate<BlockEntity>> HOSTS = new HashMap<>();
    public static void registerHost(ResourceLocation kind, Predicate<BlockEntity> accepts) { HOSTS.put(kind, accepts); }
    private static Predicate<BlockEntity> requirement(ResourceLocation kind) {
        var behavior = BEHAVIORS.get(kind); return behavior == null ? HOSTS.get(kind) : behavior.target();
    }
    private static final Map<ResourceLocation, Behavior> BEHAVIORS = new HashMap<>();
    private static final List<Behavior> ACTIVATION_ORDER = new ArrayList<>();
    private static final Map<ResourceLocation, BiConsumer<ServerLevel, StructuralRitualStore.Handle>> OBSERVERS = new HashMap<>();
    private static final Map<ServerLevel, Map<UUID, Long>> RETRIES = new WeakHashMap<>();
    public static void register(Behavior behavior) {
        if (BEHAVIORS.putIfAbsent(behavior.kind(), behavior) != null) throw new IllegalStateException("Duplicate ritual " + behavior.kind());
        ACTIVATION_ORDER.add(behavior);
        ACTIVATION_ORDER.sort(Comparator.comparingInt(Behavior::activationPriority).thenComparing(b -> b.kind().toString()));
    }
    public static void observeAvailability(ResourceLocation kind, BiConsumer<ServerLevel, StructuralRitualStore.Handle> listener) {
        OBSERVERS.merge(kind, listener, BiConsumer::andThen);
    }
    public static void registerRuntime() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(RitualCenters::tick);
    }
    public static Optional<RitualSite> site(BlockEntity entity) { return site(entity, new CompoundTag()); }
    public static Optional<RitualSite> site(BlockEntity entity, CompoundTag terms) {
        return observe(entity).map(center -> new RitualSite(center,
                (terms.contains("Plane", Tag.TAG_LONG) ? RitualPlane.at((ServerLevel) entity.getLevel(), BlockPos.of(terms.getLong("Plane")))
                        : RitualPlane.find((ServerLevel) entity.getLevel(), center.axis()))
                        .filter(plane -> plane.containsCenter(center.axis())).orElse(null)));
    }
    public static boolean current(ServerLevel level, RitualSite site) {
        if (site == null) return false;
        if (site.session() != null) return StructuralRitualStore.get(level).eligible(level, site.session());
        if (site.footprint() == null || !site.reference().dimension().equals(level.dimension().location())) return false;
        Center live = existing(level, new Member(site.reference(), site.axis(), Role.OWNER));
        return live != null && live.mayPrepare() && site.footprint().containsCenter(live.axis())
                && RitualPlane.at(level, site.footprint().anchor()).isPresent();
    }
    public static InteractionResult activate(ServerPlayer player, BlockEntity entity) {
        var level = player.serverLevel();
        if (entity != null) RitualPlane.invalidate(level, axis(entity));
        var center = observe(entity).orElse(null);
        if (center == null) return InteractionResult.PASS;
        return activateCandidates(player, RitualPlane.candidates(level, center.axis()).stream()
                .map(plane -> new RitualSite(center, plane)).toList());
    }
    public static InteractionResult activate(ServerPlayer player, RitualSite site) {
        return site == null ? InteractionResult.PASS : activateCandidates(player, List.of(site));
    }
    private static InteractionResult activateCandidates(ServerPlayer player, List<RitualSite> candidates) {
        var level = player.serverLevel();
        if (player.isFakePlayer() || player.isSpectator() || !player.mayBuild()) return InteractionResult.PASS;
        var sites = candidates.stream().filter(site -> site.center().mayPrepare()
                && level.mayInteract(player, site.position()) && current(level, site)).toList();
        if (sites.isEmpty()) return InteractionResult.PASS;
        // Resolve saved associations before dispatch; a new recipe still follows the original matching priority.
        var store = StructuralRitualStore.get(level);
        var site = sites.getFirst();
        var saved = store.allAt(site.reference());
        for (var behavior : ACTIVATION_ORDER) {
            var result = behavior.activateCandidates(level, sites, player);
            if (result != InteractionResult.PASS) return result;
        }
        BlockEntity entity = level.getBlockEntity(site.position());
        if (entity != null && adapter(entity).functions().contains(Function.SAVE)) {
            if (!saved.isEmpty()) return InteractionResult.CONSUME;
            for (var candidate : sites) {
                var receipts = store.atPlane(level.dimension().location(), candidate.footprint().anchor());
                if (receipts.isEmpty()) continue;
                var handle = receipts.getFirst();
                var members = new ArrayList<>(store.members(handle.id()));
                members.add(new Member(candidate.reference(), candidate.axis(), Role.STORAGE));
                store.members(level, handle.id(), members);
                return InteractionResult.CONSUME;
            }
        }
        return InteractionResult.PASS;
    }
    public static Optional<UUID> begin(ServerPlayer player, RitualSite site, StructuralRitualStore.Definition definition, CompoundTag supplied) {
        CompoundTag terms = supplied.copy(); terms.putLong("Plane", site.footprint().anchor().asLong());
        return StructuralRitualStore.get(player.serverLevel()).begin(player.serverLevel(), definition, site.center(), player.getUUID(), terms);
    }
    static List<Member> compose(ServerLevel level, RitualSite site, ResourceLocation kind) {
        var target = requirement(kind);
        BlockEntity clicked = level.getBlockEntity(site.position());
        BlockEntity work = target == null ? null : clicked != null && target.test(clicked) ? clicked
                : HOSTS.containsKey(kind) ? null : find(level, site.footprint().anchor(), target);
        if (work != null && !target.test(work)) work = null;
        if (target != null && work == null) return List.of();
        Center primary = work == null ? site.center() : observe(work).orElse(null);
        if (primary == null) return List.of();
        var members = new LinkedHashMap<Reference, Member>();
        Role primaryRole = work != null ? Role.TARGET : clicked != null && adapter(clicked).functions().contains(Function.SAVE)
                ? Role.STORAGE : Role.OWNER;
        members.put(primary.reference(), new Member(primary.reference(), primary.axis(), primaryRole));
        RitualPlane.centerPositions(site.footprint().anchor()).flatMap(RitualCenters::positionsAtAxis).distinct()
                .filter(level::hasChunkAt).map(level::getBlockEntity).filter(Objects::nonNull)
                .filter(entity -> !entity.isRemoved() && adapter(entity).functions().contains(Function.SAVE)
                        && site.footprint().containsCenter(axis(entity)))
                .forEach(entity -> observe(entity).ifPresent(center -> members.putIfAbsent(center.reference(), new Member(center.reference(), center.axis(), Role.STORAGE))));
        return List.copyOf(members.values());
    }
    /** Pure lookup of an already registered identity; unrelated candidates never acquire attachments. */
    public static Center existing(ServerLevel level, Member member) {
        var ref = member.reference();
        if (!ref.dimension().equals(level.dimension().location()) || !level.hasChunkAt(ref.position()) || !level.shouldTickBlocksAt(ref.position())) return null;
        var entity = level.getBlockEntity(ref.position());
        if (entity == null || entity.isRemoved()) return null;
        Center center = adapter(entity).peek(level, entity);
        return center != null && center.reference().equals(ref) ? center : null;
    }
    public static BlockEntity target(ServerLevel level, UUID id) {
        state(level, id);
        for (var member : StructuralRitualStore.get(level).members(id))
            if (member.role() == Role.TARGET && existing(level, member) != null) return level.getBlockEntity(member.reference().position());
        return null;
    }
    public static State state(ServerLevel level, UUID id) {
        return StructuralRitualStore.get(level).observeMembers(level, id, () -> inspectMembers(level, id));
    }
    /** Composition keeps the original primary first. Saving handoffs never move its registered geometry. */
    static Member execution(List<Member> members) {
        return members.stream().filter(member -> member.role() == Role.TARGET)
                .findFirst().orElse(members.getFirst());
    }
    static boolean seeksTarget(ResourceLocation kind, List<Member> members) {
        return requirement(kind) != null && members.stream().noneMatch(m -> m.role() == Role.TARGET)
                && members.stream().anyMatch(m -> m.role() == Role.STORAGE);
    }
    static boolean targetCandidate(ServerLevel level, ResourceLocation kind, BlockPos pos) {
        var entity = level.hasChunkAt(pos) ? level.getBlockEntity(pos) : null;
        var target = requirement(kind);
        return entity != null && !entity.isRemoved() && target != null && target.test(entity);
    }
    private static State inspectMembers(ServerLevel level, UUID id) {
        var store = StructuralRitualStore.get(level);
        var h = store.handle(id).orElse(null); if (h == null) return new State(false, false, true);
        var requirement = requirement(h.definition().kind());
        var members = store.members(id);
        Boolean importedStores = store.memberImport(id);
        if (importedStores != null) {
            BlockPos plane = store.source(id);
            var migrated = new ArrayList<Member>();
            Member original = members.getFirst();
            var originalEntity = level.hasChunkAt(original.reference().position()) ? level.getBlockEntity(original.reference().position()) : null;
            boolean saving = importedStores || originalEntity != null && adapter(originalEntity).functions().contains(Function.SAVE);
            migrated.add(new Member(original.reference(), original.axis(),
                    saving ? Role.STORAGE : requirement == null ? Role.OWNER : Role.TARGET));
            if (importedStores && requirement != null) {
                var candidate = find(level, plane, requirement);
                if (candidate != null) observe(candidate).ifPresent(c -> migrated.add(new Member(c.reference(), c.axis(), Role.TARGET)));
            }
            store.members(level, id, migrated); members = store.members(id);
            store.completeMemberImport(level, id);
        }
        var live = new LinkedHashMap<Member, Center>();
        boolean unknown = false;
        for (var member : members) {
            if (!level.hasChunkAt(member.reference().position()) || !level.shouldTickBlocksAt(member.reference().position())) unknown = true;
            Center observed = existing(level, member);
            if (observed != null) live.put(member, observed);
        }
        if (live.isEmpty()) return new State(false, false, !unknown);
        // Imported receipts may lack a target address. Resolve that missing function only while a saving member survives.
        if (requirement != null && members.stream().noneMatch(m -> m.role() == Role.TARGET)
                && live.keySet().stream().anyMatch(m -> m.role() == Role.STORAGE)) {
            var candidate = find(level, store.source(id), requirement);
            if (candidate != null) {
                var c = observe(candidate).orElse(null);
                if (c != null) {
                    var completed = new ArrayList<>(members);
                    var member = new Member(c.reference(), c.axis(), Role.TARGET);
                    completed.add(member); live.put(member, c);
                    store.members(level, id, completed); members = store.members(id);
                }
            }
        }
        // Replacements are admitted only at a registered address while another original member survives.
        var updated = new ArrayList<Member>();
        for (var member : members) {
            Center observed = live.get(member);
            if (observed == null && level.hasChunkAt(member.reference().position())
                    && level.shouldTickBlocksAt(member.reference().position())) {
                var entity = level.getBlockEntity(member.reference().position());
                boolean matches = entity != null && (member.role() == Role.STORAGE
                        ? adapter(entity).functions().contains(Function.SAVE)
                        : member.role() == Role.TARGET && requirement != null && requirement.test(entity));
                if (matches) observed = observe(entity).orElse(null);
            }
            if (observed != null) updated.add(new Member(observed.reference(), observed.axis(), member.role()));
            else updated.add(member);
        }
        if (!updated.equals(members)) { store.members(level, id, updated); members = store.members(id); }
        boolean present = false, targetReady = requirement == null;
        for (var member : members) {
            Center c = existing(level, member);
            if (c == null || !c.mayPrepare()) continue;
            present = true;
            if (member.role() == Role.TARGET && requirement != null && requirement.test(level.getBlockEntity(c.reference().position()))) targetReady = true;
        }
        return new State(present, present && targetReady, false);
    }
    public static void removed(ServerLevel level, Reference reference) {
        var store = StructuralRitualStore.get(level);
        for (var handle : store.allAt(reference)) {
            store.invalidateMembers(handle.id()); RitualPoints.changed(level, store.source(handle.id()));
        }
    }
    public static void changed(ServerLevel level, BlockPos position) { StructuralRitualStore.get(level).membersChanged(level, position); }
    static void changed(ServerLevel level, long chunk) { StructuralRitualStore.get(level).membersChanged(level, chunk); }
    static boolean cleanupPending(ServerLevel level, UUID id) {
        var retries = RETRIES.get(level); return retries != null && retries.containsKey(id);
    }
    static void clear(ServerLevel level) { RETRIES.remove(level); }
    public static void end(ServerLevel level, UUID id) {
        var store = StructuralRitualStore.get(level);
        store.end(level, id);
        if (store.phase(id) == StructuralRitualStore.Phase.ENDED) store.handle(id).ifPresent(h -> revoked(level, h));
    }
    /** Only failed cleanup schedules a retry. Revocation itself is already committed in the ledger. */
    static void revoked(ServerLevel level, StructuralRitualStore.Handle handle) {
        var retries = RETRIES.computeIfAbsent(level, unused -> new HashMap<>());
        if (retries.getOrDefault(handle.id(), Long.MIN_VALUE) > level.getGameTime()) return;
        try {
            var behavior = BEHAVIORS.get(handle.definition().kind());
            if (behavior != null) behavior.unavailable(level, handle);
            var observer = OBSERVERS.get(handle.definition().kind());
            if (observer != null) observer.accept(level, handle);
            var store = StructuralRitualStore.get(level);
            if (store.phase(handle.id()) == StructuralRitualStore.Phase.ENDED) {
                if (behavior != null) behavior.removed(level, handle);
                store.finish(level, handle.id());
            }
            retries.remove(handle.id());
        } catch (RuntimeException failure) {
            retries.put(handle.id(), level.getGameTime() + 20);
            org.slf4j.LoggerFactory.getLogger(RitualCenters.class).error("Ritual {} revocation failed; retry in 20 ticks", handle.id(), failure);
        }
    }
    private static void tick(net.neoforged.neoforge.event.tick.LevelTickEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.tickRateManager().runsNormally()) return;
        var store = StructuralRitualStore.get(level);
        var retries = RETRIES.get(level);
        if (retries != null) for (var retry : List.copyOf(retries.entrySet())) {
            var handle = store.handle(retry.getKey());
            if (handle.isEmpty()) retries.remove(retry.getKey());
            else if (retry.getValue() <= level.getGameTime()) revoked(level, handle.get());
        }
        for (var h : store.inDimension(level.dimension().location())) {
            if (cleanupPending(level, h.id())) continue;
            var behavior = BEHAVIORS.get(h.definition().kind());
            if (behavior != null && behavior.finished(level, h)) { end(level, h.id()); continue; }
            var state = state(level, h.id());
            if (state.pending()) continue;
            if (store.phase(h.id()) == StructuralRitualStore.Phase.ENDED || state.gone()) {
                end(level, h.id());
                continue;
            }
            if (behavior == null) continue; // Native vessel host owns its resource and settlement ordering.
            if (!state.ready() || h.definition().version() != behavior.definition().version()) {
                store.revoke(level, h.id()); continue;
            }
            if (!store.eligible(level, h.id())) continue;
            var site = store.site(level, h.id());
            if (site != null) behavior.tick(level, site, h);
        }
    }
    public record Reference(ResourceLocation dimension, BlockPos position, UUID identity) {
        public Reference {
            java.util.Objects.requireNonNull(dimension); java.util.Objects.requireNonNull(identity);
            position = position.immutable();
        }
    }
    /** Axis supplies horizontal alignment and body Y, not a preselected marked-block plane. */
    public record Center(Reference reference, BlockPos axis, boolean mayPrepare) {
        public Center {
            java.util.Objects.requireNonNull(reference);
            axis = axis.immutable();
        }
        public Optional<BlockPos> match(ServerLevel level, RitualShape shape) {
            if (!reference.dimension().equals(level.dimension().location())) return Optional.empty();
            return RitualPlane.match(level, axis, shape);
        }
    }
    @FunctionalInterface public interface Adapter<T extends BlockEntity> {
        Center observe(ServerLevel level, T entity);
        default BlockPos axis(T entity) { return entity.getBlockPos(); }
        default java.util.Set<Function> functions() { return java.util.Set.of(Function.ACTIVATE); }
        default Center peek(ServerLevel level, T entity) { return entity.hasData(RitualContent.RITUAL_ANCHOR) ? observe(level, entity) : null; }
        /** Bounded inverse candidates for source-driven target registration. */
        default java.util.stream.Stream<BlockPos> positionsAtAxis(BlockPos axis) {
            return java.util.stream.Stream.of(axis);
        }
    }
    private static final Map<Class<?>, Adapter<BlockEntity>> ADAPTERS = new LinkedHashMap<>();
    static {
        // Standard NeoForge attachment supplies identity; no machine-type allowlist is needed.
        register(BlockEntity.class, (level, entity) -> {
            boolean existing = entity.hasData(RitualContent.RITUAL_ANCHOR);
            var anchor = entity.getData(RitualContent.RITUAL_ANCHOR);
            if (!existing) entity.setChanged();
            return new Center(new Reference(level.dimension().location(), entity.getBlockPos(), anchor.identity()),
                    entity.getBlockPos(), anchor.supported());
        });
        register(GoldenHeartEntity.class, new Adapter<>() {
            public BlockPos axis(GoldenHeartEntity heart) { return heart.getBlockPos().below(); }
            public Center observe(ServerLevel level, GoldenHeartEntity heart) { return heart.center(level); }
            public java.util.Set<Function> functions() { return java.util.Set.of(Function.ACTIVATE, Function.SAVE); }
            public java.util.stream.Stream<BlockPos> positionsAtAxis(BlockPos axis) {
                return java.util.stream.Stream.of(axis.above());
            }
        });
        register(dev.senluo.witchcraft.time.LivingEntity.class, new Adapter<>() {
            public java.util.stream.Stream<BlockPos> positionsAtAxis(BlockPos axis) {
                return java.util.stream.Stream.of(axis, axis.north(), axis.south(), axis.east(), axis.west());
            }
            public BlockPos axis(dev.senluo.witchcraft.time.LivingEntity tree) {
                return tree.tissue.giant || tree.tissue.giantGrowing
                        ? tree.getBlockPos().offset(dev.senluo.witchcraft.time.TreeOrientation.relative(
                                new BlockPos(0, 0, 1), tree.tissue.treeFacing)) : tree.getBlockPos();
            }
            public Center peek(ServerLevel level, dev.senluo.witchcraft.time.LivingEntity tree) { return observe(level, tree); }
            public Center observe(ServerLevel level, dev.senluo.witchcraft.time.LivingEntity tree) {
                return tree.tissue.supported() ? new Center(new Reference(level.dimension().location(), tree.getBlockPos(), tree.tissue.identity),
                        axis(tree), true) : null;
            }
        });
    }
    private RitualCenters() {}
    /** Register during common setup; the most specific applicable base class wins. */
    public static <T extends BlockEntity> void register(Class<T> type, Adapter<T> adapter) {
        java.util.Objects.requireNonNull(adapter);
        if (ADAPTERS.containsKey(type)) throw new IllegalStateException("Ritual center adapter already registered: " + type.getName());
        ADAPTERS.put(type, new Adapter<>() {
            public BlockPos axis(BlockEntity entity) { return adapter.axis(type.cast(entity)); }
            public Center observe(ServerLevel level, BlockEntity entity) { return adapter.observe(level, type.cast(entity)); }
            public java.util.Set<Function> functions() { return adapter.functions(); }
            public Center peek(ServerLevel level, BlockEntity entity) { return adapter.peek(level, type.cast(entity)); }
            public java.util.stream.Stream<BlockPos> positionsAtAxis(BlockPos axis) { return adapter.positionsAtAxis(axis); }
        });
    }
    private static Adapter<BlockEntity> adapter(BlockEntity entity) {
        Class<?> selected = null;
        Adapter<BlockEntity> adapter = null;
        for (var entry : ADAPTERS.entrySet()) if (entry.getKey().isInstance(entity)
                && (selected == null || selected.isAssignableFrom(entry.getKey()))) {
            selected = entry.getKey(); adapter = entry.getValue();
        }
        return adapter;
    }
    /** Pure geometry lookup: an affected machine does not acquire a ritual identity or a receipt. */
    public static BlockPos axis(BlockEntity entity) { return adapter(entity).axis(entity); }
    /** Geometry belongs to center adapters, not to any individual ritual effect. */
    public static java.util.stream.Stream<BlockPos> positionsAtAxis(BlockPos axis) {
        return ADAPTERS.values().stream().flatMap(adapter -> adapter.positionsAtAxis(axis)).distinct();
    }
    /** Shared bounded lookup; the caller supplies only the physical object's category. */
    public static BlockEntity find(ServerLevel level, BlockPos plane, java.util.function.Predicate<BlockEntity> accepts) {
        return RitualPlane.centerPositions(plane).filter(pos -> level.hasChunkAt(pos) && level.shouldTickBlocksAt(pos))
                .map(level::getBlockEntity).filter(entity -> entity != null && !entity.isRemoved()).filter(accepts).findFirst().orElse(null);
    }
    public static Optional<Center> observe(BlockEntity entity) {
        if (entity == null || entity.isRemoved() || !(entity.getLevel() instanceof ServerLevel level)
                || !level.hasChunkAt(entity.getBlockPos()) || !level.shouldTickBlocksAt(entity.getBlockPos())) return Optional.empty();
        Center result = adapter(entity).observe(level, entity);
        if (result != null && (!result.reference.dimension().equals(level.dimension().location())
                || !result.reference.position().equals(entity.getBlockPos())))
            throw new IllegalStateException("Ritual center adapter returned a foreign location");
        return Optional.ofNullable(result);
    }
}
