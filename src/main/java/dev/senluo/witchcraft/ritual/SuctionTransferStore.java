package dev.senluo.witchcraft.ritual;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Shared extracted-matter journal. Consumers have separate scopes; only confirmed matter may retry. */
public final class SuctionTransferStore extends SavedData {
    private static final Factory<SuctionTransferStore> FACTORY = new Factory<>(SuctionTransferStore::new, SuctionTransferStore::load);
    public enum Scope { RITUAL, CONDUIT }
    public record Outlet(ResourceLocation dimension, BlockPos position, Scope scope) {
        public Outlet(ResourceLocation dimension, BlockPos position) { this(dimension, position, Scope.RITUAL); }
        public Outlet { java.util.Objects.requireNonNull(dimension); java.util.Objects.requireNonNull(scope); position = position.immutable(); }
    }
    /** A null face explicitly means owned conduit-buffer matter, not extraction from a boundary. */
    public record Receipt(Outlet outlet, @javax.annotation.Nullable Direction face, ResourceLocation aspect, int amount, boolean uncertain) {
        public Receipt {
            java.util.Objects.requireNonNull(outlet); java.util.Objects.requireNonNull(aspect);
            if (face == null && outlet.scope != Scope.CONDUIT) throw new IllegalArgumentException("Only conduit buffers have no source face");
            if (amount <= 0) throw new IllegalArgumentException("Invalid in-flight amount");
        }
    }
    private final Map<Outlet, Receipt> pending = new LinkedHashMap<>();
    private record Group(ResourceLocation dimension, Scope scope) {}
    // Derived only; keep quarantined receipts authoritative, without scanning them in unrelated worlds.
    private final Map<Group, LinkedHashMap<Outlet, Receipt>> groups = new java.util.HashMap<>();
    private CompoundTag preserved;
    public static SuctionTransferStore get(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Transfers belong to the server thread");
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, "witchcraft_suction_transfers");
    }
    public boolean supported() { return preserved == null; }
    public boolean holds(Outlet outlet) { return pending.containsKey(outlet); }
    public Receipt receipt(Outlet outlet) { return pending.get(outlet); }
    /** Empty scopes can skip buffer/receipt walks; no receipt or quarantine is discarded. */
    public boolean hasInDimension(ResourceLocation dimension, Scope scope) {
        return supported() && groups.containsKey(new Group(dimension, scope));
    }
    public List<Receipt> inDimension(ResourceLocation dimension) {
        return inDimension(dimension, Scope.RITUAL);
    }
    public List<Receipt> inDimension(ResourceLocation dimension, Scope scope) {
        var entries = groups.get(new Group(dimension, scope));
        return !supported() || entries == null ? List.of() : List.copyOf(entries.values());
    }
    private void put(Receipt receipt) {
        pending.put(receipt.outlet, receipt);
        groups.computeIfAbsent(new Group(receipt.outlet.dimension, receipt.outlet.scope), key -> new LinkedHashMap<>())
                .put(receipt.outlet, receipt);
    }
    private void remove(Outlet outlet) {
        pending.remove(outlet);
        Group key = new Group(outlet.dimension, outlet.scope);
        var entries = groups.get(key);
        if (entries != null) { entries.remove(outlet); if (entries.isEmpty()) groups.remove(key); }
    }
    public Receipt record(Outlet outlet, Direction face, ResourceLocation aspect, int amount) {
        java.util.Objects.requireNonNull(face);
        return record(outlet, face, aspect, amount, false);
    }
    public Receipt recordBuffer(Outlet outlet, ResourceLocation aspect, int amount) {
        return record(outlet, null, aspect, amount, false);
    }
    /** Before foreign extraction: the amount is a request, not owned matter until confirmed. */
    public Receipt reserve(Outlet outlet, Direction face, ResourceLocation aspect, int amount) {
        java.util.Objects.requireNonNull(face);
        return record(outlet, face, aspect, amount, true);
    }
    private Receipt record(Outlet outlet, Direction face, ResourceLocation aspect, int amount, boolean uncertain) {
        if (!supported() || pending.containsKey(outlet)) throw new IllegalStateException("Outlet already has an unsettled transfer");
        Receipt receipt = new Receipt(outlet, face, aspect, amount, uncertain);
        put(receipt); setDirty(); return receipt;
    }
    /** Only a successful extraction return value converts a reservation into known matter. */
    public Receipt extracted(Receipt expected, int amount) {
        if (!supported() || !expected.uncertain || !expected.equals(pending.get(expected.outlet))
                || amount < 0 || amount > expected.amount) throw new IllegalStateException("Invalid extraction confirmation");
        if (amount == 0) remove(expected.outlet);
        else put(new Receipt(expected.outlet, expected.face, expected.aspect, amount, false));
        setDirty(); return pending.get(expected.outlet);
    }
    public void accepted(Receipt expected, int amount) {
        if (!supported() || !expected.equals(pending.get(expected.outlet)) || amount < 0 || amount > expected.amount)
            throw new IllegalStateException("Stale transfer settlement");
        if (amount == 0) return;
        if (amount == expected.amount) remove(expected.outlet);
        else put(new Receipt(expected.outlet, expected.face, expected.aspect, expected.amount - amount, false));
        setDirty();
    }
    /** Extraction is confirmed and the receiver is confirmed unchanged: the matter can be retried safely. */
    public void retryable(Receipt expected) {
        if (!supported() || !expected.equals(pending.get(expected.outlet))) throw new IllegalStateException("Stale transfer settlement");
        put(new Receipt(expected.outlet, expected.face, expected.aspect, expected.amount, false)); setDirty();
    }
    /** A foreign EXECUTE exception may have mutated its inventory: retain evidence and never replay blindly. */
    public Receipt quarantine(Receipt expected) {
        if (!expected.equals(pending.get(expected.outlet))) throw new IllegalStateException("Stale uncertain transfer");
        Receipt uncertain = new Receipt(expected.outlet, expected.face, expected.aspect, expected.amount, true);
        put(uncertain); setDirty(); return uncertain;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (preserved != null) return preserved.copy();
        tag.putInt("Version", 3); ListTag entries = new ListTag();
        for (Receipt r : pending.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Dimension", r.outlet.dimension.toString()); entry.putLong("Outlet", r.outlet.position.asLong());
            entry.putString("Scope", r.outlet.scope.name());
            entry.putBoolean("Buffer", r.face == null);
            if (r.face != null) entry.putString("Face", r.face.getName());
            entry.putString("Aspect", r.aspect.toString());
            entry.putInt("Amount", r.amount); entry.putBoolean("Uncertain", r.uncertain); entries.add(entry);
        }
        tag.put("Pending", entries); return tag;
    }
    private static SuctionTransferStore load(CompoundTag tag, HolderLookup.Provider registries) {
        SuctionTransferStore store = new SuctionTransferStore();
        try {
            int version = tag.getInt("Version");
            if (version != 3 || !(tag.get("Pending") instanceof ListTag entries)
                    || !entries.isEmpty() && entries.getElementType() != Tag.TAG_COMPOUND)
                throw new IllegalArgumentException("Unsupported in-flight transfer envelope");
            for (int i = 0; i < entries.size(); i++) {
                var entry = entries.getCompound(i);
                ResourceLocation dimension = ResourceLocation.tryParse(entry.getString("Dimension")), aspect = ResourceLocation.tryParse(entry.getString("Aspect"));
                Direction face = Direction.byName(entry.getString("Face"));
                boolean buffer = entry.getBoolean("Buffer");
                if (dimension == null || aspect == null || !buffer && face == null || buffer && entry.contains("Face")
                        || !entry.contains("Buffer", Tag.TAG_BYTE) || !entry.contains("Outlet", Tag.TAG_LONG)
                        || !entry.contains("Amount", Tag.TAG_INT) || !entry.contains("Uncertain", Tag.TAG_BYTE))
                    throw new IllegalArgumentException("Incomplete transfer receipt");
                Scope scope = Scope.valueOf(entry.getString("Scope"));
                Receipt receipt = new Receipt(new Outlet(dimension, BlockPos.of(entry.getLong("Outlet")), scope), face, aspect,
                        entry.getInt("Amount"), entry.getBoolean("Uncertain"));
                if (store.pending.containsKey(receipt.outlet)) throw new IllegalArgumentException("Duplicate in-flight outlet");
                store.put(receipt);
            }
            return store;
        } catch (IllegalArgumentException invalidData) {
            store = new SuctionTransferStore(); store.preserved = tag.copy();
            org.slf4j.LoggerFactory.getLogger(SuctionTransferStore.class).error("Suction receipts retained; transfers disabled: {}", invalidData.getMessage());
            return store;
        }
    }
}
