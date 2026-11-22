package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.worldgen.LocalGeneration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/** Native-load working copies. Terrain and entity retirement jointly guard address reuse. */
public final class MineWorkAreas implements LocalGeneration.ResidentData {
    public interface EntityWorld { ServerLevel witchcraft$entityWorld(); }
    private static final Map<ServerLevel, MineWorkAreas> LOADED = new ConcurrentHashMap<>();
    private final ServerLevel level;
    private final Map<LocalGeneration.Context, Area> areas = new HashMap<>();
    private final Map<Long, Copy> byChunk = new ConcurrentHashMap<>();
    private MineWorkAreas(ServerLevel level) { this.level = level; }
    static MineWorkAreas get(ServerLevel level) {
        return LocalGeneration.data(level, MineWorkAreas.class, () -> {
            var owner = new MineWorkAreas(level); LOADED.put(level, owner); return owner;
        });
    }
    static final class Area {
        final MineWorkAreas owner;
        final LocalGeneration.Context context;
        final BlockPos origin;
        final Runnable changed;
        final MineReadQueue.Owner reads;
        Copy copy;
        boolean closing;
        Area(MineWorkAreas owner, long seed, Runnable changed, MineReadQueue.Owner reads) {
            this.owner = owner; context = LocalGeneration.open(owner.level, seed); origin = context.origin(0); this.changed = changed; this.reads = reads;
        }
        boolean idle() { return !closing && copy == null && !context.resident(); }
        @Nullable Copy restore(MineSample sample, MineSamplePool memory) {
            if (!idle() || !memory.beginOperation()) return null;
            if (!memory.reserve(sample.bytes())) { memory.endOperation(); return null; }
            copy = new Copy(this, sample.retain(), memory, sample.bytes());
            for (long offset : sample.chunks()) owner.byChunk.put(copy.destination(offset), copy);
            changed.run(); return copy;
        }
        // Stop admitting replacements. The copy's members/receipts still decide when it may retire.
        void close() { closing = true; if (copy == null) discard(); }
        private void discard() { owner.areas.remove(context); context.close(); }
    }
    Area area(long seed, Runnable changed, MineReadQueue.Owner reads) {
        Area area = new Area(this, seed, changed, reads); areas.put(area.context, area); return area;
    }
    static final class Copy {
        final Area area;
        final MineSample sample;
        final MineSamplePool memory;
        final long restoreBytes, installedTime;
        final Set<Long> entityRequests = new HashSet<>();
        boolean retiring, failed, disposed, preparing = true;
        Copy(Area area, MineSample sample, MineSamplePool memory, long restoreBytes) {
            this.area = area; this.sample = sample; this.memory = memory; this.restoreBytes = restoreBytes;
            installedTime = area.owner.level.getGameTime();
        }
        BlockPos origin() { return area.origin; }
        ServerLevel level() { return area.owner.level; }
        long destination(long offset) {
            return ChunkPos.asLong((origin().getX() >> 4) + ChunkPos.getX(offset), (origin().getZ() >> 4) + ChunkPos.getZ(offset));
        }
        long relative(long key) {
            return ChunkPos.asLong(ChunkPos.getX(key) - (origin().getX() >> 4), ChunkPos.getZ(key) - (origin().getZ() >> 4));
        }
        void needs(Map<Long, MineReadQueue.Need> needs) {
            if (!retiring) for (long offset : sample.description.workChunks())
                needs.put(destination(offset), new MineReadQueue.Need(true, 0));
        }
        boolean ready() { return !retiring && !failed && !preparing; }
        void ticketsChanged() {
            if (retiring) { finish(); return; }
            if (!preparing || failed) return;
            for (long offset : sample.description.workChunks()) if (!area.reads.prepared(destination(offset), true)) return;
            preparing = false; memory.free(restoreBytes); memory.endOperation();
        }
        void retire() {
            if (retiring) return;
            retiring = true; area.changed.run(); finish();
        }
        void finish() {
            if (disposed || !retiring || area.context.resident() || !entityRequests.isEmpty()) return;
            for (long offset : sample.chunks()) if (area.reads.leased(destination(offset))) return;
            dispose();
        }
        private void dispose() {
            if (disposed) return;
            disposed = true;
            for (long offset : sample.chunks()) area.owner.byChunk.remove(destination(offset), this);
            if (preparing) { preparing = false; memory.free(restoreBytes); memory.endOperation(); }
            sample.release(); area.copy = null;
            if (area.closing) area.discard(); else area.changed.run();
        }
    }
    @Nullable private static Copy copy(ServerLevel level, long key) {
        var owner = LOADED.get(level); return owner == null ? null : owner.byChunk.get(key);
    }
    public static boolean contains(ServerLevel level, ChunkPos pos) { return copy(level, pos.toLong()) != null; }
    @Nullable public static CompoundTag terrain(ServerLevel level, ChunkPos pos) {
        Copy copy = copy(level, pos.toLong());
        return copy == null ? null : copy.sample.terrain(copy.relative(pos.toLong()), copy.origin(), copy.installedTime);
    }
    @Nullable public static Map<Structure, StructureStart> starts(ServerLevel level, ChunkPos pos) {
        Copy copy = copy(level, pos.toLong());
        return copy == null ? null : copy.sample.starts(level, copy.relative(pos.toLong()), copy.origin());
    }
    @Nullable public static ChunkEntities<Entity> entities(ServerLevel level, ChunkPos pos) {
        Copy copy = copy(level, pos.toLong()); if (copy == null) return null;
        copy.entityRequests.add(pos.toLong());
        return new ChunkEntities<>(pos, EntityType.loadEntitiesRecursive(copy.sample.entities(copy.relative(pos.toLong()), copy.origin()), level).toList());
    }
    public static void entityRetired(ServerLevel level, long key) {
        Copy copy = copy(level, key);
        if (copy != null) { copy.entityRequests.remove(key); copy.finish(); }
    }
    public static void failed(ServerLevel level, ChunkPos pos) {
        Copy copy = copy(level, pos.toLong()); if (copy != null) copy.failed = true;
    }
    @Override public void chunkRetired(long chunk) {
        var context = LocalGeneration.at(level, new ChunkPos(chunk));
        Area area = areas.get(context); if (area != null && area.copy != null) area.copy.finish();
    }
    @Override public void close() {
        LOADED.remove(level, this);
        for (Area area : List.copyOf(areas.values())) {
            area.closing = true;
            if (area.copy != null) area.copy.dispose(); else area.discard();
        }
        areas.clear(); byChunk.clear();
    }
}
