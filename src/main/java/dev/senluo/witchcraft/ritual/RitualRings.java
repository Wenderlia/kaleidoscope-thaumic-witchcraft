package dev.senluo.witchcraft.ritual;

import java.util.List;
import java.util.Objects;

/** Physical ring geometry. Ritual occupancy belongs to the shared point ledger. */
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
        public int points() {
            int total = 0;
            for (Ring ring : DEFINITIONS) if (complete(ring)) total += ring.id;
            return total;
        }
        /** Observation upper bound, never permission to spend an unobserved ring. */
        public int possiblePoints() {
            int total = 0;
            for (Ring ring : DEFINITIONS) if (state(ring) != RitualShape.Match.INVALID) total += ring.id;
            return total;
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
    private RitualRings() {}

    public static RitualShape shape(int ring) {
        if (ring < SMALL || ring > LARGE) throw new IllegalArgumentException("Invalid ring size");
        return DEFINITIONS.get(ring - 1).shape;
    }
}
