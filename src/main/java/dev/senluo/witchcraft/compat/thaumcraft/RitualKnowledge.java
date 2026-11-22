package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.ritual.RitualResearch;
import dev.tc4port.thaumcraft.api.player.PlayerKnowledgeApi;
import dev.tc4port.thaumcraft.api.research.ResearchApi;
import dev.tc4port.thaumcraft.api.research.ResearchKey;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Reads the effective host view at the requested decision boundary. */
final class RitualKnowledge {
    private record Reader(MinecraftServer server, UUID player) {}
    // The detached-player load fires external events before TC enters its own read-event guard.
    private static final ThreadLocal<Set<Reader>> READING = ThreadLocal.withInitial(HashSet::new);

    static void register() {
        RitualResearch.register(CrucibleRitualAdapter.RESEARCH, RitualKnowledge::check);
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

        var reading = READING.get();
        var reader = new Reader(server, id);
        if (!reading.add(reader)) return RitualResearch.Result.UNAVAILABLE;
        try {
            return PlayerKnowledgeApi.savedView(server, id)
                    .map(view -> view.completedResearch().contains(required)
                            ? RitualResearch.Result.ALLOWED : RitualResearch.Result.DENIED)
                    .orElse(RitualResearch.Result.UNAVAILABLE);
        } finally {
            reading.remove(reader);
            if (reading.isEmpty()) READING.remove();
        }
    }
    private RitualKnowledge() {}
}
