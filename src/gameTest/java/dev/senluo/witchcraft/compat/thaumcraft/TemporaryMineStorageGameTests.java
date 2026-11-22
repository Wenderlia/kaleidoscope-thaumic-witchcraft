package dev.senluo.witchcraft.compat.thaumcraft;

import dev.tc4port.thaumcraft.api.ThaumcraftContent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.visitors.CollectToTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real native storage and mixins, isolated from the running world's region files. */
@PrefixGameTestTemplate(false)
public final class TemporaryMineStorageGameTests {
    private static final List<String> TYPES = List.of("chunk", "entities", "poi");
    private static final ChunkPos POS = new ChunkPos(0, 0);

    private static final class Worker extends IOWorker {
        Worker(ResourceKey<Level> dimension, String type, Path path) {
            super(new RegionStorageInfo("temporary-mine-test", dimension, type), path, false);
        }
    }

    private static final class Directory implements AutoCloseable {
        final Path root = Files.createTempDirectory("witchcraft-mine-storage-");
        Directory() throws IOException {}

        @Override public void close() throws IOException {
            // Only this fixture's freshly allocated directory; workers close before directory cleanup.
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static ResourceKey<Level> dimension(String id) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(id));
    }

    private static CompoundTag sample(String value) {
        var tag = new CompoundTag();
        tag.putInt("DataVersion", 0); // Native Blender identifies this as old data in ordinary worlds.
        tag.putString("Fixture", value);
        return tag;
    }

    private static Map<Path, byte[]> files(Path root) throws IOException {
        var result = new HashMap<Path, byte[]>();
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(Files::isRegularFile).toList())
                result.put(root.relativize(path), Files.readAllBytes(path));
        }
        return result;
    }

    private static void assertEmptyReads(GameTestHelper h, Worker worker) {
        h.assertTrue(worker.loadAsync(POS).join().isEmpty(), "Temporary mines do not load saved chunks");
        var visitor = new CollectToTag();
        worker.scanChunk(POS, visitor).join();
        h.assertTrue(visitor.getResult() == null, "Temporary mine scans do not expose saved NBT");
        h.assertTrue(!worker.isOldChunkAround(POS, 7), "Temporary mines do not read old Blender regions");
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty")
    public static void temporaryMineStorageNeverCreatesChunkFiles(GameTestHelper h) throws IOException {
        var mines = List.of(MixedMine.mine(Level.OVERWORLD), MixedMine.mine(Level.NETHER),
                MixedMine.mine(Level.END), MixedMine.mine(dimension("example:source/nested")));
        try (var directory = new Directory()) {
            int index = 0;
            for (var mine : mines) for (var type : TYPES) {
                try (var worker = new Worker(mine, type, directory.root.resolve(Integer.toString(index++)))) {
                    assertEmptyReads(h, worker);
                    worker.store(POS, sample("discarded")).join();
                    assertEmptyReads(h, worker);
                    worker.store(POS, null).join();
                    worker.synchronize(true).join();
                }
            }
            h.assertTrue(files(directory.root).isEmpty(),
                    "Terrain, entities and POI create no chunk files, including through reads and Blender scans");
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty")
    public static void temporaryMineStorageIgnoresAndPreservesOldFiles(GameTestHelper h) throws IOException {
        try (var directory = new Directory()) {
            for (var type : TYPES) {
                var path = directory.root.resolve(type);
                try (var ordinary = new Worker(Level.OVERWORLD, type, path)) {
                    ordinary.store(POS, sample("old saved chunk")).join();
                    ordinary.synchronize(true).join();
                }
                // An old external-chunk sidecar must also remain untouched by the new backend.
                Files.write(path.resolve("c.0.0.mcc"), new byte[]{1, 3, 5, 7});
            }
            var before = files(directory.root);
            h.assertTrue(before.size() == TYPES.size() * 2, "The fixture contains real region files and old sidecars");
            for (var type : TYPES) try (var worker = new Worker(MixedMine.mine(Level.OVERWORLD), type,
                    directory.root.resolve(type))) {
                assertEmptyReads(h, worker);
                worker.store(POS, sample("replacement")).join();
                worker.store(POS, null).join();
                worker.store(new ChunkPos(64, -64), sample("new region")).join();
                worker.synchronize(true).join();
            }
            var after = files(directory.root);
            h.assertTrue(before.keySet().equals(after.keySet()), "Old files are neither removed nor supplemented");
            for (var entry : before.entrySet()) h.assertTrue(Arrays.equals(entry.getValue(), after.get(entry.getKey())),
                    "Temporary mine IO leaves every old file byte unchanged: " + entry.getKey());
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty")
    public static void temporaryMineStorageLeavesOrdinaryWorldsPersistent(GameTestHelper h) throws IOException {
        var ordinaryWorlds = List.of(Level.OVERWORLD, dimension("other_mod:mixed_mine"),
                dimension("kaleidoscope_witchcraft:mixed_mine_archive"));
        var expected = sample("ordinary saved chunk");
        try (var directory = new Directory()) {
            int index = 0;
            for (var world : ordinaryWorlds) for (var type : TYPES) {
                var path = directory.root.resolve(Integer.toString(index++));
                try (var writer = new Worker(world, type, path)) {
                    writer.store(POS, expected.copy()).join();
                    writer.synchronize(true).join();
                }
                try (var reader = new Worker(world, type, path)) {
                    h.assertTrue(reader.loadAsync(POS).join().filter(expected::equals).isPresent(),
                            "Ordinary dimensions retain real disk round trips: " + world.location() + "/" + type);
                    var visitor = new CollectToTag();
                    reader.scanChunk(POS, visitor).join();
                    h.assertTrue(expected.equals(visitor.getResult()), "Ordinary native NBT scans remain available");
                    if (world.equals(Level.OVERWORLD) && type.equals("chunk"))
                        h.assertTrue(reader.isOldChunkAround(POS, 0), "Ordinary Blender detection still reads old chunks");
                }
            }
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty")
    public static void temporaryMineIdsStayWithinTheirReservedNamespace(GameTestHelper h) {
        for (var source : List.of(Level.OVERWORLD, Level.NETHER, Level.END, dimension("example:source/nested"))) {
            var mine = MixedMine.mine(source);
            h.assertTrue(source.equals(MixedMine.source(mine.location())), "Every mine ID resolves its real source");
            h.assertTrue(mine.equals(MixedMine.mine(mine)), "A mine does not recursively create another mine");
        }
        for (var id : List.of("other_mod:mixed_mine", "other_mod:mixed_mine_sources/example/source",
                "kaleidoscope_witchcraft:mixed_mine_archive", "kaleidoscope_witchcraft:mixed_mine_sources/",
                "kaleidoscope_witchcraft:mixed_mine_sources/example", "kaleidoscope_witchcraft:mixed_mine_sources/example/"))
            h.assertTrue(MixedMine.source(ResourceLocation.parse(id)) == null,
                    "Unrelated or incomplete IDs cannot select temporary storage: " + id);
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty")
    public static void temporaryMinePauseCreatesAFreshSession(GameTestHelper h) {
        var pos = new BlockPos(1, 1, 1);
        h.setBlock(pos, ThaumcraftContent.block("arcane_bore"));
        var bore = h.getLevel().getBlockEntity(h.absolutePos(pos));
        h.assertTrue(bore != null, "The fixture uses a real arcane bore block entity");
        var sessions = DeepMines.get(h.getLevel());
        var id = UUID.randomUUID();
        var otherId = UUID.randomUUID();
        try {
            var original = sessions.ensure(id);
            var work = original.bind(bore);
            var other = sessions.ensure(otherId);
            h.assertTrue(sessions.ensure(id) == original && original.bind(bore) == work,
                    "An active ritual and its actual bore retain their running session");

            sessions.remove(id); // Ritual pause and termination both use this operation.
            h.assertTrue(sessions.find(id) == null, "Pausing removes the old session from the registry");
            h.assertTrue(sessions.find(otherId) == other, "Pausing one ritual leaves the other session intact");

            var resumed = sessions.ensure(id);
            h.assertTrue(resumed != original && resumed.samples != original.samples,
                    "Resuming the same ritual creates fresh sampling state");
            h.assertTrue(resumed.bind(bore) != work,
                    "The same surviving bore cannot inherit the destroyed session's excavation");
            h.assertTrue(sessions.find(id) == resumed, "Only the new session remains registered");
        } finally {
            sessions.remove(id);
            sessions.remove(otherId);
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty")
    public static void temporaryMineBindingsFollowActualBoreInstances(GameTestHelper h) {
        var pos = new BlockPos(1, 1, 1);
        h.setBlock(pos, ThaumcraftContent.block("arcane_bore"));
        var original = h.getLevel().getBlockEntity(h.absolutePos(pos));
        h.assertTrue(original != null, "The first real bore exists");
        var sessions = DeepMines.get(h.getLevel());
        var id = UUID.randomUUID();
        try {
            var mine = sessions.ensure(id);
            var originalWork = mine.bind(original);
            h.setBlock(pos, Blocks.AIR);
            h.setBlock(pos, ThaumcraftContent.block("arcane_bore"));
            var replacement = h.getLevel().getBlockEntity(h.absolutePos(pos));
            h.assertTrue(replacement != null && replacement != original, "Replacement creates a different bore instance");
            var replacementWork = mine.bind(replacement);
            h.assertTrue(replacementWork != originalWork,
                    "A replacement at the same coordinates does not reuse old excavation history");
            mine.detach(original);
            h.assertTrue(mine.bind(replacement) == replacementWork,
                    "Detaching the old bore does not revoke its replacement's binding");
            mine.detach(replacement);
            h.assertTrue(mine.bind(replacement) != replacementWork,
                    "Detachment discards the actual bore's old excavation instance");
        } finally {
            sessions.remove(id);
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty")
    public static void destroyedMineSamplesCannotResumeWork(GameTestHelper h) {
        var pos = new BlockPos(1, 1, 1);
        h.setBlock(pos, ThaumcraftContent.block("arcane_bore"));
        var bore = h.getLevel().getBlockEntity(h.absolutePos(pos));
        h.assertTrue(bore != null, "The fixture uses a real arcane bore");
        var level = MixedMine.forHost(h.getLevel());
        h.assertTrue(level != null, "The native mine level is available");
        var sessions = DeepMines.get(h.getLevel());
        var id = UUID.randomUUID();
        try {
            var samples = sessions.ensure(id).samples;
            samples.level(h.getLevel());
            samples.maintain(bore, 3);
            var target = new MixedMine.Target(0L, samples.origin(0), Blocks.STONE.defaultBlockState(), 0, 0);
            sessions.remove(id);

            samples.maintain(bore, 3);
            var probe = samples.next(level, bore, 0);
            h.assertTrue(probe.target() == null && probe.state() == MixedMine.Supply.UNAVAILABLE,
                    "A destroyed session cannot resume sampling or request a cold read");
            h.assertTrue(!samples.accessible(level, target),
                    "A stale target cannot acquire neighborhood reads after session destruction");
            samples.completed(target);
            h.assertTrue(samples.preparation().demandColumns() == 0, "Destroyed samples retain no column demand");
            h.assertTrue(samples.preparation().heldChunks() == 0 && samples.preparation().pendingChunks() == 0,
                    "Stale calls do not restore the destroyed session's read demand");
            h.assertTrue(sessions.find(id) == null, "Stale calls do not recreate a registered session");
        } finally {
            sessions.remove(id);
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty")
    public static void mineColumnProgressRejectsStaleAndForeignBandReceipts(GameTestHelper h) {
        var pos = new BlockPos(1, 1, 1);
        h.setBlock(pos, ThaumcraftContent.block("arcane_bore"));
        var bore = h.getLevel().getBlockEntity(h.absolutePos(pos));
        h.assertTrue(bore != null, "The fixture uses a real arcane bore");
        var level = MixedMine.forHost(h.getLevel().getServer().overworld());
        h.assertTrue(level != null, "The overworld sample level is available");
        var samples = new MixedMine(12345L);
        samples.level(h.getLevel().getServer().overworld());
        try {
            // Register the virtual column without waiting for unrelated terrain generation.
            samples.next(level, bore, 0);
            h.assertTrue(samples.preparation().demandColumns() == 1, "The real sampling entry registered one column");
            int band = 0;
            int top = Math.min(0, level.getMaxBuildHeight() - 1);
            var origin = samples.origin(band);
            var state = Blocks.STONE.defaultBlockState();
            var first = new MixedMine.Target(0L, origin.atY(top), state, top, band);
            var delayed = new MixedMine.Target(0L, origin.atY(top - 4), state, top, band);
            h.assertTrue(samples.current(first) && samples.current(delayed),
                    "Concurrent candidates initially refer to the same observed column progress");
            samples.completed(first);

            var second = new MixedMine.Target(0L, origin.atY(top - 1), state, top - 1, band);
            h.assertTrue(samples.current(second), "The first receipt advances the column once");
            h.assertTrue(!samples.current(delayed), "Advancing the shared column invalidates an older candidate");
            samples.completed(delayed);
            h.assertTrue(samples.current(second), "A delayed receipt cannot skip newer column progress");
            samples.completed(second);

            var third = new MixedMine.Target(0L, origin.atY(top - 2), state, top - 2, band);
            samples.completed(first);
            samples.completed(second);
            h.assertTrue(samples.current(third), "Duplicate receipts cannot move the column backwards");

            var anotherBand = new MixedMine.Target(0L, samples.origin(band + 1).atY(top - 8), state, top - 2, band + 1);
            h.assertTrue(!samples.current(anotherBand), "Matching height cannot admit a receipt from another band");
            samples.completed(anotherBand);
            h.assertTrue(samples.current(third), "Another band's receipt cannot overwrite the current band's height");
            samples.completed(third);
            var fourth = new MixedMine.Target(0L, origin.atY(top - 3), state, top - 3, band);
            h.assertTrue(samples.current(fourth), "Current receipts still advance after stale receipts are discarded");
        } finally {
            samples.release();
        }
        h.succeed();
    }
}
