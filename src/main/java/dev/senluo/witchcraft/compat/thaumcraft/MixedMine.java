package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import javax.annotation.Nullable;

/** One disposable excavation per ritual session, sharing native terrain and generation facilities. */
public final class MixedMine {
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
    private MineSupply supply;
    private boolean closed;

    MixedMine(long seed) {
        supply = new MineSupply(seed);
    }

    @Nullable ServerLevel level(ServerLevel host) {
        if (closed) return null;
        ServerLevel carrier = forHost(host);
        if (carrier != null) {
            MineSupply previous = supply;
            supply = supply.atLevel(carrier);
            if (supply != previous) { columns.clear(); scans.clear(); }
        }
        return carrier;
    }

    /** Permanently closes this session; resource leases retire through their own supply run. */
    void release() {
        closed = true;
        supply.close(); columns.clear(); scans.clear();
    }
    /** Real work heartbeat; merely calling this never creates a mine level or advances sampling. */
    public void maintain(BlockEntity bore, int radius) { if (!closed) supply.maintain(bore, radius); }
    void detach(BlockEntity bore) { supply.detach(bore); scans.remove(bore); }
    public record Preparation(int targetBands, int readyBands, int pendingChunks, int heldChunks,
                              double bandsPerSecond, double preparationLatencySeconds, int demandColumns, int forecastRebuilds) {}
    public Preparation preparation() { return supply.snapshot(); }
    public static void unload(ServerLevel level) {
        MINES.remove(level.dimension());
        MineReadQueue.unload(level);
    }
    private LevelChunk ready(ServerLevel level, ChunkPos pos) {
        if (closed) return null;
        supply.reads.bind(level);
        LevelChunk loaded = supply.reads.chunk(pos);
        if (loaded == null) supply.waiting();
        return loaded;
    }

    static final class Column {
        int band, y, bottom;
        MineSupply.Replay replay;
        int replayY, replayBottom;
        Column(int top, int bottom) { this.y = top; this.bottom = bottom; }
    }
    private final Map<Long, Column> columns = new HashMap<>();
    private static final class Scan { long cursor; }
    private final Map<BlockEntity, Scan> scans = new java.util.WeakHashMap<>();
    private ResourceLocation samplingRealm;
    /** Band and starting height identify the column progress observed by this candidate. */
    public record Target(long column, BlockPos virtual, BlockState state, int top, int band,
                         Column owner, MineSupply.Lease lease) implements AutoCloseable {
        public Target(long column, BlockPos virtual, BlockState state, int top, int band) { this(column, virtual, state, top, band, null, null); }
        boolean withinRadius(int radius) {
            long dx = ChunkPos.getX(column), dz = ChunkPos.getZ(column);
            return dx * dx + dz * dz <= (long) radius * radius;
        }
        @Override public void close() { if (lease != null) lease.close(); }
    }
    public enum Supply { READY, WAITING, EXHAUSTED, UNAVAILABLE, FAILED }
    /** Changing the next scanned column is separate from consuming its height or band. */
    public record Probe(@Nullable Target target, Supply state) {
        static final Probe WAIT = new Probe(null, Supply.WAITING), EXHAUSTED = new Probe(null, Supply.EXHAUSTED),
                UNAVAILABLE = new Probe(null, Supply.UNAVAILABLE), FAILED = new Probe(null, Supply.FAILED);
    }

    private static long key(int x, int z) { return ChunkPos.asLong(x, z); }
    /** Pure addressing, shared with prefetch; requesting a future band never consumes RNG state. */
    BlockPos origin(int band) {
        return supply.origin(band);
    }
    private BlockPos sample(int dx, int dz, Column col) {
        return origin(col.band).offset(dx, col.y, dz);
    }

    void advance(BlockEntity bore) {
        Scan scan = scans.get(bore);
        if (!closed && scan != null) scan.cursor++;
    }

    public Probe next(ServerLevel mine, BlockEntity bore, int radius) {
        if (closed) return Probe.UNAVAILABLE;
        MineSupply run = supply;
        supply.reads.bind(mine);
        ResourceKey<Level> source=source(mine.dimension().location());
        Realm preset = null;
        for (var candidate : REALMS) if (candidate.source().equals(source)) { preset = candidate; break; }
        int top = preset == null ? mine.getMaxBuildHeight()-1 : Math.min(preset.top(), mine.getMaxBuildHeight()-1);
        int floor = preset == null ? mine.getMinBuildHeight() : Math.max(preset.bottom(), mine.getMinBuildHeight());
        if (top < floor) return Probe.UNAVAILABLE;
        if (!mine.dimension().location().equals(samplingRealm)) {
            columns.clear(); scans.clear();
            samplingRealm = mine.dimension().location();
        }
        supply.bounds(top, floor);
        Scan scanState = scans.computeIfAbsent(bore, ignored -> new Scan());
        scanState.cursor = nextColumn(scanState.cursor, radius);
        int[] offset = column(scanState.cursor, radius);
        int dx = offset[0], dz = offset[1];
        Column col = columns.get(key(dx, dz));
        if (col == null) { col = new Column(top, floor); columns.put(key(dx, dz), col); supply.column(key(dx, dz), col.band); }
        BlockPos pos = sample(dx, dz, col);
        ChunkPos chunkPos = new ChunkPos(pos);
        long columnKey = key(dx, dz);
        boolean failedReplay = col.replay != null && col.replay.copy.failed;
        var replay = supply.replay(columnKey);
        if (col.replay != replay) {
            col.replay = replay;
            if (replay != null) { col.replayY = replay.copy.sample.description.top(); col.replayBottom = replay.copy.sample.description.floor(); }
        }
        boolean fresh = supply.available(chunkPos.toLong());
        if (fresh) {
            supply.freshAvailable(columnKey);
        } else {
            supply.await(columnKey, chunkPos.toLong(), col.replay != null);
            if (col.replay == null) {
                return failedReplay ? Probe.FAILED : Probe.WAIT;
            }
            if (!col.replay.ready()) return Probe.WAIT;
            var description = col.replay.copy.sample.description;
            top = description.top(); floor = description.floor();
            pos = col.replay.copy.origin().offset(dx, col.replayY, dz);
            chunkPos = new ChunkPos(pos);
        }
        LevelChunk chunk = ready(mine, chunkPos);
        if (chunk == null) return Probe.WAIT;
        int cursor = fresh ? col.y : col.replayY;
        int bottom = Math.max(fresh ? col.bottom : col.replayBottom, mine.getMinBuildHeight());
        int[] index = fresh ? null : col.replay.copy.sample.heights(dx, dz);
        BlockPos.MutableBlockPos scan=pos.mutable();
        for (int y = Math.min(cursor, top); y >= bottom; y--) {
            if (closed || supply != run || columns.get(columnKey) != col) return Probe.UNAVAILABLE;
            if (index != null) {
                int candidate = indexedHeight(index, y);
                // Even an air cell at the slice boundary can open the original structure extension.
                y = y >= floor && candidate < floor && bottom == floor ? floor : candidate;
                if (y < bottom) break;
            }
            scan.setY(y);
            if (y == floor && bottom == floor && floor > mine.getMinBuildHeight()) {
                Integer extended = structureBottom(mine, chunk, scan);
                if (closed || supply != run || columns.get(columnKey) != col) return Probe.UNAVAILABLE;
                if (extended == null) return Probe.WAIT;
                bottom = Math.max(Math.min(floor, extended), mine.getMinBuildHeight());
                if (fresh) col.bottom = bottom; else col.replayBottom = bottom;
            }
            BlockState state = chunk.getBlockState(scan);
            boolean mineable = !state.isAir()
                    && !(state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)
                    && state.getDestroySpeed(mine, scan) >= 0;
            if (closed || supply != run || columns.get(columnKey) != col) return Probe.UNAVAILABLE;
            if (mineable) {
                var lease = supply.hold(col.band, chunkPos.toLong(), fresh ? null : col.replay);
                Target target = new Target(columnKey, scan.immutable(), state, Math.min(cursor, top), col.band, col, lease);
                lease.attach(target);
                return new Probe(target, Supply.READY);
            }
        }
        if (fresh) { col.band++; col.y = top; col.bottom = floor; supply.column(columnKey, col.band); }
        else { supply.leave(columnKey, col.replay); col.replay = null; }
        return Probe.EXHAUSTED;
    }
    private static int indexedHeight(int[] heights, int atMost) {
        int low = 0, high = heights.length;
        while (low < high) { int mid = (low + high) >>> 1; if (heights[mid] > atMost) low = mid + 1; else high = mid; }
        return low == heights.length ? Integer.MIN_VALUE : heights[low];
    }

    // Only inspect generated structure references when crossing the bottom of the sample band.
    @Nullable private Integer structureBottom(ServerLevel level, LevelChunk chunk, BlockPos pos) {
        int bottom = pos.getY();
        for (var entry : chunk.getAllReferences().entrySet()) {
            for (long startPos : entry.getValue()) {
                ChunkPos origin = new ChunkPos(startPos);
                ChunkAccess source = supply.reads.structure(origin);
                if (source == null) { supply.waiting(); return null; }
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
        if (closed) return false;
        ChunkPos pos = new ChunkPos(target.virtual());
        // Prepare the surrounding chunks for ordinary area tools, loot and neighbor updates.
        // Visit every neighbor after a miss; shared admission prioritizes these actual dependencies.
        boolean loaded = true;
        for (int x = pos.x - 1; x <= pos.x + 1; x++) for (int z = pos.z - 1; z <= pos.z + 1; z++) {
            ChunkPos next = new ChunkPos(x, z);
            if (ready(mine, next) == null) loaded = false;
        }
        return loaded;
    }

    @Nullable private Column currentColumn(Target target) {
        if (closed || target == null) return null;
        Column col = columns.get(target.column());
        if (col == null) return null;
        var lease = target.lease();
        boolean replay = lease != null && lease.replay != null;
        if (lease != null && (!lease.current(supply) || target.owner() != col
                || replay && (lease.replay != col.replay || !supply.owns(target.column(), lease.replay)))) return null;
        if (!replay && col.band != target.band()) return null;
        return (replay ? col.replayY : col.y) == target.top() ? col : null;
    }

    boolean current(Target target) { return currentColumn(target) != null; }

    void started(Target target) {
        Column col = currentColumn(target);
        if (col != null && (target.lease() == null || target.lease().replay == null)) supply.started(col.band);
    }

    public void completed(Target target) {
        completed(target, false);
    }
    void completed(Target target, boolean produced) {
        Column col = currentColumn(target);
        if (col == null) return;
        if (target.lease() == null || target.lease().replay == null) {
            if (produced) supply.produced(col.band, ChunkPos.getX(target.column()), ChunkPos.getZ(target.column()));
            col.y = target.virtual().getY() - 1;
        } else col.replayY = target.virtual().getY() - 1;
    }

    static int[] column(long cursor, int radius) {
        long diameter = radius * 2L + 1;
        int x = (int) (Math.floorMod(cursor, diameter) - radius);
        int z = (int) (Math.floorMod(Math.floorDiv(cursor, diameter), diameter) - radius);
        return (long) x * x + (long) z * z <= (long) radius * radius ? new int[]{x, z} : null;
    }

    /** Skip impossible lattice cells without changing the order of the circular scan. */
    static long nextColumn(long cursor, int radius) {
        long diameter = radius * 2L + 1;
        for (int row = 0; row < 2; row++) {
            long x = Math.floorMod(cursor, diameter) - radius;
            long z = Math.floorMod(Math.floorDiv(cursor, diameter), diameter) - radius;
            long square = (long) radius * radius - z * z;
            long extent = (long) Math.sqrt(square);
            while (extent * extent > square) extent--;
            while ((extent + 1) * (extent + 1) <= square) extent++;
            if (x < -extent) return cursor + (-extent - x);
            if (x <= extent) return cursor;
            cursor += radius - x + 1;
        }
        throw new IllegalStateException("A circular scan row must contain a valid column");
    }
}
