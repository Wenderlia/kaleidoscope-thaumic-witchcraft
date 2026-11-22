package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.worldgen.LocalGeneration;
import java.util.*;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkDataEvent;

/** Detached, read-only native content. Working copies, cursors and pool slots do not own this data. */
final class MineSample {
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    static final int FORMAT = 2;
    private static volatile Capture activeCapture;
    private static final class Capture {
        final ServerLevel level;
        final Set<Long> chunks;
        volatile boolean changed;
        Capture(ServerLevel level, Set<Long> chunks) { this.level = level; this.chunks = chunks; }
    }
    static void changed(ServerLevel level, BlockPos pos) {
        Capture capture = activeCapture;
        if (capture != null && capture.level == level && capture.chunks.contains(ChunkPos.asLong(pos))) capture.changed = true;
    }
    record Description(UUID id, ResourceLocation source, int originX, int originZ, int top, int floor,
                       int minY, int height, int radius, Set<Long> targets, Set<Long> workChunks) {
        boolean accepts(int dx, int dz) {
            if ((long) dx * dx + (long) dz * dz > (long) radius * radius) return false;
            return targets.contains(ChunkPos.asLong((8 + dx) >> 4, (8 + dz) >> 4));
        }
        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Id", id); tag.putString("Source", source.toString());
            tag.putInt("X", originX); tag.putInt("Z", originZ); tag.putInt("Top", top); tag.putInt("Floor", floor);
            tag.putInt("MinY", minY); tag.putInt("Height", height); tag.putInt("Radius", radius);
            tag.putLongArray("Targets", targets.stream().mapToLong(Long::longValue).toArray());
            tag.putLongArray("WorkChunks", workChunks.stream().mapToLong(Long::longValue).toArray());
            return tag;
        }
        static Description load(CompoundTag tag) {
            return new Description(tag.getUUID("Id"), ResourceLocation.parse(tag.getString("Source")),
                    tag.getInt("X"), tag.getInt("Z"), tag.getInt("Top"), tag.getInt("Floor"), tag.getInt("MinY"),
                    tag.getInt("Height"), tag.getInt("Radius"), longs(tag.getLongArray("Targets")), longs(tag.getLongArray("WorkChunks")));
        }
        private static Set<Long> longs(long[] values) {
            Set<Long> set = new LinkedHashSet<>(); for (long value : values) set.add(value); return Set.copyOf(set);
        }
    }

    final Description description;
    final long seed;
    private final Map<Long, CompoundTag> terrain;
    private final Map<Long, ListTag> entities;
    private final Map<Long, int[]> columns = new HashMap<>();
    private final MineSamplePool memory;
    private long bytes;
    private int references = 1;
    private Boolean compatible;

    private MineSample(MineSamplePool memory, Description description, long seed,
                       Map<Long, CompoundTag> terrain, Map<Long, ListTag> entities, long bytes) {
        this.memory = memory; this.description = description; this.seed = seed;
        this.terrain = Collections.unmodifiableMap(terrain); this.entities = entities; this.bytes = bytes;
    }
    MineSample retain() { if (references <= 0) throw new IllegalStateException("Retired mine sample"); references++; return this; }
    void release() { if (--references == 0) { memory.free(bytes); columns.clear(); } }
    int references() { return references; }
    long bytes() { return bytes; }
    Set<Long> chunks() { return terrain.keySet(); }

    /** Persist the host's loading closure, without promoting support chunks to FULL work. */
    static Map<Long, ChunkStatus> loadingContext(Set<Long> workChunks) {
        var requirements = ChunkPyramid.LOADING_PYRAMID.getStepTo(ChunkStatus.FULL).accumulatedDependencies();
        Map<Long, ChunkStatus> context = new LinkedHashMap<>();
        for (long key : workChunks) context.put(key, ChunkStatus.FULL);
        int radius = requirements.getRadius();
        for (long key : workChunks) for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            long neighbor = ChunkPos.asLong(ChunkPos.getX(key) + dx, ChunkPos.getZ(key) + dz);
            ChunkStatus needed = requirements.get(Math.max(Math.abs(dx), Math.abs(dz)));
            context.merge(neighbor, needed, (old, next) -> old.isAfter(next) ? old : next);
        }
        return context;
    }

    record Snapshot(@Nullable MineSample sample, boolean retry) {
        static final Snapshot WAIT = new Snapshot(null, true), SKIP = new Snapshot(null, false);
    }
    /** Optional pre-mining snapshot. Native inability to serialize is distinct from temporary shortage. */
    static Snapshot capture(MineSamplePool memory, ServerLevel level, BlockPos origin, int radius,
                                       int top, int floor, Set<Long> targets, Set<Long> dependencies) {
        Map<Long, CompoundTag> terrain = new LinkedHashMap<>();
        Map<Long, ListTag> entities = new LinkedHashMap<>();
        Set<Long> targetOffsets = new LinkedHashSet<>(), workOffsets = new LinkedHashSet<>();
        var context = loadingContext(dependencies);
        long bytes = 16384 + context.size() * 256L;
        if (!memory.reserve(bytes)) return Snapshot.WAIT;
        Capture previous = activeCapture, capture = new Capture(level, context.keySet());
        activeCapture = capture;
        try {
            for (var required : context.entrySet()) {
                long key = required.getKey();
                ChunkAccess chunk = level.getChunkSource().getChunkForLighting(ChunkPos.getX(key), ChunkPos.getZ(key))
                        instanceof ChunkAccess loaded ? loaded : null;
                if (chunk == null || chunk.getPersistedStatus().isBefore(required.getValue())) return Snapshot.WAIT;
                boolean full = chunk.getPersistedStatus().getChunkType() == ChunkType.LEVELCHUNK;
                if (full && (!level.areEntitiesLoaded(key) || !TemporaryGeneration.ready(level, key))) return Snapshot.WAIT;
                CompoundTag tag = ChunkSerializer.write(level, chunk);
                NeoForge.EVENT_BUS.post(new ChunkDataEvent.Save(chunk, level, tag));
                // Save-event listeners may retain their argument. The template owns a separate payload.
                tag = tag.copy();
                ListTag savedEntities = new ListTag();
                if (full) {
                    AABB box = new AABB(chunk.getPos().getMinBlockX(), level.getMinBuildHeight(), chunk.getPos().getMinBlockZ(),
                            chunk.getPos().getMinBlockX() + 16, level.getMaxBuildHeight(), chunk.getPos().getMinBlockZ() + 16);
                    for (Entity entity : level.getEntities((Entity) null, box, e -> !e.isPassenger())) {
                        if (entity.chunkPosition().toLong() != key || entity instanceof Player) continue;
                        CompoundTag entityTag = new CompoundTag();
                        if (!entity.save(entityTag)) return Snapshot.SKIP;
                        savedEntities.add(entityTag.copy());
                    }
                }
                long weight = (long) tag.sizeInBytes() + savedEntities.sizeInBytes() + 96;
                if (!memory.reserve(weight)) return Snapshot.WAIT;
                bytes += weight;
                long offset = ChunkPos.asLong(ChunkPos.getX(key) - (origin.getX() >> 4), ChunkPos.getZ(key) - (origin.getZ() >> 4));
                terrain.put(offset, tag); entities.put(offset, savedEntities);
                if (dependencies.contains(key)) workOffsets.add(offset);
                if (targets.contains(key)) targetOffsets.add(offset);
            }
            if (capture.changed) return Snapshot.WAIT;
            var generation = LocalGeneration.at(level, new ChunkPos(origin));
            Description description = new Description(UUID.randomUUID(), MixedMine.source(level.dimension().location()).location(),
                    origin.getX(), origin.getZ(), top, floor, level.getMinBuildHeight(), level.getHeight(), radius,
                    Set.copyOf(targetOffsets), Set.copyOf(workOffsets));
            MineSample sample = new MineSample(memory, description, generation.seed(), terrain, entities, bytes);
            bytes = 0;
            return new Snapshot(sample, false);
        } catch (RuntimeException exception) {
            LOG.warn("Mine sample at {} cannot be retained; native first excavation remains available", origin, exception);
            return Snapshot.SKIP;
        } finally { activeCapture = previous; memory.free(bytes); }
    }

    /** Identify the native payload format; native loaders own its content and mod metadata. */
    boolean compatible(ServerLevel level) {
        if (compatible == null) {
            try { compatible = checkCompatibility(level); }
            catch (RuntimeException failure) { compatible = false; LOG.warn("Mine sample {} cannot be restored", description.id, failure); }
        }
        return compatible;
    }
    private boolean checkCompatibility(ServerLevel level) {
        if (level.getMinBuildHeight() != description.minY || level.getHeight() != description.height
                || !description.source.equals(MixedMine.source(level.dimension().location()).location())) return false;
        int version = net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        for (CompoundTag chunk : terrain.values())
            if (chunk.getInt("DataVersion") != version) return false;
        return true;
    }

    /** Independent tags are handed to native loaders; original structures are translated after native decoding. */
    CompoundTag terrain(long offset, BlockPos destination, long gameTime) {
        CompoundTag tag = terrain.get(offset).copy();
        int dx = destination.getX() - description.originX, dz = destination.getZ() - description.originZ;
        tag.putInt("xPos", tag.getInt("xPos") + (dx >> 4)); tag.putInt("zPos", tag.getInt("zPos") + (dz >> 4));
        tag.putLong("LastUpdate", gameTime);
        for (String name : List.of("block_entities", "block_ticks", "fluid_ticks"))
            for (Tag entry : tag.getList(name, Tag.TAG_COMPOUND)) shiftBlock((CompoundTag) entry, dx, dz);
        // FULL uses EntityStorage; a native protochunk still owns its not-yet-spawned entities.
        if (ChunkSerializer.getChunkTypeFromTag(tag) == ChunkType.LEVELCHUNK) tag.remove("entities");
        else for (Tag entity : tag.getList("entities", Tag.TAG_COMPOUND)) shiftEntity((CompoundTag) entity, dx, dz);
        CompoundTag structures = tag.getCompound("structures"), references = structures.getCompound("References");
        for (String name : references.getAllKeys()) {
            long[] refs = references.getLongArray(name);
            for (int i = 0; i < refs.length; i++) refs[i] = ChunkPos.asLong(ChunkPos.getX(refs[i]) + (dx >> 4), ChunkPos.getZ(refs[i]) + (dz >> 4));
            references.putLongArray(name, refs);
        }
        return tag;
    }
    private static void shiftBlock(CompoundTag tag, int dx, int dz) {
        tag.putInt("x", tag.getInt("x") + dx); tag.putInt("z", tag.getInt("z") + dz);
    }
    ListTag entities(long offset, BlockPos destination) {
        ListTag result = entities.getOrDefault(offset, new ListTag()).copy();
        int dx = destination.getX() - description.originX, dz = destination.getZ() - description.originZ;
        for (Tag tag : result) shiftEntity((CompoundTag) tag, dx, dz);
        return result;
    }
    private static void shiftEntity(CompoundTag tag, int dx, int dz) {
        ListTag pos = tag.getList("Pos", Tag.TAG_DOUBLE);
        pos.set(0, DoubleTag.valueOf(pos.getDouble(0) + dx)); pos.set(2, DoubleTag.valueOf(pos.getDouble(2) + dz));
        tag.putUUID("UUID", UUID.randomUUID());
        for (Tag child : tag.getList("Passengers", Tag.TAG_COMPOUND)) shiftEntity((CompoundTag) child, dx, dz);
    }

    Map<Structure, StructureStart> starts(ServerLevel level, long offset, BlockPos destination) {
        CompoundTag tags = terrain.get(offset).getCompound("structures").getCompound("starts");
        Map<Structure, StructureStart> result = new HashMap<>();
        int dx = destination.getX() - description.originX, dz = destination.getZ() - description.originZ;
        var context = StructurePieceSerializationContext.fromLevel(level);
        for (String name : tags.getAllKeys()) {
            StructureStart start = StructureStart.loadStaticStart(context, tags.getCompound(name).copy(), seed);
            if (start == null || !start.isValid()) continue;
            for (StructurePiece piece : start.getPieces()) {
                piece.move(dx, 0, dz);
                if (piece instanceof PoolElementStructurePiece pool) {
                    var junctions = pool.getJunctions();
                    for (int i = 0; i < junctions.size(); i++) {
                        JigsawJunction j = junctions.get(i);
                        junctions.set(i, new JigsawJunction(j.getSourceX() + dx, j.getSourceGroundY(), j.getSourceZ() + dz,
                                j.getDeltaY(), j.getDestProjection()));
                    }
                }
            }
            ChunkPos pos = start.getChunkPos();
            result.put(start.getStructure(), new StructureStart(start.getStructure(), new ChunkPos(pos.x + (dx >> 4), pos.z + (dz >> 4)),
                    start.getReferences(), new PiecesContainer(start.getPieces())));
        }
        return result;
    }

    /** Only air is excluded permanently. Hardness, fluids, protection and tool rules remain dynamic. */
    @Nullable int[] heights(int dx, int dz) {
        long key = ChunkPos.asLong(dx, dz);
        int[] cached = columns.get(key); if (cached != null) return cached;
        long offset = ChunkPos.asLong((8 + dx) >> 4, (8 + dz) >> 4);
        CompoundTag chunk = terrain.get(offset); if (chunk == null) return null;
        int[] scratch = new int[description.top - description.minY + 1]; int count = 0;
        Map<Integer, CompoundTag> sections = new HashMap<>();
        for (Tag entry : chunk.getList("sections", Tag.TAG_COMPOUND)) sections.put(((CompoundTag) entry).getByte("Y") + 0, (CompoundTag) entry);
        for (int section = description.top >> 4; section >= description.minY >> 4; section--) {
            CompoundTag data = sections.get(section); if (data == null) continue;
            CompoundTag states = data.getCompound("block_states"); ListTag palette = states.getList("palette", Tag.TAG_COMPOUND);
            if (palette.isEmpty()) continue;
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            SimpleBitStorage storage = palette.size() == 1 ? null : new SimpleBitStorage(bits, 4096, states.getLongArray("data"));
            for (int y = Math.min(description.top, (section << 4) + 15); y >= Math.max(description.minY, section << 4); y--) {
                int index = ((y & 15) << 8) | (((8 + dz) & 15) << 4) | ((8 + dx) & 15);
                String block = palette.getCompound(storage == null ? 0 : storage.get(index)).getString("Name");
                if (!block.equals("minecraft:air") && !block.equals("minecraft:cave_air") && !block.equals("minecraft:void_air")) scratch[count++] = y;
            }
        }
        long weight = 64L + count * 4L;
        if (!memory.reserve(weight)) return null;
        bytes += weight; cached = Arrays.copyOf(scratch, count); columns.put(key, cached); return cached;
    }

    CompoundTag encode() {
        CompoundTag tag = new CompoundTag(); tag.put("Description", description.save()); tag.putLong("Seed", seed);
        ListTag chunks = new ListTag();
        for (var entry : terrain.entrySet()) {
            CompoundTag chunk = new CompoundTag(); chunk.putLong("Offset", entry.getKey());
            chunk.put("Terrain", entry.getValue()); chunk.put("Entities", entities.get(entry.getKey())); chunks.add(chunk);
        }
        tag.put("Chunks", chunks); return tag;
    }
    static MineSample decode(MineSamplePool memory, CompoundTag tag, long reserved) {
        Map<Long, CompoundTag> terrain = new LinkedHashMap<>(); Map<Long, ListTag> entities = new LinkedHashMap<>();
        for (Tag entry : tag.getList("Chunks", Tag.TAG_COMPOUND)) {
            CompoundTag chunk = (CompoundTag) entry; long offset = chunk.getLong("Offset");
            terrain.put(offset, chunk.getCompound("Terrain")); entities.put(offset, chunk.getList("Entities", Tag.TAG_COMPOUND));
        }
        Description description = Description.load(tag.getCompound("Description"));
        if (!terrain.keySet().containsAll(description.workChunks()) || !description.workChunks().containsAll(description.targets())
                || description.top < description.floor || description.floor < description.minY
                || description.top >= description.minY + description.height || description.radius < 0)
            throw new IllegalArgumentException("Invalid mine sample extent");
        return new MineSample(memory, description, tag.getLong("Seed"), terrain, entities, reserved);
    }
}
