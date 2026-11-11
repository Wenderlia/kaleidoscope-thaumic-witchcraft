package dev.senluo.witchcraft.ritual;

import com.mojang.authlib.GameProfile;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerSetSpawnEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Rebuildable bed lookup; player respawn data remains authoritative. No player entities are loaded. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID)
public final class TaglockBeds extends SavedData {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaglockBeds.class);
    private static final Factory<TaglockBeds> FACTORY = new Factory<>(TaglockBeds::new, TaglockBeds::load);
    private static final String FILE = "witchcraft_taglock_beds";
    private record Bed(ResourceLocation dimension, BlockPos pos) {}

    public record Owner(UUID id, String name) {
        public GameProfile profile() {
            return new GameProfile(id, name.isEmpty() ? null : name);
        }

        public TaglockSample sample() {
            return new TaglockSample(TaglockSample.CURRENT_VERSION, id,
                    ResourceLocation.withDefaultNamespace("player"), name.isEmpty() ? "Unknown player" : name, !name.isEmpty());
        }
    }

    private record Entry(Owner owner, Bed bed) {}
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final Map<Bed, Set<UUID>> byBed = new HashMap<>();
    private final Set<UUID> pending = new HashSet<>();
    private final Set<UUID> seenFiles = new HashSet<>();
    private final Set<UUID> liveUpdates = new HashSet<>();
    private DirectoryStream<Path> directory;
    private Iterator<Path> files;
    private boolean started;
    private boolean ready;
    private boolean failed;

    public static TaglockBeds get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE);
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        get(event.getServer()).begin(event.getServer());
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        get(event.getServer()).closeDirectory();
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        TaglockBeds beds = get(event.getServer());
        beds.flushPending(event.getServer());
        beds.importNext(event.getServer());
    }

    @SubscribeEvent
    public static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) get(player.serverLevel().getServer()).remember(player);
    }

    @SubscribeEvent
    public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) get(player.serverLevel().getServer()).remember(player);
    }

    @SubscribeEvent
    public static void respawned(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) get(player.serverLevel().getServer()).remember(player);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void spawnChanged(PlayerSetSpawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            get(player.serverLevel().getServer()).pending.add(player.getUUID());
    }

    public boolean ready() {
        return ready;
    }

    public boolean failed() {
        return failed;
    }

    public List<Owner> owners(ServerLevel level, BlockPos clicked) {
        flushPending(level.getServer());
        Set<UUID> ids = new HashSet<>();
        collect(ids, new Bed(level.dimension().location(), clicked));
        var state = level.getBlockState(clicked);
        if (state.hasProperty(BedBlock.PART) && state.hasProperty(BedBlock.FACING)) {
            BlockPos other = clicked.relative(BedBlock.getConnectedDirection(state));
            if (level.hasChunkAt(other)) {
                var otherState = level.getBlockState(other);
                if (otherState.is(state.getBlock()) && otherState.hasProperty(BedBlock.PART)
                        && otherState.getValue(BedBlock.PART) != state.getValue(BedBlock.PART))
                    collect(ids, new Bed(level.dimension().location(), other));
            }
        }
        List<Owner> result = new ArrayList<>(ids.size());
        for (UUID id : ids) result.add(entries.get(id).owner);
        result.sort(Comparator.comparing(Owner::name).thenComparing(owner -> owner.id.toString()));
        return result;
    }

    private void collect(Set<UUID> result, Bed bed) {
        Set<UUID> ids = byBed.get(bed);
        if (ids != null) result.addAll(ids);
    }

    private void remember(ServerPlayer player) {
        UUID id = player.getUUID();
        pending.remove(id);
        if (!ready) liveUpdates.add(id);
        BlockPos pos = player.getRespawnPosition();
        replace(id, pos == null ? null : new Entry(new Owner(id, player.getGameProfile().getName()),
                new Bed(player.getRespawnDimension().location(), pos.immutable())));
    }

    private void flushPending(MinecraftServer server) {
        if (pending.isEmpty()) return;
        // The spawn event runs before the vanilla setter. Read the final state after it
        // finishes, including cancellation by another listener and respawn-point resets.
        for (UUID id : List.copyOf(pending)) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) remember(player);
        }
        pending.clear();
    }

    private void replace(UUID id, Entry next) {
        Entry previous = entries.get(id);
        if (java.util.Objects.equals(previous, next)) return;
        if (previous != null) {
            Set<UUID> ids = byBed.get(previous.bed);
            ids.remove(id);
            if (ids.isEmpty()) byBed.remove(previous.bed);
            entries.remove(id);
        }
        if (next != null) {
            entries.put(id, next);
            byBed.computeIfAbsent(next.bed, ignored -> new HashSet<>()).add(id);
        }
        setDirty();
    }

    private void begin(MinecraftServer server) {
        if (started) return;
        started = true;
        Path path = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        try {
            directory = Files.newDirectoryStream(path, "*.dat");
            files = directory.iterator();
        } catch (IOException | SecurityException exception) {
            failed = true;
            LOGGER.warn("Could not open player data for the taglock bed index", exception);
            closeDirectory();
        }
    }

    private void importNext(MinecraftServer server) {
        if (!started || ready || failed || files == null) return;
        try {
            // One file per native tick at startup; selecting a bed never scans playerdata.
            if (!files.hasNext()) {
                finishImport();
                return;
            }
            Path path = files.next();
            String filename = path.getFileName().toString();
            UUID id;
            try {
                id = UUID.fromString(filename.substring(0, filename.length() - 4));
            } catch (IllegalArgumentException exception) {
                return;
            }
            if (!filename.equalsIgnoreCase(id + ".dat") || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return;
            seenFiles.add(id);
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online != null) remember(online);
            else if (!liveUpdates.contains(id)) importPlayer(server, id, path);
        } catch (RuntimeException exception) {
            failed = true;
            LOGGER.warn("Could not enumerate player data for the taglock bed index", exception);
            closeDirectory();
        }
    }

    private void importPlayer(MinecraftServer server, UUID id, Path path) {
        CollectFields fields = new CollectFields(
                new FieldSelector(IntArrayTag.TYPE, "UUID"),
                new FieldSelector(IntTag.TYPE, "SpawnX"), new FieldSelector(IntTag.TYPE, "SpawnY"),
                new FieldSelector(IntTag.TYPE, "SpawnZ"), new FieldSelector(StringTag.TYPE, "SpawnDimension"));
        try {
            NbtIo.parseCompressed(path, fields, NbtAccounter.create(16L * 1024 * 1024));
            if (!(fields.getResult() instanceof CompoundTag tag)) throw new IOException("Player root is not a compound");
            if (tag.hasUUID("UUID") && !id.equals(tag.getUUID("UUID"))) throw new IOException("Player UUID does not match filename");
            if (!tag.contains("SpawnX", Tag.TAG_INT) || !tag.contains("SpawnY", Tag.TAG_INT) || !tag.contains("SpawnZ", Tag.TAG_INT)) {
                replace(id, null);
                return;
            }
            String dimensionName = tag.contains("SpawnDimension", Tag.TAG_STRING)
                    ? tag.getString("SpawnDimension") : Level.OVERWORLD.location().toString();
            ResourceLocation dimension = dimensionName.length() <= 256 ? ResourceLocation.tryParse(dimensionName) : null;
            if (dimension == null) throw new IOException("Invalid respawn dimension");
            String name = entries.containsKey(id) ? entries.get(id).owner.name : "";
            var cache = server.getProfileCache();
            if (cache != null) name = cache.get(id).map(GameProfile::getName).orElse(name);
            replace(id, new Entry(new Owner(id, validName(name) ? name : ""),
                    new Bed(dimension, new BlockPos(tag.getInt("SpawnX"), tag.getInt("SpawnY"), tag.getInt("SpawnZ")))));
        } catch (IOException | RuntimeException exception) {
            // A damaged/oversized native record must not authorize a stale indexed bed.
            replace(id, null);
            LOGGER.warn("Could not import taglock bed for player {}: {}", id, exception.toString());
        }
    }

    private void finishImport() {
        for (UUID id : List.copyOf(entries.keySet())) {
            if (!seenFiles.contains(id) && !liveUpdates.contains(id)) replace(id, null);
        }
        seenFiles.clear();
        liveUpdates.clear();
        closeDirectory();
        ready = true;
    }

    private void closeDirectory() {
        files = null;
        if (directory == null) return;
        try {
            directory.close();
        } catch (IOException exception) {
            LOGGER.warn("Could not close the taglock player-data directory", exception);
        }
        directory = null;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Entry entry : entries.values()) {
            CompoundTag value = new CompoundTag();
            value.putUUID("id", entry.owner.id);
            value.putString("name", entry.owner.name);
            value.putString("dimension", entry.bed.dimension.toString());
            value.putLong("pos", entry.bed.pos.asLong());
            list.add(value);
        }
        tag.put("owners", list);
        return tag;
    }

    private static TaglockBeds load(CompoundTag tag, HolderLookup.Provider registries) {
        TaglockBeds result = new TaglockBeds();
        ListTag list = tag.getList("owners", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.hasUUID("id") || !entry.contains("pos", Tag.TAG_LONG)) continue;
            String name = entry.getString("name");
            String dimensionName = entry.getString("dimension");
            ResourceLocation dimension = dimensionName.length() <= 256 ? ResourceLocation.tryParse(dimensionName) : null;
            if (!validName(name) || dimension == null) continue;
            UUID id = entry.getUUID("id");
            result.replace(id, new Entry(new Owner(id, name), new Bed(dimension, BlockPos.of(entry.getLong("pos")))));
        }
        result.setDirty(false);
        return result;
    }

    private static boolean validName(String name) {
        return name != null && (name.isEmpty() || !name.isBlank()) && name.length() <= 128
                && name.chars().noneMatch(c -> Character.isISOControl(c) || c == '§');
    }
}
