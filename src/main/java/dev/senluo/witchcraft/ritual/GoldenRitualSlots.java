package dev.senluo.witchcraft.ritual;

import java.util.*;
import net.minecraft.server.level.ServerLevel;

/** Shared ring allocation, including fixed regional leases. */
public final class GoldenRitualSlots {
    public static final String PINNED_RING = "BearingRing";
    public static Map<UUID, Integer> allocate(ServerLevel level, RitualPlane plane,
                                             UUID prospective, int minimum) {
        return allocate(level, plane, prospective, null, minimum);
    }
    /** A paid untyped preparation can quote a larger ring without replacing its receipt. */
    public static Map<UUID, Integer> replacing(ServerLevel level, RitualPlane plane,
                                              UUID replacement, int minimum) {
        return allocate(level, plane, null, replacement, minimum);
    }
    public static List<StructuralRitualStore.Handle> sessions(ServerLevel level, RitualPlane plane) {
        if (plane == null) return List.of();
        var store = StructuralRitualStore.get(level);
        return store.atPlane(level.dimension().location(), plane.anchor(), handle -> {
            // Older/native vessel receipts locate their plane without altering paid terms.
            return level.hasChunkAt(handle.center().position()) && RitualCenters.site(level.getBlockEntity(handle.center().position()))
                    .filter(site -> site.plane() != null && site.plane().anchor().equals(plane.anchor())).isPresent();
        });
    }
    private static Map<UUID, Integer> allocate(ServerLevel level, RitualPlane plane,
                                              UUID prospective, UUID replacement, int minimum) {
        var store = StructuralRitualStore.get(level);
        List<RitualRings.Request> requests = new ArrayList<>();
        Map<UUID, Integer> pinned = new HashMap<>();
        for (var handle : sessions(level, plane)) {
            requests.add(new RitualRings.Request(handle.id(), handle.id().equals(replacement) ? minimum : handle.definition().minimumRing()));
            int ring = store.bearingRing(handle.id());
            if (ring >= handle.definition().minimumRing() && ring <= RitualRings.LARGE) pinned.put(handle.id(), ring);
        }
        if (prospective != null) requests.add(new RitualRings.Request(prospective, minimum));
        return RitualRings.allocate(plane.rings(), requests, pinned);
    }
    private GoldenRitualSlots() {}
}
