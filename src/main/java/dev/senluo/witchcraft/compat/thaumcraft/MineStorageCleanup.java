package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.List;
import java.util.regex.Pattern;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Startup-only removal of obsolete native chunk files, before any level opens its storage. */
public final class MineStorageCleanup {
    private static final Logger LOG = LoggerFactory.getLogger(MineStorageCleanup.class);
    private static final List<String> STORES = List.of("region", "entities", "poi");
    private static final Pattern CHUNK_FILE = Pattern.compile("(?:r\\.(-?\\d+)\\.(-?\\d+)\\.mca|c\\.(-?\\d+)\\.(-?\\d+)\\.mcc)");
    private static final int FAILURE_DETAILS = 3;

    public record Result(long deletedFiles, long deletedBytes, long failures) {}

    /** The caller owns the save lock and has not constructed any ServerLevel for this save. */
    public static Result clean(Path worldRoot) {
        var totals = new Totals();
        Path root = directory(worldRoot.toAbsolutePath().normalize(), totals);
        if (root != null) {
            Path dimensions = directory(root.resolve("dimensions"), totals);
            Path namespace = dimensions == null ? null : directory(dimensions.resolve(KaleidoscopeWitchcraft.MOD_ID), totals);
            if (namespace != null) discover(namespace, totals);
        }
        LOG.info("Temporary mine cleanup: deleted {} files ({} bytes), {} I/O failures",
                totals.files, totals.bytes, totals.failures);
        return new Result(totals.files, totals.bytes, totals.failures);
    }

    private static void discover(Path namespace, Totals totals) {
        try (var entries = Files.newDirectoryStream(namespace)) {
            for (Path entry : entries) {
                Path candidate = directory(entry, totals);
                if (candidate == null) continue;
                if (candidate.getFileName().toString().equals("mixed_mine_sources")) discoverSources(namespace, candidate, totals);
                else if (isMine(namespace, candidate)) cleanStores(candidate, totals);
            }
        } catch (DirectoryIteratorException failure) {
            totals.failed(namespace, failure.getCause());
        } catch (IOException failure) {
            totals.failed(namespace, failure);
        }
    }

    private static void discoverSources(Path namespace, Path sources, Totals totals) {
        var pending = new ArrayDeque<Path>();
        pending.push(sources);
        while (!pending.isEmpty()) {
            Path dir = directory(pending.pop(), totals);
            if (dir == null) continue;
            // A SavedData tree can resemble another encoded dimension. Preserve that ambiguity.
            if (dir.getFileName().toString().equals("data") && isMine(namespace, dir.getParent())) continue;
            if (isMine(namespace, dir)) cleanStores(dir, totals);
            // Validate each directory before opening it; a Windows junction is not necessarily a symbolic link.
            try (var entries = Files.newDirectoryStream(dir)) {
                for (Path entry : entries) {
                    Path child = directory(entry, totals);
                    if (child != null) pending.push(child);
                }
            } catch (DirectoryIteratorException failure) {
                totals.failed(dir, failure.getCause());
            } catch (IOException failure) {
                totals.failed(dir, failure);
            }
        }
    }

    private static boolean isMine(Path namespace, Path candidate) {
        if (!candidate.startsWith(namespace)) return false;
        String path = namespace.relativize(candidate).toString().replace('\\', '/');
        ResourceLocation id = ResourceLocation.tryParse(KaleidoscopeWitchcraft.MOD_ID + ":" + path);
        return id != null && MixedMine.source(id) != null;
    }

    private static void cleanStores(Path mine, Totals totals) {
        for (String name : STORES) {
            Path storage = directory(mine.resolve(name), totals);
            if (storage == null) continue;
            try (var files = Files.newDirectoryStream(storage)) {
                for (Path file : files) {
                    if (!nativeChunkFile(file.getFileName().toString())) continue;
                    deleteFile(storage, file, totals);
                }
            } catch (DirectoryIteratorException failure) {
                totals.failed(storage, failure.getCause());
            } catch (IOException failure) {
                totals.failed(storage, failure);
            }
        }
        Path data = directory(mine.resolve("data"), totals);
        if (data != null) deleteFile(data, data.resolve("ThaumcraftRetroGeneration.dat"), totals);
    }

    private static void deleteFile(Path parent, Path file, Totals totals) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) return;
            Path actual = file.toRealPath();
            if (!actual.equals(file) || !actual.getParent().equals(parent)) return;
            if (Files.deleteIfExists(actual)) { totals.files++; totals.bytes += attributes.size(); }
        } catch (NoSuchFileException ignored) {
            // Another cleanup may already have removed this direct file.
        } catch (IOException failure) { totals.failed(file, failure); }
    }

    private static boolean nativeChunkFile(String name) {
        var match = CHUNK_FILE.matcher(name);
        if (!match.matches()) return false;
        int first = match.group(1) == null ? 3 : 1;
        try {
            for (int index = first; index <= first + 1; index++) {
                String coordinate = match.group(index);
                if (!Integer.toString(Integer.parseInt(coordinate)).equals(coordinate)) return false;
            }
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static Path directory(Path path, Totals totals) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) return null;
            // Do not traverse aliases, including junctions which are not reported as symbolic links on Windows.
            return path.toRealPath().equals(path) ? path : null;
        } catch (NoSuchFileException ignored) {
            return null;
        } catch (IOException failure) {
            totals.failed(path, failure);
            return null;
        }
    }

    private static final class Totals {
        long files, bytes, failures;
        void failed(Path path, IOException failure) {
            if (++failures <= FAILURE_DETAILS) LOG.warn("Temporary mine cleanup skipped {}: {}", path, failure.toString());
        }
    }

    private MineStorageCleanup() {}
}
