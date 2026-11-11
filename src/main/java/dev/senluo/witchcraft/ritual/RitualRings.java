package dev.senluo.witchcraft.ritual;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Pure allocation policy. Requests arrive oldest first; one ritual claims one complete eligible ring. */
public final class RitualRings {
    public static final int SMALL = 1, MEDIUM = 2, LARGE = 3;

    /** Stable size IDs also appear in saved requirements. */
    public enum Ring {
        SMALL(RitualRings.SMALL, RitualShape.SMALL),
        MEDIUM(RitualRings.MEDIUM, RitualShape.MEDIUM),
        LARGE(RitualRings.LARGE, RitualShape.LARGE);

        private final int id;
        private final RitualShape shape;
        Ring(int id, RitualShape shape) {
            this.id = id;
            this.shape = shape;
        }
        public int id() { return id; }
        public RitualShape shape() { return shape; }
    }
    public static final List<Ring> DEFINITIONS = List.of(Ring.SMALL, Ring.MEDIUM, Ring.LARGE);

    /** World observations only. Occupancy and influence are derived separately, not persisted here. */
    public record Layout(RitualShape.Match small, RitualShape.Match medium, RitualShape.Match large) {
        public static final Layout NONE = new Layout(RitualShape.Match.INVALID, RitualShape.Match.INVALID, RitualShape.Match.INVALID);
        public Layout {
            Objects.requireNonNull(small); Objects.requireNonNull(medium); Objects.requireNonNull(large);
        }
        public RitualShape.Match state(Ring ring) {
            return switch (ring) { case SMALL -> small; case MEDIUM -> medium; case LARGE -> large; };
        }
        public boolean complete(Ring ring) { return state(ring) == RitualShape.Match.VALID; }
        public boolean fullyKnown() {
            return small != RitualShape.Match.UNLOADED && medium != RitualShape.Match.UNLOADED && large != RitualShape.Match.UNLOADED;
        }
        public int completeMask() {
            int mask = 0;
            for (Ring ring : DEFINITIONS) if (complete(ring)) mask |= 1 << (ring.id - 1);
            return mask;
        }
        public int count() {
            int result = 0;
            for (Ring ring : DEFINITIONS) if (complete(ring)) result++;
            return result;
        }
        /** Zero means no known complete ring; never use this as an influence tier. */
        public int outermostRing() {
            int result = 0;
            for (Ring ring : DEFINITIONS) if (complete(ring)) result = ring.id;
            return result;
        }
    }
    public record Request(UUID id, int minimum) {
        public Request {
            java.util.Objects.requireNonNull(id);
            if (minimum < SMALL || minimum > LARGE) throw new IllegalArgumentException("Invalid ring requirement");
        }
    }
    private RitualRings() {}

    /** Only known complete rings can be claimed. An unloaded ring cannot provide a free slot. */
    public static Map<UUID, Integer> allocate(Layout layout, List<Request> requests) {
        return allocate(layout, requests, Map.of());
    }

    /** Regional rites bind their actual bearing ring. They cannot silently move to another ring. */
    public static Map<UUID, Integer> allocate(Layout layout, List<Request> requests, Map<UUID, Integer> pinned) {
        Objects.requireNonNull(layout);
        Map<UUID, Integer> result = new LinkedHashMap<>();
        var seen = new HashSet<UUID>();
        int claimed = 0;
        for (Request request : requests) {
            Integer id = pinned.get(request.id);
            if (id == null || id < request.minimum || id > LARGE) continue;
            int bit = 1 << (id - 1);
            if ((claimed & bit) != 0) continue;
            claimed |= bit;
            if (layout.complete(DEFINITIONS.get(id - 1))) result.put(request.id, id);
        }
        for (Request request : requests) {
            if (!seen.add(request.id)) throw new IllegalArgumentException("Duplicate ring request");
            if (pinned.containsKey(request.id)) continue;
            for (Ring ring : DEFINITIONS) {
                int bit = 1 << (ring.id - 1);
                if (ring.id >= request.minimum && layout.complete(ring) && (claimed & bit) == 0) {
                    result.put(request.id, ring.id); claimed |= bit; break;
                }
            }
        }
        return Map.copyOf(result);
    }

    public static RitualShape shape(int ring) {
        if (ring < SMALL || ring > LARGE) throw new IllegalArgumentException("Invalid ring size");
        return DEFINITIONS.get(ring - 1).shape;
    }
}
