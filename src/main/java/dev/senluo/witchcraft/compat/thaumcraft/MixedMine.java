package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.common.util.INBTSerializable;
import javax.annotation.Nullable;

/** One excavation per ritual. Native levels own terrain; each ritual owns its cursors and read tickets. */
public final class MixedMine implements INBTSerializable<CompoundTag> {
    private record Realm(ResourceKey<Level> source, ResourceKey<Level> mine, int top, int bottom) {}
    private static final Realm[] REALMS = {
            realm(Level.OVERWORLD, "mixed_mine", 0, -60),
            realm(Level.NETHER, "mixed_mine_nether", 127, 0),
            realm(Level.END, "mixed_mine_end", 255, 0)
    };
    private static final String GENERATED_PREFIX = "mixed_mine_sources/";
    // Stable identity mapping only: no levels or servers retained across sessions.
    private static final Map<ResourceKey<Level>,ResourceKey<Level>> MINES = new java.util.concurrent.ConcurrentHashMap<>();

    private static Realm realm(ResourceKey<Level> source, String name, int top, int bottom) {
        return new Realm(source, ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID, name)), top, bottom);
    }

    /** Legacy mine IDs stay stable; other IDs reversibly encode their actual source. */
    public static ResourceKey<Level> source(ResourceLocation mine) {
        for (var realm : REALMS) if (realm.mine().location().equals(mine)) return realm.source();
        if (mine.getNamespace().equals(KaleidoscopeWitchcraft.MOD_ID) && mine.getPath().startsWith(GENERATED_PREFIX)) {
            String encoded = mine.getPath().substring(GENERATED_PREFIX.length());
            int separator = encoded.indexOf('/');
            if (separator > 0 && separator < encoded.length()-1) {
                var id = ResourceLocation.tryParse(encoded.substring(0,separator)+":"+encoded.substring(separator+1));
                if (id != null) return ResourceKey.create(Registries.DIMENSION,id);
            }
        }
        return null;
    }

    public static ResourceKey<Level> mine(ResourceKey<Level> source) {
        return MINES.computeIfAbsent(source, key -> {
            // A mine already represents its source; using a drill there does not recursively clone it.
            if (source(key.location()) != null) return key;
            for (var realm : REALMS) if (realm.source().equals(key)) return realm.mine();
            return ResourceKey.create(Registries.DIMENSION,ResourceLocation.fromNamespaceAndPath(
                    KaleidoscopeWitchcraft.MOD_ID,GENERATED_PREFIX+key.location().getNamespace()+"/"+key.location().getPath()));
        });
    }

    @Nullable public static ServerLevel forHost(ServerLevel host) {
        ResourceKey<Level> key=mine(host.dimension());
        ServerLevel existing=host.getServer().getLevel(key);
        return existing != null ? existing : ((MineLevelFactory)host.getServer()).witchcraft$mineLevel(host,key);
    }
    private static final int LEASE = 60;
    private static final TicketType<UUID> READ = TicketType.create("witchcraft_ritual_mine_read", UUID::compareTo, LEASE);
    private final UUID owner;
    private final Runnable changed;
    private final Map<Long, Long> pending = new HashMap<>(), renewed = new HashMap<>();
    private long lastPruned = Long.MIN_VALUE;
    private ServerLevel readLevel;

    MixedMine(UUID owner, Runnable changed) {
        this.owner = owner; this.changed = changed;
        this.seed = owner.getMostSignificantBits() ^ owner.getLeastSignificantBits();
    }

    /** Releases only this ritual's tickets, even when another ritual reads the same chunk. */
    void release() {
        if (readLevel != null) for (long key : renewed.keySet())
            readLevel.getChunkSource().removeRegionTicket(READ, new ChunkPos(key), 0, owner);
        pending.clear(); renewed.clear(); lastPruned = Long.MIN_VALUE;
        readLevel = null;
    }
    public static void unload(ServerLevel level) {
        MINES.remove(level.dimension());
    }
    // Each ritual admits its own misses; vanilla still coalesces generation of a shared chunk.
    private LevelChunk ready(ServerLevel level, ChunkPos pos) {
        if (readLevel != level) { release(); readLevel = level; }
        var chunks = level.getChunkSource();
        LevelChunk loaded = chunks.getChunkNow(pos.x, pos.z);
        long now = level.getGameTime(), key = pos.toLong();
        if (lastPruned == Long.MIN_VALUE || now - lastPruned >= 10) {
            lastPruned = now;
            renewed.values().removeIf(tick -> now - tick >= LEASE);
            pending.entrySet().removeIf(e -> now - e.getValue() >= LEASE
                    || chunks.getChunkNow(ChunkPos.getX(e.getKey()), ChunkPos.getZ(e.getKey())) != null);
        }
        if (loaded != null) pending.remove(key);
        else {
            if (!pending.containsKey(key) && pending.size() >= 2) return null;
            pending.put(key, now);
        }
        Long lastRenewal = renewed.get(key);
        if (lastRenewal == null || now - lastRenewal >= 20) {
            chunks.addRegionTicket(READ, pos, 0, owner);
            renewed.put(key, now);
        }
        return loaded;
    }

    private static final class Column {
        int band, y, bottom;
        Column(int top, int bottom) { this.y = top; this.bottom = bottom; }
    }
    private final Map<Long, Column> columns = new HashMap<>();
    private long seed;
    private int originBand = -1, originX, originZ;
    private CompoundTag savedColumns;
    private ResourceLocation samplingRealm;
    public record Target(BlockPos rock, BlockPos virtual, BlockState state, int top) {}
    /** Waiting must not advance a column; a fully examined empty band may advance. */
    public record Probe(@Nullable Target target, boolean waiting) {
        static final Probe WAIT = new Probe(null, true), EMPTY = new Probe(null, false);
    }

    private static long key(int x, int z) { return ChunkPos.asLong(x, z); }
    private BlockPos sample(int dx, int dz, Column col) {
        if (originBand != col.band) {
            RandomSource random = RandomSource.create(seed + 0x9e3779b97f4a7c15L * col.band);
            originX = (random.nextInt(125000) - 62500) * 16 + 8;
            originZ = (random.nextInt(125000) - 62500) * 16 + 8;
            originBand = col.band;
        }
        return new BlockPos(originX + dx, col.y, originZ + dz);
    }

    public Probe next(ServerLevel mine, BlockEntity bore, BlockPos rock) {
        ResourceKey<Level> source=source(mine.dimension().location());
        Realm preset = null;
        for (var candidate : REALMS) if (candidate.source().equals(source)) { preset = candidate; break; }
        int top = preset == null ? mine.getMaxBuildHeight()-1 : Math.min(preset.top(), mine.getMaxBuildHeight()-1);
        int floor = preset == null ? mine.getMinBuildHeight() : Math.max(preset.bottom(), mine.getMinBuildHeight());
        if (top < floor) return Probe.EMPTY;
        if (!mine.dimension().location().equals(samplingRealm)) {
            columns.clear(); originBand = -1; savedColumns = null;
            samplingRealm = mine.dimension().location(); changed.run();
        }
        int dx = rock.getX() - bore.getBlockPos().getX(), dz = rock.getZ() - bore.getBlockPos().getZ();
        Column col = columns.computeIfAbsent(key(dx, dz), ignored -> {
            savedColumns = null; changed.run(); return new Column(top, floor);
        });
        BlockPos pos = sample(dx, dz, col);
        ChunkPos chunkPos = new ChunkPos(pos);
        LevelChunk chunk = ready(mine, chunkPos);
        if (chunk == null || !mine.areEntitiesLoaded(chunkPos.toLong())) return Probe.WAIT;
        int bottom = Math.max(col.bottom, mine.getMinBuildHeight());
        BlockPos.MutableBlockPos scan=pos.mutable();
        for (int y = Math.min(col.y, top); y >= bottom; y--) {
            scan.setY(y);
            if (y == floor && col.bottom == floor && floor > mine.getMinBuildHeight()) {
                Integer extended = structureBottom(mine, chunk, scan);
                if (extended == null) return Probe.WAIT;
                col.bottom = Math.min(floor, extended);
                savedColumns = null;
                changed.run();
                bottom = Math.max(col.bottom, mine.getMinBuildHeight());
            }
            BlockState state = chunk.getBlockState(scan);
            if (!state.isAir()
                    && !(state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)
                    && state.getDestroySpeed(mine, scan) >= 0) {
                return new Probe(new Target(rock.immutable(), scan.immutable(), state, Math.min(col.y, top)), false);
            }
        }
        col.band++;
        col.y = top;
        col.bottom = floor;
        savedColumns = null; changed.run();
        return Probe.EMPTY;
    }

    // Only inspect generated structure references when crossing the bottom of the sample band.
    @Nullable private Integer structureBottom(ServerLevel level, LevelChunk chunk, BlockPos pos) {
        int bottom = pos.getY();
        for (var entry : chunk.getAllReferences().entrySet()) {
            for (long startPos : entry.getValue()) {
                ChunkPos origin = new ChunkPos(startPos);
                ChunkAccess source = level.getChunkSource().getChunkForLighting(origin.x, origin.z) instanceof ChunkAccess access
                        ? access : ready(level, origin);
                if (source == null) return null;
                var start = source.getStartForStructure(entry.getKey());
                if (start == null || !start.isValid()) continue;
                for (var piece : start.getPieces()) {
                    BoundingBox box = piece.getBoundingBox();
                    if (box.isInside(pos)) bottom = Math.min(bottom, start.getBoundingBox().minY());
                }
            }
        }
        return bottom;
    }

    public boolean accessible(ServerLevel mine, Target target) {
        ChunkPos pos = new ChunkPos(target.virtual());
        // Prepare the surrounding chunks for ordinary area tools, loot and neighbor updates.
        // Visit every neighbor even after a miss: ready() bounds pending requests for this ritual.
        boolean loaded = true;
        for (int x = pos.x - 1; x <= pos.x + 1; x++) for (int z = pos.z - 1; z <= pos.z + 1; z++) {
            ChunkPos next = new ChunkPos(x, z);
            if (ready(mine, next) == null || !mine.areEntitiesLoaded(next.toLong())) loaded = false;
        }
        return loaded;
    }

    public void completed(BlockEntity bore, Target target) {
        Column col = columns.get(key(target.rock().getX() - bore.getBlockPos().getX(),
                target.rock().getZ() - bore.getBlockPos().getZ()));
        if (col != null) col.y = target.virtual().getY() - 1;
        savedColumns = null; changed.run();
    }

    @Override public CompoundTag serializeNBT(HolderLookup.Provider lookup) {
        if (savedColumns != null) return savedColumns;
        CompoundTag data = new CompoundTag();
        data.putLong("Seed", seed);
        if (samplingRealm != null) data.putString("Realm", samplingRealm.toString());
        int[] saved = new int[columns.size() * 5];
        int i = 0;
        for (var entry : columns.entrySet()) {
            saved[i++] = ChunkPos.getX(entry.getKey()); saved[i++] = ChunkPos.getZ(entry.getKey());
            Column col = entry.getValue();
            saved[i++] = col.band; saved[i++] = col.y; saved[i++] = col.bottom;
        }
        data.putIntArray("Columns", saved);
        savedColumns = data;
        return data;
    }
    @Override public void deserializeNBT(HolderLookup.Provider lookup, CompoundTag data) {
        savedColumns = null;
        originBand = -1;
        seed = data.getLong("Seed");
        samplingRealm = data.contains("Realm") ? ResourceLocation.tryParse(data.getString("Realm"))
                : REALMS[0].mine().location();
        columns.clear();
        int[] saved = data.getIntArray("Columns");
        for (int i = 0; i + 4 < saved.length; i += 5) {
            Column col = new Column(saved[i+3], saved[i+4]);
            col.band = saved[i+2]; col.y = saved[i+3]; col.bottom = saved[i+4];
            columns.put(key(saved[i], saved[i+1]), col);
        }
    }
}
