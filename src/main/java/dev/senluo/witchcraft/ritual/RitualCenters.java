package dev.senluo.witchcraft.ritual;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.HashSet;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Center adapters supply facts only. The common matcher never needs to identify a tree or a crucible. */
public final class RitualCenters {
    @FunctionalInterface public interface Companion { BlockEntity find(ServerLevel level, BlockPos plane); }
    /** Activation adapters own target requirements; the ledger still owns payment and lifetime. */
    public interface Behavior {
        StructuralRitualStore.Definition definition();
        default ResourceLocation kind() { return definition().kind(); }
        net.minecraft.world.InteractionResult activate(ServerLevel level, RitualSite site, net.minecraft.server.level.ServerPlayer player);
        /** Physical center presence; temporary inability to run must not be reported as destruction. */
        default Companion companion() { return null; }
        /** Only old receipts that already supported composite recovery opt into this one-time metadata restoration. */
        default boolean legacyPair() { return false; }
        default void tick(ServerLevel level, RitualSite site, StructuralRitualStore.Handle handle) {}
        default void unavailable(ServerLevel level, StructuralRitualStore.Handle handle) {}
        default void removed(ServerLevel level, StructuralRitualStore.Handle handle) {
            unavailable(level, handle);
            var store = StructuralRitualStore.get(level);
            store.end(level, handle.id()); store.finish(handle.id());
        }
    }
    private static final Map<ResourceLocation, Behavior> BEHAVIORS = new LinkedHashMap<>();
    public static void register(Behavior behavior) {
        if (BEHAVIORS.putIfAbsent(behavior.kind(), behavior) != null)
            throw new IllegalStateException("Duplicate ritual behavior " + behavior.kind());
    }
    public static void registerRuntime() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(RitualCenters::tick);
    }
    public static Optional<RitualSite> site(BlockEntity entity) {
        return site(entity, new net.minecraft.nbt.CompoundTag());
    }
    public static Optional<RitualSite> site(BlockEntity entity, net.minecraft.nbt.CompoundTag terms) {
        return observe(entity).map(center -> new RitualSite(center,
                (terms.contains("Plane", net.minecraft.nbt.Tag.TAG_LONG)
                        ? RitualPlane.at((ServerLevel) entity.getLevel(), BlockPos.of(terms.getLong("Plane")))
                        : RitualPlane.find((ServerLevel) entity.getLevel(), center.axis()))
                        .filter(plane -> plane.containsCenter(center.axis())).orElse(null)));
    }
    public static boolean current(ServerLevel level, RitualSite site) {
        if (site.plane() == null || !site.reference().dimension().equals(level.dimension().location())
                || !level.hasChunkAt(site.position())) return false;
        return observe(level.getBlockEntity(site.position())).filter(center -> center.mayPrepare()
                && center.reference().equals(site.reference()) && site.plane().containsCenter(center.axis())).isPresent()
                && RitualPlane.at(level, site.plane().anchor()).filter(site.plane()::equals).isPresent();
    }
    public static net.minecraft.world.InteractionResult activate(net.minecraft.server.level.ServerPlayer player, BlockEntity entity) {
        var level = player.serverLevel();
        if (entity != null) RitualPlane.invalidate(level, entity.getBlockPos());
        var site = site(entity).orElse(null);
        return site == null ? net.minecraft.world.InteractionResult.PASS : activate(player, site);
    }
    /** Other activation routes submit the same facts; they never call the effect ticker directly. */
    public static net.minecraft.world.InteractionResult activate(net.minecraft.server.level.ServerPlayer player, RitualSite site) {
        var level = player.serverLevel();
        if (site == null || !site.center().mayPrepare() || player.isFakePlayer() || player.isSpectator()
                || !player.mayBuild() || !level.mayInteract(player, site.position()) || !current(level, site))
            return net.minecraft.world.InteractionResult.PASS;
        for (var behavior : BEHAVIORS.values()) {
            var result = behavior.activate(level, site, player);
            if (result != net.minecraft.world.InteractionResult.PASS) return result;
        }
        return net.minecraft.world.InteractionResult.PASS;
    }
    public static void removed(ServerLevel level, Reference reference) {
        var store = StructuralRitualStore.get(level);
        for (var handle : store.allAt(reference)) {
            var behavior = BEHAVIORS.get(handle.definition().kind());
            if (behavior == null) continue;
            var terms = store.progress(handle.id()).orElseThrow().terms();
            BlockPos plane = terms.contains("Plane", Tag.TAG_LONG) ? BlockPos.of(terms.getLong("Plane")) : reference.position().below();
            if (paired(behavior, level.getBlockEntity(reference.position()), handle, terms) && companion(behavior, level, plane) != null)
                unavailable(level, behavior, handle);
            else behavior.removed(level, handle);
        }
    }
    /** Compose the real center independently of which object received the player's click. */
    public static Optional<UUID> begin(net.minecraft.server.level.ServerPlayer player, RitualSite site,
            StructuralRitualStore.Definition definition, CompoundTag suppliedTerms) {
        ServerLevel level = player.serverLevel();
        var behavior = BEHAVIORS.get(definition.kind());
        var companion = behavior == null ? null : companion(behavior, level, site.plane().anchor());
        var support = companion == null ? null : companionSupport(level, site.plane());
        var center = companion == null ? site.center() : observe(support == null ? companion : support).orElse(null);
        if (center == null || !center.mayPrepare() || !level.mayInteract(player, center.reference().position())
                || companion != null && !level.mayInteract(player, companion.getBlockPos())) return Optional.empty();
        CompoundTag terms = suppliedTerms.copy();
        terms.putLong("Plane", site.plane().anchor().asLong()); terms.putBoolean("PairedCenter", support != null);
        return StructuralRitualStore.get(level).begin(level, definition, center, player.getUUID(), terms);
    }
    private static boolean paired(Behavior behavior, BlockEntity entity, StructuralRitualStore.Handle handle, CompoundTag terms) {
        if (terms.contains("PairedCenter", Tag.TAG_BYTE)) return terms.getBoolean("PairedCenter");
        if (behavior.companion() == null) return false;
        // A replacement at this address is not evidence of the original receipt's composition.
        var observed = observe(entity).orElse(null);
        return observed != null && observed.reference().equals(handle.center())
                ? supportsCompanion(entity) : behavior.legacyPair();
    }
    private static BlockEntity companion(Behavior behavior, ServerLevel level, BlockPos plane) {
        var finder = behavior.companion();
        return finder == null ? null : finder.find(level, plane);
    }
    private static void unavailable(ServerLevel level, Behavior behavior, StructuralRitualStore.Handle handle) {
        StructuralRitualStore.get(level).advance(level, handle.id(), handle.definition(), StructuralRitualStore.Presence.UNLOADED,
                null, RitualShape.Match.UNLOADED, (step, terms) -> Optional.empty());
        behavior.unavailable(level, handle);
    }
    private static void tick(net.neoforged.neoforge.event.tick.LevelTickEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.tickRateManager().runsNormally()) return;
        var store = StructuralRitualStore.get(level);
        for (var h : store.inDimension(level.dimension().location())) {
            var progress = store.progress(h.id()).orElse(null);
            if (progress == null) continue;
            var behavior = BEHAVIORS.get(h.definition().kind());
            if (behavior == null) continue;
            if (h.definition().version() != behavior.definition().version()) { unavailable(level, behavior, h); continue; }
            if (progress.phase() == StructuralRitualStore.Phase.ENDED) { behavior.removed(level, h); continue; }
            if (!level.hasChunkAt(h.center().position()) || !level.shouldTickBlocksAt(h.center().position())) {
                unavailable(level, behavior, h); continue;
            }
            var entity = level.getBlockEntity(h.center().position());
            var terms = progress.terms();
            // Only pre-existing node composites had an implicit support plane in their saved contract.
            if (!terms.contains("Plane") && behavior.legacyPair()) terms.putLong("Plane", h.center().position().below().asLong());
            var site = site(entity, terms).orElse(null);
            if (entity != null && !entity.isRemoved() && site == null) { unavailable(level, behavior, h); continue; }
            boolean paired = paired(behavior, entity, h, terms);
            BlockPos plane = terms.contains("Plane", Tag.TAG_LONG) ? BlockPos.of(terms.getLong("Plane"))
                    : site != null && site.plane() != null ? site.plane().anchor() : null;
            if (plane != null && !store.bindSite(level, h.id(), plane, paired)) { unavailable(level, behavior, h); continue; }
            if (paired) {
                var companion = plane == null ? null : companion(behavior, level, plane);
                if (!supportsCompanion(entity)) {
                    if (companion == null) behavior.removed(level, h);
                    else unavailable(level, behavior, h);
                    continue;
                }
                if (site == null || !site.center().mayPrepare() || companion == null) { unavailable(level, behavior, h); continue; }
                if (!site.reference().equals(h.center())) {
                    var resumable = new HashSet<UUID>();
                    for (var other : store.allAt(h.center().dimension(), h.center().position())) {
                        var otherBehavior = BEHAVIORS.get(other.definition().kind());
                        if (otherBehavior != null && other.definition().version() == otherBehavior.definition().version()
                                && paired(otherBehavior, entity, other, store.progress(other.id()).orElseThrow().terms()))
                            resumable.add(other.id());
                    }
                    if (!store.rebindAt(level, site.center(), resumable)) { unavailable(level, behavior, h); continue; }
                }
            } else if (site == null || !site.reference().equals(h.center())) { behavior.removed(level, h); continue; }
            if (site.center().mayPrepare()) behavior.tick(level, site, h);
            else unavailable(level, behavior, h);
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
        default boolean supportsCompanion() { return false; }
        default BlockPos positionAtAxis(BlockPos axis) { return axis; }
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
            public boolean supportsCompanion() { return true; }
            public BlockPos positionAtAxis(BlockPos axis) { return axis.above(); }
        });
        register(dev.senluo.witchcraft.time.LivingEntity.class, new Adapter<>() {
            public BlockPos axis(dev.senluo.witchcraft.time.LivingEntity tree) {
                return tree.tissue.giant || tree.tissue.giantGrowing
                        ? tree.getBlockPos().offset(dev.senluo.witchcraft.time.TreeOrientation.relative(
                                new BlockPos(0, 0, 1), tree.tissue.treeFacing)) : tree.getBlockPos();
            }
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
            public boolean supportsCompanion() { return adapter.supportsCompanion(); }
            public BlockPos positionAtAxis(BlockPos axis) { return adapter.positionAtAxis(axis); }
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
    /** Shared bounded lookup; the caller supplies only the physical object's category. */
    public static BlockEntity find(ServerLevel level, BlockPos plane, java.util.function.Predicate<BlockEntity> accepts) {
        return RitualPlane.centerPositions(plane).filter(pos -> level.hasChunkAt(pos) && level.shouldTickBlocksAt(pos))
                .map(level::getBlockEntity).filter(entity -> entity != null && !entity.isRemoved()).filter(accepts).findFirst().orElse(null);
    }
    private static boolean supportsCompanion(BlockEntity entity) { return entity != null && adapter(entity).supportsCompanion(); }
    private static BlockEntity companionSupport(ServerLevel level, RitualPlane plane) {
        for (var entry : ADAPTERS.entrySet()) if (entry.getValue().supportsCompanion()) {
            var support = RitualPlane.centerPositions(plane.anchor()).map(entry.getValue()::positionAtAxis)
                    .filter(pos -> level.hasChunkAt(pos) && level.shouldTickBlocksAt(pos)).map(level::getBlockEntity)
                    .filter(entry.getKey()::isInstance).filter(entity -> plane.containsCenter(axis(entity))).findFirst();
            if (support.isPresent()) return support.get();
        }
        return null;
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
