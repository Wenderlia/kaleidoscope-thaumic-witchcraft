package dev.senluo.witchcraft.ritual;

import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;

/** Pure visual rules shared by inscription, chunk models and attached marks.
 * Shape matching deliberately does not use decorative design or orientation.
 */
public final class ChalkPattern {
    public static final int DESIGNS = 24;
    /** Initial stone-cut designs occupy the first twelve atlas slots. */
    public static final int CARVING_DESIGNS = 12;
    public static final int CARVING_ATLAS_DESIGNS = 36;
    public static final int TURNS = 4;
    public static final int ATLAS_COLUMNS = 8;
    public static final int ATLAS_ROWS = 84;

    private ChalkPattern() {}

    /** Sample on the server and persist on success; never sample during rendering. */
    public static int nextChalkDesign(RandomSource random, int previous) {
        return differentDesign(random, DESIGNS, previous);
    }

    public static int nextCarvingDesign(RandomSource random, int previous) {
        return CARVING_DESIGNS + differentDesign(random, DESIGNS, previous - CARVING_DESIGNS);
    }

    private static int differentDesign(RandomSource random, int count, int previous) {
        boolean exclude = previous >= 0 && previous < count;
        int chosen = random.nextInt(count - (exclude ? 1 : 0));
        return exclude && chosen >= previous ? chosen + 1 : chosen;
    }


    /** Clockwise quarter turns from north, captured once from the writer's yaw. */
    public static int rotationFor(Direction facing) {
        return switch (facing) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> throw new IllegalArgumentException("A surface inscription needs a horizontal facing");
        };
    }

    /** Native carving IDs stay stable; transferred chalk letters append to each wood bank. */
    public static int atlasCell(int material, int design, int rotation) {
        int offset = material == 0 ? 0 : DESIGNS+(material-1)*CARVING_ATLAS_DESIGNS;
        return (offset+design)*TURNS+rotation;
    }
}
