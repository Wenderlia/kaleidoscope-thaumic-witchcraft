package dev.senluo.witchcraft.ritual;

import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** World-owned UUID ledger: an offline subject is an identity, never a player entity to load. */
final class GeasLedger extends SavedData {
    private static final Factory<GeasLedger> FACTORY = new Factory<>(GeasLedger::new, GeasLedger::load);
    private final Map<UUID, Set<ResourceLocation>> entries = new HashMap<>();
    private CompoundTag preserved;
    private record TransitionKey(UUID subject, BindingPair pair) {}
    private final Map<TransitionKey, Transition> committing = new HashMap<>();

    static GeasLedger get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "witchcraft_geas_bindings");
    }
    boolean supported() { return preserved == null; }
    boolean has(UUID player, ResourceLocation binding) { return entries.getOrDefault(player, Set.of()).contains(binding); }
    boolean grant(UUID player, ResourceLocation binding) {
        if (!supported()) throw new IllegalStateException("Unsupported geas ledger must not be overwritten");
        if (!entries.computeIfAbsent(player, unused -> new HashSet<>()).add(binding)) return false;
        setDirty();
        return true;
    }
    BindingPair.State state(UUID player, BindingPair pair) { return pair.state(has(player, pair.positive()), has(player, pair.negative())); }
    BindingPair.Change apply(UUID player, BindingPair pair, boolean inverse, BindingPair.State expected) {
        if (committing.containsKey(new TransitionKey(player, pair))) return BindingPair.Change.REJECTED;
        return applyUnreserved(player, pair, inverse, expected);
    }
    private BindingPair.Change applyUnreserved(UUID player, BindingPair pair, boolean inverse, BindingPair.State expected) {
        if (!supported()) return BindingPair.Change.REJECTED;
        BindingPair.State before = state(player, pair);
        if (before != expected || before == BindingPair.State.CONFLICT) return BindingPair.Change.REJECTED;
        ResourceLocation desired = pair.id(inverse), opposite = pair.id(!inverse);
        if (has(player, desired)) return BindingPair.Change.UNCHANGED;
        // One operation can only REMOVE the opposite or GRANT the requested side, never both.
        if (has(player, opposite)) {
            entries.get(player).remove(opposite);
            setDirty(); return BindingPair.Change.RELEASED;
        }
        grant(player, desired);
        setDirty(); return BindingPair.Change.GRANTED;
    }
    Transition reserve(UUID subject, BindingPair pair, boolean inverse, BindingPair.State expected) {
        TransitionKey key = new TransitionKey(subject, pair);
        if (!supported() || committing.containsKey(key) || state(subject, pair) != expected
                || !new GeasRites.Rite(pair, inverse).accepts(expected)) return null;
        Transition result = new Transition(key, inverse, expected);
        committing.put(key, result);
        return result;
    }
    final class Transition implements AutoCloseable {
        private final TransitionKey key;
        private final boolean inverse;
        private final BindingPair.State expected;
        private boolean closed;
        Transition(TransitionKey key, boolean inverse, BindingPair.State expected) {
            this.key = key; this.inverse = inverse; this.expected = expected;
        }
        BindingPair.Change commit() {
            if (closed || committing.get(key) != this) return BindingPair.Change.REJECTED;
            close();
            return applyUnreserved(key.subject(), key.pair(), inverse, expected);
        }
        @Override public void close() {
            if (!closed) { committing.remove(key, this); closed = true; }
        }
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (preserved != null) return preserved.copy();
        tag.putInt("Version", 2);
        ListTag players = new ListTag();
        var subjects = new TreeSet<UUID>(entries.keySet());
        subjects.forEach(subject -> {
            CompoundTag row = new CompoundTag();
            row.putUUID("Player", subject);
            ListTag bindings = new ListTag();
            entries.getOrDefault(subject, Set.of()).stream().sorted().forEach(id -> bindings.add(StringTag.valueOf(id.toString())));
            row.put("Bindings", bindings); players.add(row);
        });
        tag.put("Players", players);
        return tag;
    }
    private static GeasLedger load(CompoundTag tag, HolderLookup.Provider registries) {
        GeasLedger result = new GeasLedger();
        int version = tag.getInt("Version");
        boolean valid = version == 2 && tag.get("Players") instanceof ListTag;
        if (valid) for (Tag raw : (ListTag) tag.get("Players")) {
            if (!(raw instanceof CompoundTag row) || !row.hasUUID("Player")
                    || !(row.get("Bindings") instanceof ListTag bindings)) { valid = false; break; }
            Set<ResourceLocation> ids = new HashSet<>();
            for (Tag value : bindings) {
                if (!(value instanceof StringTag)) { valid = false; break; }
                ResourceLocation id = ResourceLocation.tryParse(value.getAsString());
                if (id == null || !ids.add(id)) { valid = false; break; }
            }
            if (!valid || result.entries.putIfAbsent(row.getUUID("Player"), ids) != null) { valid = false; break; }
        }
        if (!valid) {
            result.entries.clear(); result.preserved = tag.copy();
            org.slf4j.LoggerFactory.getLogger(GeasLedger.class).error("Unsupported geas ledger retained; new grants disabled");
        }
        return result;
    }
}
