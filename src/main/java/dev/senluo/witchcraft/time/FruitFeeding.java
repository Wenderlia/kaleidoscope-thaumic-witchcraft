package dev.senluo.witchcraft.time;

import net.minecraft.server.level.ServerLevel;

/** Executable restoration has first claim. Production then shares one real, unlocked wallet. */
public final class FruitFeeding {
    public static final int RESTORATION_SECONDS = 30;
    private FruitFeeding() {}

    public static long reserve(Colony group) {
        if (group.members.isEmpty() || !(group.members.getFirst().getLevel() instanceof ServerLevel level)) return 0;
        long now = level.getGameTime();
        if (group.reserveTick != Long.MIN_VALUE && now >= group.reserveTick && now - group.reserveTick < 20)
            return group.reserveAmount;
        long reserve = 0;
        for (LivingEntity member : group.members) {
            if (member.tree()) reserve += TemporalWorld.get(level).trees.restorationReserve(member);
        }
        group.reserveTick = now;
        group.reserveAmount = reserve;
        return group.reserveAmount;
    }

    public static long available(Colony group) {
        return Math.max(0, group.spendable() - reserve(group));
    }

}
