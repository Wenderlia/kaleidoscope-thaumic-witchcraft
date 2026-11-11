package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.ritual.StructuralRitualStore;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.saveddata.SavedData;

/** Ritual-owned mining data. The source level persists it even while the center chunk is unloaded. */
final class DeepMineStore extends SavedData {
    private static final Factory<DeepMineStore> FACTORY = new Factory<>(DeepMineStore::new, DeepMineStore::load);
    private static final Map<ServerLevel, DeepMineStore> LOADED = new WeakHashMap<>();
    private final Map<UUID, Mine> mines = new HashMap<>();
    private boolean initialized;
    private CompoundTag preserved;

    static DeepMineStore get(ServerLevel level) {
        return LOADED.computeIfAbsent(level, current -> {
            var store = current.getDataStorage().computeIfAbsent(FACTORY, "witchcraft_ritual_mines");
            if (!store.initialized) {
                // Only receipts already present at first upgrade may adopt the old machine ledger.
                for (var handle : StructuralRitualStore.get(current).inDimension(current.dimension().location()))
                    if (handle.definition().kind().equals(DeepDrilling.ID))
                        store.mines.put(handle.id(), store.new Mine(handle.id(), true));
                store.initialized = true;
                store.setDirty();
            }
            return store;
        });
    }
    static void unload(ServerLevel level) {
        var store = LOADED.remove(level);
        if (store != null) store.mines.values().forEach(Mine::pause);
    }
    Mine ensure(UUID id) {
        if (preserved != null) return null;
        return mines.computeIfAbsent(id, key -> { setDirty(); return new Mine(key, false); });
    }
    Mine find(UUID id) { return mines.get(id); }
    void pause(UUID id) {
        var mine = mines.get(id);
        if (mine != null) mine.pause();
    }
    void remove(UUID id) {
        var mine = mines.remove(id);
        if (mine != null) { mine.pause(); setDirty(); }
    }

    final class Mine {
        final MixedMine samples;
        private final Map<BlockPos, BoreExcavation> work = new HashMap<>();
        private final Map<BlockEntity, BoreExcavation> bindings = new WeakHashMap<>();
        private boolean legacy;
        Mine(UUID id, boolean legacy) {
            this.legacy = legacy;
            samples = new MixedMine(id, DeepMineStore.this::setDirty);
        }
        BoreExcavation bind(BlockEntity bore) {
            BlockPos pos = bore.getBlockPos();
            var excavation = work.computeIfAbsent(pos.immutable(), key -> {
                setDirty(); return new BoreExcavation(DeepMineStore.this::setDirty);
            });
            boolean first = bindings.put(bore, excavation) == null;
            if (legacy || first) {
                var old = BoreStorage.get(bore);
                if (legacy && old.legacyMine != null) samples.deserializeNBT(bore.getLevel().registryAccess(), old.legacyMine);
                if (legacy && old.legacyWork != null) excavation.deserializeNBT(bore.getLevel().registryAccess(), old.legacyWork);
                old.legacyMine = old.legacyWork = null;
                legacy = false; bore.setChanged(); setDirty();
            }
            return excavation;
        }
        void detach(BlockEntity bore) {
            var excavation = bindings.remove(bore);
            if (excavation != null) excavation.cancel(bore);
        }
        void pause() {
            bindings.forEach((bore, excavation) -> excavation.cancel(bore));
            bindings.clear();
            samples.release();
        }
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        if (preserved != null) return preserved.copy();
        tag.putInt("Version", 1);
        ListTag entries = new ListTag();
        mines.forEach((id, mine) -> {
            CompoundTag row = new CompoundTag();
            row.putUUID("Ritual", id); row.putBoolean("Legacy", mine.legacy);
            row.put("Mine", mine.samples.serializeNBT(lookup));
            ListTag workers = new ListTag();
            mine.work.forEach((pos, work) -> {
                CompoundTag worker = new CompoundTag();
                worker.putLong("Pos", pos.asLong()); worker.put("Work", work.serializeNBT(lookup));
                workers.add(worker);
            });
            row.put("Workers", workers); entries.add(row);
        });
        tag.put("Mines", entries);
        return tag;
    }
    private static DeepMineStore load(CompoundTag tag, HolderLookup.Provider lookup) {
        var store = new DeepMineStore();
        store.initialized = true;
        if (tag.getInt("Version") != 1) { store.preserved = tag.copy(); return store; }
        for (Tag raw : tag.getList("Mines", Tag.TAG_COMPOUND)) {
            var row = (CompoundTag) raw;
            UUID id = row.getUUID("Ritual");
            var mine = store.new Mine(id, row.getBoolean("Legacy"));
            mine.samples.deserializeNBT(lookup, row.getCompound("Mine"));
            for (Tag entry : row.getList("Workers", Tag.TAG_COMPOUND)) {
                var worker = (CompoundTag) entry;
                var work = new BoreExcavation(store::setDirty);
                work.deserializeNBT(lookup, worker.getCompound("Work"));
                mine.work.put(BlockPos.of(worker.getLong("Pos")), work);
            }
            store.mines.put(id, mine);
        }
        return store;
    }
}
