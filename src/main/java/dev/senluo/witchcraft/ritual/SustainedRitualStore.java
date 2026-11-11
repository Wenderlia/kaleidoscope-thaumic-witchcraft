package dev.senluo.witchcraft.ritual;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Dimension-owned active rituals. Runtime suppliers avoid rebuilding NBT on every game tick. */
public final class SustainedRitualStore extends SavedData {
    public static final String NAME = "witchcraft_sustained_rituals";
    private static final Factory<SustainedRitualStore> FACTORY =
            new Factory<>(SustainedRitualStore::new, SustainedRitualStore::load);
    private static final class Entry {
        final BlockPos core;
        final UUID identity;
        CompoundTag snapshot;
        Supplier<CompoundTag> live;
        Entry(CompoundTag tag) {
            core = BlockPos.of(tag.getLong("Core")); identity = tag.getUUID("Identity");
            snapshot = tag.copy();
        }
        CompoundTag snapshot() { return live == null ? snapshot.copy() : live.get(); }
    }
    private final Map<UUID, Entry> entries = new HashMap<>();
    private CompoundTag preserved;

    public static SustainedRitualStore get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }
    public boolean supported() { return preserved == null; }
    public boolean contains(UUID subject) { return supported() && entries.containsKey(subject); }
    public CompoundTag snapshot(UUID subject) {
        Entry entry = entries.get(subject);
        return !supported() || entry == null ? null : entry.snapshot();
    }
    public void bind(UUID subject, Supplier<CompoundTag> writer) {
        if (!supported()) return;
        Entry entry = new Entry(writer.get());
        entry.live = writer; entries.put(subject, entry); setDirty();
    }
    public void suspend(UUID subject) {
        Entry entry = entries.get(subject);
        if (entry == null || entry.live == null) return;
        entry.snapshot = entry.live.get(); entry.live = null; setDirty();
    }
    public void remove(UUID subject) {
        if (supported() && entries.remove(subject) != null) setDirty();
    }
    public List<UUID> at(BlockPos core, UUID identity) {
        return entries.entrySet().stream().filter(e -> e.getValue().core.equals(core)
                && e.getValue().identity.equals(identity)).map(Map.Entry::getKey).toList();
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (preserved != null) return preserved.copy();
        tag.putInt("Version", 1);
        ListTag list = new ListTag();
        entries.values().forEach(entry -> list.add(entry.snapshot()));
        tag.put("Sessions", list);
        return tag;
    }
    public static SustainedRitualStore load(CompoundTag tag, HolderLookup.Provider registries) {
        SustainedRitualStore result = new SustainedRitualStore();
        if (tag.getInt("Version") != 1 || !(tag.get("Sessions") instanceof ListTag list)
                || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND || list.size() > 65_536)
            return preserve(tag);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!SustainedRitual.validSnapshot(entry)
                    || result.entries.putIfAbsent(entry.getUUID("Subject"), new Entry(entry)) != null)
                return preserve(tag);
        }
        return result;
    }
    private static SustainedRitualStore preserve(CompoundTag tag) {
        SustainedRitualStore result = new SustainedRitualStore();
        result.preserved = tag.copy();
        org.slf4j.LoggerFactory.getLogger(SustainedRitualStore.class)
                .error("Unsupported sustained ritual data retained unchanged; automatic restoration disabled");
        return result;
    }
}
