package dev.senluo.witchcraft.slash;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Audio has its own budget: suppressing overlapping voices never suppresses a paid cast or hit. */
public final class SlashSounds {
    private static final Map<ServerPlayer, Budget> BUDGETS = new WeakHashMap<>();
    private static final class Budget {
        long release = Long.MIN_VALUE, contact = Long.MIN_VALUE, blocked = Long.MIN_VALUE;
        long blackRedRelease = Long.MIN_VALUE;
    }
    private SlashSounds() {}

    public static void release(SlashEntity slash, ServerPlayer player) {
        Budget budget = BUDGETS.computeIfAbsent(player, p -> new Budget());
        long tick = player.server.getTickCount();
        boolean blackRed = slash.visual() == SlashVisual.BLACK_RED;
        // A preceding normal cast must not swallow the rare cue; repeated rare casts are still bounded.
        long previous = blackRed ? budget.blackRedRelease : budget.release;
        if (previous != Long.MIN_VALUE && tick - previous < 2) return;
        if (blackRed) budget.blackRedRelease = tick;
        // Give the cue the same quiet interval before the next normal release.
        budget.release = tick;
        slash.level().playSound(null, slash.getX(), slash.getY(), slash.getZ(), SlashContent.RELEASE.get(),
                SoundSource.PLAYERS, blackRed ? .78F : .62F,
                (blackRed ? .84F : .96F) + player.getRandom().nextFloat() * (blackRed ? .06F : .08F));
    }

    static void contact(SlashEntity slash, Vec3 point) {
        playContact(slash, point, SlashContent.CONTACT.get(), false);
    }

    /** Called only for a failed hardness quote, never for a protection/event veto. */
    static void blocked(SlashEntity slash, Vec3 point, BlockState state, float hardness) {
        if (state.is(Blocks.BEDROCK)) {
            playContact(slash, point, SlashContent.BLOCKED_BEDROCK.get(), true);
        } else if (hardness >= SlashImpactCurve.STONE_HARDNESS) { // Destroy time, not blast resistance.
            playContact(slash, point,
                    SlashContent.BLOCKED_HARDNESS.get(SlashImpactCurve.profileIndex(hardness)).get(), true);
        } else {
            // Softer blocks and other negative-hardness blocks have no new material cue yet.
            contact(slash, point);
        }
    }

    private static void playContact(SlashEntity slash, Vec3 point, SoundEvent sound, boolean blocked) {
        if (!(slash.level() instanceof net.minecraft.server.level.ServerLevel level) || slash.ownerId() == null) return;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(slash.ownerId());
        if (player == null) return;
        Budget budget = BUDGETS.computeIfAbsent(player, p -> new Budget());
        long tick = player.server.getTickCount();
        long previous = blocked ? budget.blocked : budget.contact;
        if (previous != Long.MIN_VALUE && tick - previous < (blocked ? 6 : 3)) return;
        if (blocked) budget.blocked = tick;
        else budget.contact = tick;
        level.playSound(null, point.x, point.y, point.z, sound, SoundSource.PLAYERS, blocked ? .58F : .5F,
                .97F + player.getRandom().nextFloat() * .06F);
    }
}
