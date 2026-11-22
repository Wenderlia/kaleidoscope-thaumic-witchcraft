package dev.senluo.witchcraft.compat.thaumcraft;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Destructive cleanup is exercised only inside a newly allocated, isolated directory. */
@PrefixGameTestTemplate(false)
public final class MineStorageCleanupGameTests {
    private static final String PREFIX = "dimensions/kaleidoscope_witchcraft/";
    private static final List<String> STORES = List.of("region", "entities", "poi");

    private static final class Directory implements AutoCloseable {
        final Path root = Files.createTempDirectory("witchcraft-mine-cleanup-");
        Directory() throws IOException {}

        @Override public void close() throws IOException {
            // Files.walk does not follow symbolic links; every traversed entry belongs to this fixture.
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static Path write(Path root, String relative) throws IOException {
        var path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.write(path, ("fixture:" + relative).getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static Map<Path, byte[]> files(Path root) throws IOException {
        var result = new HashMap<Path, byte[]>();
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList())
                result.put(root.relativize(path), Files.readAllBytes(path));
        }
        return result;
    }

    private static void assertFiles(GameTestHelper h, Map<Path, byte[]> expected, Path root) throws IOException {
        var actual = files(root);
        h.assertTrue(expected.keySet().equals(actual.keySet()), "Cleanup changes only the selected files and creates no marker");
        for (var entry : expected.entrySet()) h.assertTrue(Arrays.equals(entry.getValue(), actual.get(entry.getKey())),
                "Retained data stays byte-for-byte unchanged: " + entry.getKey());
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty")
    public static void mineStorageCleanupDeletesOnlyOwnedChunkFilesAndIsIdempotent(GameTestHelper h) throws IOException {
        try (var directory = new Directory()) {
            var world = directory.root.resolve("world");
            var deleted = new HashSet<Path>();
            for (var mine : List.of("mixed_mine", "mixed_mine_nether", "mixed_mine_end",
                    "mixed_mine_sources/example/source/nested")) {
                var base = PREFIX + mine + "/";
                for (var store : STORES) {
                    for (var name : List.of("r.0.0.mca", "r.-20.39.mca", "c.31.-64.mcc"))
                        deleted.add(world.relativize(write(world, base + store + "/" + name)));
                    for (var name : List.of("notes.txt", "r.0.0.mca.bak", "r.invalid.0.mca",
                            "c.0.invalid.mcc", "archive/r.0.0.mca"))
                        write(world, base + store + "/" + name);
                }
                deleted.add(world.relativize(write(world, base + "data/ThaumcraftRetroGeneration.dat")));
                write(world, base + "data/ThaumcraftRetroGeneration.dat.bak");
                write(world, base + "data/custom_state.dat");
                write(world, base + "data/region/r.0.0.mca");
                write(world, base + "region_backup/r.0.0.mca");
                write(world, base + "level.dat");
            }
            for (var ordinary : List.of("", "DIM-1/", "DIM1/", "dimensions/example/ordinary/",
                    "dimensions/other_mod/mixed_mine/", PREFIX + "mixed_mine_archive/",
                    PREFIX + "ordinary/", PREFIX + "mixed_mine_sources/example/")) {
                for (var store : STORES) {
                    write(world, ordinary + store + "/r.0.0.mca");
                    write(world, ordinary + store + "/c.-1.2.mcc");
                }
            }
            write(world, "data/ThaumcraftRetroGeneration.dat");
            var expected = files(world);
            long deletedBytes = deleted.stream().mapToLong(path -> expected.get(path).length).sum();
            deleted.forEach(expected::remove);

            var first = MineStorageCleanup.clean(world);
            h.assertTrue(first.deletedFiles() == deleted.size() && first.deletedBytes() == deletedBytes
                            && first.failures() == 0,
                    "Cleanup reports the actual deleted file count and bytes without failures");
            assertFiles(h, expected, world);
            var second = MineStorageCleanup.clean(world);
            h.assertTrue(second.deletedFiles() == 0 && second.deletedBytes() == 0 && second.failures() == 0,
                    "Repeated cleanup reports no additional deletion or failure");
            assertFiles(h, expected, world);
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty")
    public static void mineStorageCleanupDoesNotFollowAvailableSymbolicLinks(GameTestHelper h) throws IOException {
        try (var directory = new Directory()) {
            var world = directory.root.resolve("world");
            var outside = directory.root.resolve("outside");
            var outsideChunk = write(outside, "r.0.0.mca");
            write(outside, "region/r.0.0.mca");
            write(outside, "entities/c.0.0.mcc");
            var linkedStore = world.resolve(PREFIX + "mixed_mine/region");
            var linkedFile = world.resolve(PREFIX + "mixed_mine_nether/region/r.0.0.mca");
            var linkedSource = world.resolve(PREFIX + "mixed_mine_sources/example/linked");
            Files.createDirectories(linkedStore.getParent());
            Files.createDirectories(linkedFile.getParent());
            Files.createDirectories(linkedSource.getParent());
            try {
                Files.createSymbolicLink(linkedStore, outside);
                Files.createSymbolicLink(linkedFile, outsideChunk);
                Files.createSymbolicLink(linkedSource, outside);
            } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
                LogUtils.getLogger().warn("Skipping mine cleanup symbolic-link assertions: link creation is unavailable", unavailable);
                h.succeed();
                return;
            }
            var before = files(outside);
            var ordinaryFile = write(world, PREFIX + "mixed_mine_end/poi/r.0.0.mca");
            MineStorageCleanup.clean(world);
            h.assertTrue(!Files.exists(ordinaryFile, LinkOption.NOFOLLOW_LINKS),
                    "Encountering a link does not stop cleanup of independent ordinary mine files");
            h.assertTrue(Files.isSymbolicLink(linkedStore) && Files.isSymbolicLink(linkedFile)
                            && Files.isSymbolicLink(linkedSource),
                    "Linked stores, files and source dimensions are neither followed nor removed");
            assertFiles(h, before, outside);
        }
        h.succeed();
    }
}
