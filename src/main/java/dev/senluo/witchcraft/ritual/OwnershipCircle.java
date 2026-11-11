package dev.senluo.witchcraft.ritual;

import dev.senluo.witchcraft.time.LivingEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Bounded surface query using the current tree form's actual trunk axis. */
record OwnershipCircle(BlockPos center) {

    static OwnershipCircle find(ServerLevel level, LivingEntity tree) {
        return RitualCenters.observe(tree).flatMap(center -> {
                    RitualPlane.invalidate(level, center.axis());
                    return center.match(level, RitualShape.SMALL);
                })
                .map(OwnershipCircle::new).orElse(null);
    }

    boolean valid(ServerLevel level) {
        return RitualShape.SMALL.match(level, center) == RitualShape.Match.VALID;
    }

}
