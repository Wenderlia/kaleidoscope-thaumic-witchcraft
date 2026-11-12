package dev.senluo.witchcraft.ritual;

import java.util.Arrays;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** The outermost complete ring encloses materials, independently of ritual-slot allocation. */
public final class RitualOfferingArea {
    private static final Footprint[] FOOTPRINTS = {
            new Footprint(RitualShape.SMALL), new Footprint(RitualShape.MEDIUM), new Footprint(RitualShape.LARGE)
    };
    private final BlockPos anchor;
    private final Footprint footprint;
    private final AABB bounds;

    private RitualOfferingArea(BlockPos anchor, int ring) {
        this.anchor = anchor.immutable();
        this.footprint = FOOTPRINTS[ring - 1];
        int radius = footprint.radius;
        double minY = anchor.getY() - RitualPlane.HEIGHT_RADIUS;
        double maxY = anchor.getY() + RitualPlane.HEIGHT_RADIUS + 1;
        bounds = new AABB(anchor.getX() - radius, minY, anchor.getZ() - radius,
                anchor.getX() + radius + 1, maxY, anchor.getZ() + radius + 1);
    }

    /** Uses the shared geometry cache; never loads chunks or treats occupied rings as absent. */
    public static Optional<RitualOfferingArea> outermost(ServerLevel level, BlockPos anchor) {
        return RitualPlane.at(level, anchor).map(RitualOfferingArea::outermost);
    }

    public static RitualOfferingArea outermost(RitualPlane plane) {
        return new RitualOfferingArea(plane.anchor(), plane.outermostRing());
    }

    /** Uses the fixed spatial snapshot; it makes no claim about current ring completeness. */
    public static RitualOfferingArea outermost(RitualSite.Footprint footprint) {
        return new RitualOfferingArea(footprint.anchor(), footprint.outermostRing());
    }

    /** Read-only footprint for a server-authorized presentation snapshot; no world scan. */
    public static RitualOfferingArea visualFootprint(BlockPos anchor, int ring) {
        if (ring < 1 || ring > 3) throw new IllegalArgumentException("Invalid ritual footprint");
        return new RitualOfferingArea(anchor, ring);
    }

    public AABB bounds() { return bounds; }

    public boolean containsHorizontal(Vec3 position) {
        if (position.x < bounds.minX || position.x >= bounds.maxX
                || position.z < bounds.minZ || position.z >= bounds.maxZ) return false;
        int x = Mth.floor(position.x) - anchor.getX();
        int row = Mth.floor(position.z) - anchor.getZ() + footprint.radius;
        return x >= footprint.left[row] && x <= footprint.right[row];
    }

    /** Fill between the two boundary marks in each row, including the marked blocks themselves. */
    private static final class Footprint {
        final int radius;
        final int[] left, right;
        Footprint(RitualShape shape) {
            radius = shape.marks().stream().mapToInt(pos -> Math.max(Math.abs(pos.getX()), Math.abs(pos.getZ())))
                    .max().orElseThrow();
            left = new int[2 * radius + 1]; right = new int[left.length];
            Arrays.fill(left, Integer.MAX_VALUE); Arrays.fill(right, Integer.MIN_VALUE);
            for (BlockPos mark : shape.marks()) {
                int row = mark.getZ() + radius;
                left[row] = Math.min(left[row], mark.getX());
                right[row] = Math.max(right[row], mark.getX());
            }
        }
    }
}
