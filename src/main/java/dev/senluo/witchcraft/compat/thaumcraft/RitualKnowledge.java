package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.ritual.RitualResearch;
import dev.tc4port.thaumcraft.api.player.PlayerKnowledgeApi;
import dev.tc4port.thaumcraft.api.research.ResearchApi;
import dev.tc4port.thaumcraft.api.research.ResearchKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Live single-key queries; offline snapshots shared across centers until the saved record changes. */
final class RitualKnowledge {
    private static final int MAX_PLAYERS = 256;
    private static final Map<MinecraftServer, Cache> CACHES = new WeakHashMap<>();
    private record FileStamp(FileTime modified, long size, Object identity) {
        static FileStamp read(Path path) throws IOException {
            try {
                var attrs = Files.readAttributes(path, BasicFileAttributes.class);
                return new FileStamp(attrs.lastModifiedTime(), attrs.size(), attrs.fileKey());
            } catch (NoSuchFileException absent) { return null; }
        }
    }
    private record Revision(FileStamp current, FileStamp fallback) {
        static Revision read(MinecraftServer server, UUID player) throws IOException {
            Path dir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
            return new Revision(FileStamp.read(dir.resolve(player + ".dat")), FileStamp.read(dir.resolve(player + ".dat_old")));
        }
    }
    private static final class Entry {
        Revision revision;
        long generation = Long.MIN_VALUE;
        int checkedTick = Integer.MIN_VALUE;
        Optional<Set<ResearchKey>> completed = Optional.empty();
        boolean ioFailed;
        RitualResearch.Result result(ResearchKey key) {
            return completed.map(keys -> keys.contains(key) ? RitualResearch.Result.ALLOWED : RitualResearch.Result.DENIED)
                    .orElse(RitualResearch.Result.UNAVAILABLE);
        }
    }
    private static final class Cache {
        final LinkedHashMap<UUID, Entry> entries = new LinkedHashMap<>(16, .75F, true);
        final Set<UUID> reading = new HashSet<>();
        Entry entry(UUID id) {
            Entry result = entries.computeIfAbsent(id, ignored -> new Entry());
            if (entries.size() > MAX_PLAYERS) entries.pollFirstEntry();
            return result;
        }
    }
    static void register() {
        RitualResearch.register(CrucibleRitualAdapter.RESEARCH, RitualKnowledge::check);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.SaveToFile event) -> invalidate(event));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> invalidate(event));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> invalidate(event));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> CACHES.remove(event.getServer()));
    }
    private static void invalidate(PlayerEvent event) {
        MinecraftServer server = event.getEntity().getServer();
        if (server == null || !server.isSameThread()) return;
        Cache cache = CACHES.get(server);
        if (cache != null) cache.entries.remove(event.getEntity().getUUID());
    }
    private static RitualResearch.Result check(ServerLevel level, UUID id, String key) {
        ResearchKey required;
        try { required = ResearchKey.parse(key); }
        catch (IllegalArgumentException invalid) { return RitualResearch.Result.DENIED; }
        MinecraftServer server = level.getServer();
        if (!server.isSameThread()) return RitualResearch.Result.UNAVAILABLE;
        var catalog = ResearchApi.registry();
        if (catalog.get(required).isEmpty()) return RitualResearch.Result.UNAVAILABLE;
        var live = server.getPlayerList().getPlayer(id);
        if (live != null) return ResearchApi.isComplete(live, required) ? RitualResearch.Result.ALLOWED : RitualResearch.Result.DENIED;

        Cache cache = CACHES.computeIfAbsent(server, ignored -> new Cache());
        if (cache.reading.contains(id)) return RitualResearch.Result.UNAVAILABLE;
        Entry entry = cache.entry(id);
        int tick = server.getTickCount();
        if (entry.checkedTick == tick && entry.generation == catalog.generation()) return entry.result(required);
        // The integrated-server owner's data may come from level.dat, not playerdata. Only memoize it for this tick.
        boolean host = server.getSingleplayerProfile() != null && id.equals(server.getSingleplayerProfile().getId());
        try {
            Revision revision = host ? null : Revision.read(server, id);
            if (host || entry.generation != catalog.generation() || entry.ioFailed || !Objects.equals(entry.revision, revision)) {
                cache.reading.add(id);
                try {
                    var snapshot = PlayerKnowledgeApi.savedView(server, id).map(view -> Set.copyOf(view.completedResearch()));
                    // Loading invokes external events. A concurrently replaced record cannot authorize this payment.
                    entry.completed = host || revision.equals(Revision.read(server, id)) ? snapshot : Optional.empty();
                    entry.revision = entry.completed.isPresent() || snapshot.isEmpty() ? revision : null;
                } finally { cache.reading.remove(id); }
            }
            entry.ioFailed = false;
        } catch (IOException unavailable) {
            entry.completed = Optional.empty(); entry.revision = null;
            if (!entry.ioFailed) org.slf4j.LoggerFactory.getLogger(RitualKnowledge.class)
                    .warn("Cannot inspect offline ritual research record for {}", id, unavailable);
            entry.ioFailed = true;
        }
        entry.generation = catalog.generation(); entry.checkedTick = tick;
        return entry.result(required);
    }
    private RitualKnowledge() {}
}
