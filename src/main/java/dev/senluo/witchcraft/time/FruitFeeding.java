package dev.senluo.witchcraft.time;

import net.minecraft.server.level.ServerLevel;

/** Executable restoration has first claim. Production then shares one real, unlocked wallet. */
public final class FruitFeeding {
    public static final int RESTORATION_SECONDS = 30;
    private FruitFeeding() {}

    public static long reserve(Colony group) {
        if (group.members.isEmpty() || !(group.members.getFirst().getLevel() instanceof ServerLevel level)) return 0;
        TreeLogic trees = TemporalWorld.get(level).trees;
        long reserve = 0;
        for (LivingEntity member : group.members) {
            if (member.tree()) reserve += trees.restorationReserve(member);
        }
        return reserve;
    }

    public static long available(Colony group) {
        return Math.max(0, group.spendable() - reserve(group));
    }

}
