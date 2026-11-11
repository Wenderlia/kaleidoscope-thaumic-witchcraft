package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.ritual.*;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Matrix eligibility combines complete geometry with the shared rune-stabilization observation. */
public final class InfusionRings {
    private static final RuneStabilization.Stabilizer STABILIZERS = InfusionStabilizers::matches;
    private InfusionRings() {}
    static Optional<RitualPlane> find(ServerLevel level, BlockPos matrix) {
        RitualPlane.invalidate(level, matrix);
        return lookup(level, matrix);
    }
    public static Optional<RitualPlane> lookup(ServerLevel level, BlockPos matrix) {
        for (int i = 0; i <= RitualPlane.HEIGHT_RADIUS * 2; i++) {
            int dy = i == 0 ? 0 : (i % 2 == 1 ? -(i + 1) / 2 : i / 2);
            var plane = at(level, matrix.offset(0, dy, 0));
            if (plane.isPresent()) return plane;
        }
        return Optional.empty();
    }
    static Optional<RitualPlane> at(ServerLevel level, BlockPos anchor) {
        var rings = new RitualRings.Layout(match(level, anchor, RitualShape.SMALL),
                match(level, anchor, RitualShape.MEDIUM), match(level, anchor, RitualShape.LARGE));
        return rings.count() == 0 ? Optional.empty() : Optional.of(new RitualPlane(anchor, rings));
    }
    private static RitualShape.Match match(ServerLevel level, BlockPos anchor, RitualShape shape) {
        var geometry = shape.match(level, anchor);
        if (geometry != RitualShape.Match.VALID) return geometry;
        return switch (RuneStabilization.all(level, anchor, shape, STABILIZERS)) {
            case STABILIZED -> RitualShape.Match.VALID;
            case UNSTABILIZED -> RitualShape.Match.INVALID;
            case UNLOADED -> RitualShape.Match.UNLOADED;
        };
    }
}
