package dev.senluo.witchcraft.region;

import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

/** Dimension-local spatial facts. No ticks, entity scans, world access or chunk tickets. */
public final class RegionRuleStore extends SavedData {
    public static final Factory<RegionRuleStore> FACTORY = new Factory<>(RegionRuleStore::new, RegionRuleStore::load);
    private static final int MAX_ENTRIES = 65_536;
    public record Condition(ResourceLocation kind, CompoundTag data, List<BlockPos> dependencies) {
        public Condition {
            Objects.requireNonNull(kind); Objects.requireNonNull(data);
            if (data.sizeInBytes() > 16_384 || dependencies.isEmpty() || dependencies.size() > 1024)
                throw new IllegalArgumentException("Invalid region condition");
            data = data.copy(); dependencies = dependencies.stream().map(BlockPos::immutable).distinct().toList();
        }
        @Override public CompoundTag data() { return data.copy(); }
    }
    /** Whole chunks, full build height. Bounds are inclusive, including negative chunk coordinates. */
    public record Entry(UUID id, Set<ResourceLocation> rules, int minX, int minZ, int maxX, int maxZ, Optional<Condition> condition) {
        public Entry {
            Objects.requireNonNull(id); rules = Set.copyOf(rules); Objects.requireNonNull(condition);
            if (rules.isEmpty() || rules.size() > 64) throw new IllegalArgumentException("Invalid region rules");
            long width = (long) maxX - minX + 1, depth = (long) maxZ - minZ + 1;
            if (width < 1 || depth < 1 || width > 256 || depth > 256 || width * depth > 256
                    || minX < -1_875_001 || maxX > 1_875_001 || minZ < -1_875_001 || maxZ > 1_875_001)
                throw new IllegalArgumentException("Invalid region bounds");
        }
    }
    private static final class Cached {
        final Entry entry;
        long revision;
        long dependencyVersion = Long.MIN_VALUE;
        boolean dirty = true, active, checking;
        Cached(Entry entry) { this.entry = entry; }
    }
    private final Map<UUID, Cached> entries = new LinkedHashMap<>();
    private final Map<Long, Set<UUID>> regions = new HashMap<>(), blocks = new HashMap<>(), chunks = new HashMap<>();
    private CompoundTag preserved;
    private long queries, candidates, validations, invalidations;
    public boolean supported() { return preserved == null; }
    public boolean contains(UUID id) { return supported() && entries.containsKey(id); }
    public boolean canInstall(UUID id) { return supported() && (entries.containsKey(id) || entries.size() < MAX_ENTRIES); }
    public Optional<Entry> entry(UUID id) { return Optional.ofNullable(entries.get(id)).map(c -> c.entry); }
    public long[] statistics() { return new long[]{entries.size(), regions.size(), blocks.size(), queries, candidates, validations, invalidations}; }

    /** Same receipt is idempotent; a reused receipt with different facts is an error, not an overwrite. */
    public void install(Entry entry) {
        if (!canInstall(entry.id)) throw new IllegalStateException("Region rule store unavailable");
        Cached old = entries.get(entry.id);
        if (old != null) {
            if (!old.entry.equals(entry)) throw new IllegalArgumentException("Conflicting region receipt");
            return;
        }
        index(entry); setDirty();
    }
    public void remove(UUID id) {
        Cached cached = entries.remove(id);
        if (cached == null || !supported()) return;
        visitRegion(cached.entry, key -> unwatch(regions, key, id));
        cached.entry.condition.ifPresent(c -> {
            for (BlockPos pos : c.dependencies) {
                unwatch(blocks, pos.asLong(), id); unwatch(chunks, ChunkPos.asLong(pos), id);
            }
        });
        setDirty();
    }
    public boolean applies(ResourceLocation rule, BlockPos pos, Predicate<Condition> validate) {
        return applies(rule, pos, validate, condition -> 0L);
    }
    public boolean applies(ResourceLocation rule, BlockPos pos, Predicate<Condition> validate,
                           java.util.function.ToLongFunction<Condition> version) {
        queries++;
        if (!supported()) return false;
        Set<UUID> bucket = regions.get(ChunkPos.asLong(pos));
        if (bucket == null) return false;
        for (UUID id : List.copyOf(bucket)) {
            Cached cached = entries.get(id); candidates++;
            if (cached == null) continue;
            if (!cached.entry.rules.contains(rule)) continue;
            if (cached.entry.condition.isEmpty()) return true;
            if (cached.checking) continue;
            Condition condition = cached.entry.condition.get();
            long dependencyVersion = version.applyAsLong(condition);
            if (cached.dirty || cached.dependencyVersion != dependencyVersion) {
                long revision = cached.revision;
                cached.checking = true;
                boolean result;
                try { validations++; result = validate.test(condition); }
                finally { cached.checking = false; }
                // A callback may invalidate dependencies. Never publish a stale authorization.
                if (entries.get(id) != cached || cached.revision != revision || version.applyAsLong(condition) != dependencyVersion) continue;
                cached.active = result; cached.dirty = false; cached.dependencyVersion = dependencyVersion;
            }
            if (cached.active) return true;
        }
        return false;
    }
    public void blockChanged(BlockPos pos) { invalidate(blocks.get(pos.asLong())); }
    public void chunkChanged(long chunk) { invalidate(chunks.get(chunk)); }
    public void invalidate(UUID id) { invalidate(Set.of(id)); }
    public void rulesChanged() { invalidate(entries.keySet()); }
    private void invalidate(Set<UUID> ids) {
        if (ids == null) return;
        for (UUID id : ids) {
            Cached c = entries.get(id);
            if (c != null && c.entry.condition.isPresent()) { c.revision++; c.dirty = true; invalidations++; }
        }
    }
    private void index(Entry entry) {
        entries.put(entry.id, new Cached(entry));
        visitRegion(entry, key -> watch(regions, key, entry.id));
        entry.condition.ifPresent(c -> {
            for (BlockPos pos : c.dependencies) {
                watch(blocks, pos.asLong(), entry.id); watch(chunks, ChunkPos.asLong(pos), entry.id);
            }
        });
    }
    private static void visitRegion(Entry e, java.util.function.LongConsumer action) {
        for (int x = e.minX; x <= e.maxX; x++) for (int z = e.minZ; z <= e.maxZ; z++) action.accept(ChunkPos.asLong(x, z));
    }
    private static void watch(Map<Long, Set<UUID>> map, long key, UUID id) { map.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(id); }
    private static void unwatch(Map<Long, Set<UUID>> map, long key, UUID id) {
        Set<UUID> bucket = map.get(key);
        if (bucket != null && bucket.remove(id) && bucket.isEmpty()) map.remove(key);
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (preserved != null) return preserved.copy();
        tag.putInt("Version", 1); ListTag list = new ListTag();
        for (Cached c : entries.values()) {
            Entry e = c.entry; CompoundTag row = new CompoundTag();
            row.putUUID("Id", e.id); ListTag rules = new ListTag(); e.rules.stream().sorted().forEach(r -> rules.add(StringTag.valueOf(r.toString())));
            row.put("Rules", rules);
            row.putIntArray("Bounds", new int[]{e.minX, e.minZ, e.maxX, e.maxZ});
            e.condition.ifPresent(condition -> {
                CompoundTag gate = new CompoundTag(); gate.putString("Kind", condition.kind.toString());
                gate.put("Data", condition.data());
                gate.putLongArray("Dependencies", condition.dependencies.stream().mapToLong(BlockPos::asLong).toArray());
                row.put("Condition", gate);
            });
            list.add(row);
        }
        tag.put("Entries", list); return tag;
    }
    private static RegionRuleStore load(CompoundTag tag, HolderLookup.Provider registries) {
        RegionRuleStore store = new RegionRuleStore();
        try {
            if (!tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 1
                    || !(tag.get("Entries") instanceof ListTag list) || list.size() > MAX_ENTRIES
                    || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)
                throw new IllegalArgumentException("Unsupported region envelope");
            for (Tag element : list) {
                CompoundTag row = (CompoundTag) element;
                int[] b = row.getIntArray("Bounds");
                if (!row.hasUUID("Id") || b.length != 4 || store.entries.containsKey(row.getUUID("Id")))
                    throw new IllegalArgumentException("Invalid region receipt");
                Optional<Condition> condition = Optional.empty();
                if (row.contains("Condition")) {
                    if (!row.contains("Condition", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Invalid condition");
                    CompoundTag gate = row.getCompound("Condition");
                    if (!gate.contains("Data", Tag.TAG_COMPOUND) || !gate.contains("Dependencies", Tag.TAG_LONG_ARRAY))
                        throw new IllegalArgumentException("Incomplete condition");
                    condition = Optional.of(new Condition(location(gate.getString("Kind")), gate.getCompound("Data"),
                            Arrays.stream(gate.getLongArray("Dependencies")).mapToObj(BlockPos::of).toList()));
                }
                if (!(row.get("Rules") instanceof ListTag rules) || rules.isEmpty() || rules.size() > 64 || rules.getElementType() != Tag.TAG_STRING)
                    throw new IllegalArgumentException("Invalid rule list");
                Set<ResourceLocation> keys = new LinkedHashSet<>();
                for (Tag rule : rules) keys.add(location(rule.getAsString()));
                store.index(new Entry(row.getUUID("Id"), keys, b[0], b[1], b[2], b[3], condition));
            }
        } catch (IllegalArgumentException failure) {
            store = new RegionRuleStore(); store.preserved = tag.copy();
            org.slf4j.LoggerFactory.getLogger(RegionRuleStore.class).error("Region rules retained unchanged; disabled: {}", failure.getMessage());
        }
        return store;
    }
    private static ResourceLocation location(String id) {
        if (id.length() > 256) throw new IllegalArgumentException("Oversized region key");
        ResourceLocation value = ResourceLocation.tryParse(id);
        if (value == null) throw new IllegalArgumentException("Invalid region key");
        return value;
    }
}
