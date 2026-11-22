package dev.senluo.witchcraft.slash;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Audio has its own budget: suppressing overlapping voices never suppresses a paid cast or hit. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID)
public final class SlashSounds {
    private static final int CONTACT_INTERVAL = 3;
    private static final Map<ServerPlayer, Budget> BUDGETS = new WeakHashMap<>();
    private static final Map<ServerPlayer, Contact> PENDING = new HashMap<>();

    private static final class Budget {
        long release = Long.MIN_VALUE, contact = Long.MIN_VALUE, blocked = Long.MIN_VALUE;
        long blackRedRelease = Long.MIN_VALUE;
    }

    private record Contact(ServerLevel level, Vec3 point, boolean flash) {}

    private SlashSounds() {}

    public static void release(SlashEntity slash, ServerPlayer player) {
        Budget budget = BUDGETS.computeIfAbsent(player, p -> new Budget());
        long tick = player.server.getTickCount();
        boolean blackRed = slash.visual() == SlashVisual.BLACK_RED;
        // A preceding normal cast must not swallow the rare cue; repeated rare casts are still bounded.
        long previous = blackRed ? budget.blackRedRelease : budget.release;
        if (previous != Long.MIN_VALUE && tick - previous < 2) return;
        if (blackRed) budget.blackRedRelease = tick;
        budget.release = tick;
        slash.level().playSound(null, slash.getX(), slash.getY(), slash.getZ(), SlashContent.RELEASE.get(),
                SoundSource.PLAYERS, blackRed ? .78F : .62F,
                (blackRed ? .84F : .96F) + player.getRandom().nextFloat() * (blackRed ? .06F : .08F));
    }

    static void livingContact(SlashEntity slash, ServerPlayer caster, Vec3 point) {
        if (slash.level() instanceof ServerLevel level) {
            offer(level, caster, point, slash.visual() == SlashVisual.BLACK_RED);
        }
    }

    static void contact(SlashEntity slash, Vec3 point) {
        if (!(slash.level() instanceof ServerLevel level) || slash.ownerId() == null) return;
        ServerPlayer caster = level.getServer().getPlayerList().getPlayer(slash.ownerId());
        if (caster != null) offer(level, caster, point, false);
    }

    // Coalesce contacts until the end of this server tick. The first black flash wins the location.
    private static void offer(ServerLevel level, ServerPlayer caster, Vec3 point, boolean flash) {
        Contact current = PENDING.get(caster);
        if (current == null || flash && !current.flash()) PENDING.put(caster, new Contact(level, point, flash));
    }

    @SubscribeEvent
    public static void flush(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) return;
        long tick = event.getServer().getTickCount();
        // A mod listening to playSound may synchronously submit another contact.
        for (ServerPlayer caster : List.copyOf(PENDING.keySet())) {
            Contact contact = PENDING.get(caster);
            if (contact == null || contact.level().getServer() != event.getServer()) continue;
            Budget budget = BUDGETS.computeIfAbsent(caster, p -> new Budget());
            if (budget.contact != Long.MIN_VALUE && tick - budget.contact < CONTACT_INTERVAL) {
                // Hold at most one rare contact for the next free tick; ordinary voices expire immediately.
                if (!contact.flash()) PENDING.remove(caster);
                continue;
            }
            PENDING.remove(caster);
            budget.contact = tick;
            SoundEvent sound = contact.flash() ? SlashContent.BLACK_FLASH_CONTACT.get() : SlashContent.CONTACT.get();
            float pitch = .97F + caster.getRandom().nextFloat() * .06F;
            Vec3 at = contact.point();
            contact.level().playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, contact.flash() ? .76F : .5F, pitch);
        }
    }

    /** Called only for a failed hardness quote, never for a protection/event veto. */
    static void blocked(SlashEntity slash, Vec3 point, BlockState state, float hardness) {
        SoundEvent sound;
        if (state.is(Blocks.BEDROCK)) {
            sound = SlashContent.BLOCKED_BEDROCK.get();
        } else if (hardness >= SlashImpactCurve.STONE_HARDNESS) {
            sound = SlashContent.BLOCKED_HARDNESS.get(SlashImpactCurve.profileIndex(hardness)).get();
        } else {
            contact(slash, point);
            return;
        }
        if (!(slash.level() instanceof ServerLevel level) || slash.ownerId() == null) return;
        ServerPlayer caster = level.getServer().getPlayerList().getPlayer(slash.ownerId());
        if (caster == null) return;
        Budget budget = BUDGETS.computeIfAbsent(caster, p -> new Budget());
        long tick = caster.server.getTickCount();
        if (budget.blocked != Long.MIN_VALUE && tick - budget.blocked < 6) return;
        budget.blocked = tick;
        level.playSound(null, point.x, point.y, point.z, sound, SoundSource.PLAYERS, .58F,
                .97F + caster.getRandom().nextFloat() * .06F);
    }

    @SubscribeEvent
    public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PENDING.remove(player);
            BUDGETS.remove(player);
        }
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        PENDING.entrySet().removeIf(e -> e.getValue().level().getServer() == event.getServer());
    }
}
