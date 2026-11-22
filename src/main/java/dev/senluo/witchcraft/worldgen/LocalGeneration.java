package dev.senluo.witchcraft.worldgen;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Independent generation contexts in one native carrier. Admission and release use the server thread. */
public final class LocalGeneration {
    // Address reservations, not generated terrain: only requested chunks and their native dependencies exist.
    private static final int CELL_CHUNKS = 4096, GRID = 512, CELLS = GRID * GRID;
    private static final Map<ServerLevel, Space> SPACES = new ConcurrentHashMap<>();
    private static boolean listening;

    private LocalGeneration() {}

    public static void attach(ServerLevel carrier) {
        if (!carrier.getServer().isSameThread()) throw new IllegalStateException("Generation admission requires the server thread");
        SPACES.computeIfAbsent(carrier, Space::new);
        if (!listening) {
            listening = true;
            NeoForge.EVENT_BUS.addListener(LocalGeneration::unload);
        }
    }

    /** Registers before any read ticket; occupied native addresses cannot be reassigned. */
    public static Context open(ServerLevel carrier, long seed) {
        attach(carrier);
        return SPACES.get(carrier).open(seed);
    }

    /** Called by native generation stages, including their worker threads. */
    @Nullable public static Context at(ServerLevel carrier, ChunkPos chunk) {
        Space space = SPACES.get(carrier);
        Cell cell = space == null ? null : space.cells.get(cellKey(chunk));
        return cell == null ? null : cell.context;
    }

    /** Optional generation side data. Native holder retirement is its only maintenance clock. */
    public interface ResidentData {
        void chunkRetired(long chunk);
        void close();
    }

    @Nullable public static <T extends ResidentData> T data(ServerLevel carrier, Class<T> type, Supplier<T> create) {
        Space space = SPACES.get(carrier);
        return space == null ? null : type.cast(space.data.computeIfAbsent(type, unused -> create.get()));
    }

    public static boolean hasHolder(ServerLevel carrier, long chunk) {
        Space space = SPACES.get(carrier);
        Cell cell = space == null ? null : space.cells.get(cellKey(chunk));
        return cell != null && cell.holders.containsKey(chunk);
    }

    public static void chunkCreated(ServerLevel carrier, ChunkPos chunk) {
        Space space = SPACES.get(carrier);
        if (space != null) space.cells.computeIfAbsent(cellKey(chunk), unused -> new Cell())
                .holders.merge(chunk.toLong(), 1, Integer::sum);
    }

    /** Final native holder retirement, after in-flight stages have finished. */
    public static void chunkUnloaded(ServerLevel carrier, long chunk) {
        Space space = SPACES.get(carrier);
        if (space == null) return;
        long key = cellKey(chunk);
        Cell cell = space.cells.get(key);
        if (cell != null) {
            // A replacement holder may already exist when an old holder's unload callback returns.
            Integer remaining = cell.holders.computeIfPresent(chunk, (unused, count) -> count == 1 ? null : count - 1);
            if (remaining == null) for (ResidentData data : space.data.values()) data.chunkRetired(chunk);
            space.retire(key, cell);
        }
    }

    private static long cellKey(ChunkPos chunk) { return ChunkPos.asLong(chunk.x >> 12, chunk.z >> 12); }
    private static long cellKey(long chunk) { return ChunkPos.asLong(ChunkPos.getX(chunk) >> 12, ChunkPos.getZ(chunk) >> 12); }

    private static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            Space space = SPACES.remove(level);
            if (space != null) {
                // Native shutdown drains generation before LevelEvent.Unload.
                space.data.values().forEach(ResidentData::close);
                space.data.clear();
                space.cells.clear();
            }
        }
    }

    private static final class Cell {
        volatile Context context;
        final Map<Long, Integer> holders = new ConcurrentHashMap<>();
    }

    private static final class Space {
        final ServerLevel level;
        final Map<Long, Cell> cells = new ConcurrentHashMap<>();
        final Map<Class<?>, ResidentData> data = new ConcurrentHashMap<>();
        Space(ServerLevel level) { this.level = level; }

        Context open(long seed) {
            int first = RandomSource.create(seed).nextInt(CELLS);
            for (int attempt = 0; attempt < CELLS; attempt++) {
                int slot = (first + attempt) % CELLS;
                int x = slot % GRID - GRID / 2, z = slot / GRID - GRID / 2;
                long key = ChunkPos.asLong(x, z);
                if (cells.containsKey(key)) continue;
                Cell cell = new Cell();
                Context context = new Context(this, key, seed);
                cell.context = context;
                cells.put(key, cell);
                return context;
            }
            throw new IllegalStateException("Local generation address space is occupied");
        }

        void retire(long key, Cell cell) {
            if (cell.holders.isEmpty() && (cell.context == null || cell.context.closed)) cells.remove(key, cell);
        }
    }

    public static final class Context implements AutoCloseable {
        private final Space space;
        private final long cell;
        private final long seed;
        private final RandomState randomState;
        private final ChunkGeneratorStructureState structures;
        private boolean closed;

        private Context(Space space, long cell, long seed) {
            this.space = space; this.cell = cell; this.seed = seed;
            var level = space.level;
            var generator = level.getChunkSource().getGenerator();
            var settings = generator instanceof NoiseBasedChunkGenerator noise
                    ? noise.generatorSettings().value() : NoiseGeneratorSettings.dummy();
            randomState = RandomState.create(settings, level.registryAccess().lookupOrThrow(Registries.NOISE), seed);
            structures = generator.createState(level.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET), randomState, seed);
        }

        public long seed() { return seed; }
        public RandomState randomState() { return randomState; }
        public ChunkGeneratorStructureState structures() { return structures; }
        public ServerLevel level() { return space.level; }
        public boolean resident() {
            Cell current = space.cells.get(cell);
            return current != null && current.context == this && !current.holders.isEmpty();
        }

        /** Pure random addressing; retries and prefetch use the same address without advancing RNG state. */
        public BlockPos origin(int sample) {
            if (closed) throw new IllegalStateException("Local generation context is closed");
            RandomSource random = RandomSource.create(seed + 0x9e3779b97f4a7c15L * sample);
            // Central half leaves 16K blocks on each edge for native dependencies and structure pieces.
            int x = ChunkPos.getX(cell) * CELL_CHUNKS + CELL_CHUNKS / 4 + random.nextInt(CELL_CHUNKS / 2);
            int z = ChunkPos.getZ(cell) * CELL_CHUNKS + CELL_CHUNKS / 4 + random.nextInt(CELL_CHUNKS / 2);
            return new BlockPos(x * 16 + 8, 0, z * 16 + 8);
        }

        @Override public void close() {
            closed = true;
            Cell current = space.cells.get(cell);
            if (current != null && current.context == this) space.retire(cell, current);
        }
    }
}
