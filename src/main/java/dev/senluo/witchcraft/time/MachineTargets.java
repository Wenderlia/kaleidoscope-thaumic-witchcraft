package dev.senluo.witchcraft.time;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

/** One loaded-only contact index per colony; roots do not individually scan for machines. */
public final class MachineTargets {
    private final ServerLevel level;
    private record Contacts(List<BlockPos> ordered, Set<BlockPos> positions, Set<Long> chunks) {}
    private final Map<Colony, Contacts> targets = new IdentityHashMap<>();
    private final Map<Long, Set<Colony>> watchers = new HashMap<>();
    public MachineTargets(ServerLevel level) { this.level = level; }
    public void clear() { targets.clear(); watchers.clear(); }
    void forget(Colony group) {
        Contacts old = targets.remove(group);
        if (old == null) return;
        for (long key : old.chunks) {
            Set<Colony> bucket = watchers.get(key);
            if (bucket != null && bucket.remove(group) && bucket.isEmpty()) watchers.remove(key);
        }
    }
    public void blockChanged(BlockPos pos, BlockState before, BlockState after) {
        if (before.getBlock() != after.getBlock() && (before.hasBlockEntity() || after.hasBlockEntity())) chunkChanged(ChunkPos.asLong(pos));
    }
    public void chunkChanged(long chunk) {
        Set<Colony> affected = watchers.get(chunk);
        if (affected == null) return;
        for (Colony group : List.copyOf(affected)) forget(group);
    }
    public List<BlockPos> get(Colony colony) {
        return contacts(colony).ordered;
    }
    private Contacts contacts(Colony colony) {
        return targets.computeIfAbsent(colony, group -> {
            Set<BlockPos> contacts = new HashSet<>();
            Set<Long> chunks = new HashSet<>();
            for (BlockPos connector : group.roots) collect(connector, contacts, chunks);
            for (BlockPos connector : group.logs) collect(connector, contacts, chunks);
            for (long key : chunks) watchers.computeIfAbsent(key, unused -> Collections.newSetFromMap(new IdentityHashMap<>())).add(group);
            return new Contacts(contacts.stream().sorted(Comparator.comparingLong(BlockPos::asLong)).toList(), Set.copyOf(contacts), Set.copyOf(chunks));
        });
    }
    private void collect(BlockPos connector, Set<BlockPos> contacts, Set<Long> chunks) {
        chunks.add(ChunkPos.asLong(connector));
        if (!level.hasChunkAt(connector) || !level.shouldTickBlocksAt(connector)) return;
        for (Direction face : Direction.values()) {
            BlockPos pos = connector.relative(face);
            chunks.add(ChunkPos.asLong(pos));
            if (level.hasChunkAt(pos) && level.shouldTickBlocksAt(pos) && level.getBlockState(pos).hasBlockEntity())
                contacts.add(pos.immutable());
        }
    }
    public boolean supplies(Colony group, BlockPos pos) {
        return group.productionMode() != SupplyMode.PAUSED
                && group.members.stream().anyMatch(e -> e.canDistribute() && !e.amber()) && contacts(group).positions.contains(pos);
    }
}
