package dev.senluo.witchcraft.slash;

import com.google.common.collect.HashMultimap;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import it.unimi.dsi.fastutil.longs.LongArraySet;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.border.BorderStatus;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkTicketLevelUpdatedEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Server-thread witnesses for blocked radial fronts. Notifications never query or load terrain. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID)
final class RadialChunkWait {
    private static final int FULL = ChunkLevel.byStatus(ChunkStatus.FULL);
    private static final Map<ServerLevel, HashMultimap<Long, RadialChunkWait>> WAITING = new IdentityHashMap<>();
    private final ServerLevel level;
    private final Vec3 center;
    private final double remaining;
    private final boolean phasing;
    private final double minX, maxX, minZ, maxZ;
    private final long firstChunk;
    private LongArraySet otherChunks;
    private boolean dirty;

    RadialChunkWait(ServerLevel level, Vec3 center, double remaining, boolean phasing, long firstChunk) {
        this.firstChunk = firstChunk;
        this.level = level;
        this.center = center;
        this.remaining = remaining;
        this.phasing = phasing;
        var border = level.getWorldBorder();
        minX = border.getMinX(); maxX = border.getMaxX();
        minZ = border.getMinZ(); maxZ = border.getMaxZ();
    }

    static boolean supported(ServerLevel level) {
        return level.getChunkSource().getClass() == ServerChunkCache.class
                && level.getWorldBorder().getStatus() == BorderStatus.STATIONARY;
    }

    boolean stillBlocked(ServerLevel current, Vec3 origin, double budget, boolean passesBlocks) {
        if (dirty || current != level || !center.equals(origin) || remaining != budget || phasing != passesBlocks)
            return false;
        var border = current.getWorldBorder();
        return border.getStatus() == BorderStatus.STATIONARY && minX == border.getMinX() && maxX == border.getMaxX()
                && minZ == border.getMinZ() && maxZ == border.getMaxZ();
    }

    void record(long missing) {
        if (firstChunk == missing) return;
        if (otherChunks == null) otherChunks = new LongArraySet();
        otherChunks.add(missing);
    }

    void register() {
        var index = WAITING.computeIfAbsent(level, ignored -> HashMultimap.create());
        index.put(firstChunk, this);
        if (otherChunks != null) otherChunks.forEach((long key) -> index.put(key, this));
    }

    void release() {
        var index = WAITING.get(level);
        if (index == null) return;
        index.remove(firstChunk, this);
        if (otherChunks != null) otherChunks.forEach((long key) -> index.remove(key, this));
        if (index.isEmpty()) WAITING.remove(level);
    }

    @SubscribeEvent
    public static void ticketChanged(ChunkTicketLevelUpdatedEvent event) {
        if (WAITING.isEmpty() || event.getOldTicketLevel() <= FULL || event.getNewTicketLevel() > FULL) return;
        var index = WAITING.get(event.getLevel());
        if (index != null && index.containsKey(event.getChunkPos()))
            for (var wait : index.get(event.getChunkPos())) wait.dirty = true;
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            var index = WAITING.remove(level);
            if (index != null) for (var wait : index.values()) wait.dirty = true;
        }
    }
}
