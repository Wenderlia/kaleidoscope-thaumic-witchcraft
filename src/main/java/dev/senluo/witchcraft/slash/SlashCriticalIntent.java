package dev.senluo.witchcraft.slash;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Ephemeral, server-owned intent. No packets, saved flags or player-facing hint text. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID)
public final class SlashCriticalIntent {
    private static final Map<ServerPlayer, Pending> PENDING = new WeakHashMap<>();
    private static final class Pending {
        final long tick;
        boolean reserved;
        Pending(long tick) { this.tick = tick; }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void chat(ServerChatEvent event) {
        if (event.isCanceled() || !event.getRawText().strip().equals("解")) return;
        ServerPlayer player = event.getPlayer();
        if (player.isFakePlayer()) return;
        Runnable remember = () -> {
            if (player.isAlive() && !player.isSpectator() && !player.hasDisconnected()
                    && !SlashRules.bindings(player).inverseChanting())
                PENDING.put(player, new Pending(player.server.getTickCount()));
        };
        if (player.server.isSameThread()) remember.run(); else player.server.execute(remember);
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) { PENDING.remove(event.getEntity()); }

    /** Reserve across payment callbacks so a nested cast cannot use the same intent twice. */
    public static Lease reserve(ServerPlayer player) {
        SlashRules rules = SlashRules.bindings(player);
        if (rules.inverseChanting()) { PENDING.remove(player); return null; }
        return reserve(player, rules.chantTicks());
    }

    public static boolean available(ServerPlayer player, int ticks) {
        Pending pending = PENDING.get(player);
        if (pending == null || pending.reserved) return false;
        long elapsed = (long) player.server.getTickCount() - pending.tick;
        return elapsed >= 0 && elapsed <= ticks;
    }

    private static Lease reserve(ServerPlayer player, int ticks) {
        Pending pending = PENDING.get(player);
        if (pending == null) return null;
        long elapsed = (long) player.server.getTickCount() - pending.tick;
        if (elapsed < 0 || elapsed > ticks) { PENDING.remove(player); return null; }
        if (pending.reserved) return null;
        pending.reserved = true;
        return new Lease(player, pending);
    }

    public static final class Lease implements AutoCloseable {
        private final ServerPlayer player;
        private final Pending pending;
        private boolean committed;
        private Lease(ServerPlayer player, Pending pending) { this.player = player; this.pending = pending; }
        public void commit() {
            committed = true;
            // A callback may have recorded a NEW chat intent; never erase its replacement.
            if (PENDING.get(player) == pending) PENDING.remove(player);
        }
        @Override public void close() {
            if (!committed) pending.reserved = false;
        }
    }

    private SlashCriticalIntent() {}
}
