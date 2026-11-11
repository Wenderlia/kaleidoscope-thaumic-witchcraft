package dev.senluo.witchcraft.ritual;

import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;

/** A loss policy, independent of item class, slot, capacity, and storage representation.
 * Adapters call this once at the actual positive wear debit, AFTER their native modifiers.
 * It is not a repair, a maximum-capacity modifier, or a generic energy discount.
 */
public final class DurabilityLoss {
    public enum Policy {
        NORMAL, HALF;

        /** For custom continuous durability. Previewing this value has no side effects. */
        public double continuous(double loss) {
            if (!Double.isFinite(loss) || loss < 0) throw new IllegalArgumentException("Invalid durability loss");
            return this == HALF ? loss * .5 : loss;
        }

        /** For indivisible durability units: constant-time unbiased rounding, no saved remainder.
         * One point is waived with 50% probability; two points always cost one.
         * Call only on commit, not while simulating or drawing a tooltip.
         */
        public int integral(int loss, RandomSource random) {
            if (loss <= 0 || this == NORMAL) return loss;
            return loss / 2 + ((loss & 1) != 0 && random.nextBoolean() ? 1 : 0);
        }
    }

    public static Policy forBearer(LivingEntity bearer) {
        return bearer instanceof ServerPlayer player && !player.isFakePlayer() && GeasBindings.has(player, GeasBindings.RESTRAINT)
                ? Policy.HALF : Policy.NORMAL;
    }

    /** For a custom durability store that carries a reliable owner UUID, including offline owners.
     * The caller supplies provenance; this does not scan inventories or guess by nearby players.
     */
    public static Policy forOwner(MinecraftServer server, UUID owner) {
        return owner != null && GeasBindings.has(server, owner, GeasBindings.RESTRAINT) ? Policy.HALF : Policy.NORMAL;
    }

    private DurabilityLoss() {}
}
