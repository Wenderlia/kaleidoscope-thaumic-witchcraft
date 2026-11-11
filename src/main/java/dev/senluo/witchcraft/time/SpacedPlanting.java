package dev.senluo.witchcraft.time;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;

/** Complete the spaced pass before filling gaps; bounded work is resumed, never mistaken for absence. */
final class SpacedPlanting {
    private List<BlockPos> sites = List.of();
    private int cursor;
    private boolean dense;

    BlockPos find(List<BlockPos> current, Predicate<BlockPos> usable, Predicate<BlockPos> spaced) {
        if (sites != current) { sites = current; cursor = 0; dense = false; }
        for (int work = 0; work < 64 && !sites.isEmpty(); work++) {
            if (cursor >= sites.size()) {
                cursor = 0;
                if (dense) { dense = false; return null; }
                dense = true;
            }
            BlockPos pos = sites.get(cursor++);
            if (usable.test(pos) && (dense || spaced.test(pos))) {
                return pos;
            }
        }
        return null;
    }

    // A denied location is skipped on the next search; only an actual placement restarts spacing.
    void placed() { cursor = 0; dense = false; }
}
