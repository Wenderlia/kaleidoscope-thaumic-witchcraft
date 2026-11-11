package dev.senluo.witchcraft.time;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

final class HostRestores extends SavedData {
    private final Map<BlockPos, BlockState> pending = new LinkedHashMap<>();
    static HostRestores get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(HostRestores::new, HostRestores::load), "witchcraft_pending_hosts");
    }
    void put(BlockPos pos, BlockState state) { pending.put(pos, state); setDirty(); }
    void flush(ServerLevel level) {
        if (pending.isEmpty()) return;
        for (var entry : Map.copyOf(pending).entrySet()) {
            var pos = entry.getKey();
            if (!level.hasChunkAt(pos)) continue;
            if (level.getBlockState(pos).isAir() && !level.setBlock(pos, entry.getValue(), 3) && !entry.getValue().isAir()) continue;
            if (pending.remove(pos, entry.getValue())) setDirty();
        }
    }
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag entries = new ListTag();
        pending.forEach((pos, state) -> {
            CompoundTag record = new CompoundTag();
            record.putLong("Pos", pos.asLong()); record.put("State", NbtUtils.writeBlockState(state)); entries.add(record);
        });
        tag.put("Hosts", entries); return tag;
    }
    private static HostRestores load(CompoundTag tag, HolderLookup.Provider registries) {
        HostRestores result = new HostRestores();
        for (Tag raw : tag.getList("Hosts", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag)raw;
            result.pending.put(BlockPos.of(entry.getLong("Pos")), NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK), entry.getCompound("State")));
        }
        return result;
    }
}
