package dev.senluo.witchcraft.slash;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.registries.*;

/** Server-only temporary chance. Deliberately not copyOnDeath and not part of the permanent geas ledger. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID)
public final class SlashRhythm {
    private static final DeferredRegister<AttachmentType<?>> TYPES = DeferredRegister.create(
            NeoForgeRegistries.ATTACHMENT_TYPES, KaleidoscopeWitchcraft.MOD_ID);
    private static final DeferredHolder<AttachmentType<?>, AttachmentType<CompoundTag>> DATA = TYPES.register(
            "slash_critical_rhythm", () -> AttachmentType.builder(() -> new CompoundTag()).serialize(CompoundTag.CODEC).build());

    public static void register(IEventBus bus) { TYPES.register(bus); }
    private static long clock(ServerPlayer player) { return player.server.overworld().getGameTime(); }

    private static CriticalRhythm read(ServerPlayer player) {
        if (!player.hasData(DATA.get())) return CriticalRhythm.EMPTY;
        CompoundTag tag = player.getData(DATA.get());
        // Unknown/malformed records are retained, but never grant chance or get overwritten by a cast.
        if (tag.getInt("Version") == 1) return CriticalRhythm.EMPTY; // Retire the legacy held bonus.
        if (tag.getInt("Version") != 2 || !tag.contains("Bonus", Tag.TAG_DOUBLE)
                || !tag.contains("LastMatch", Tag.TAG_LONG)
                || !tag.contains("LastCast", Tag.TAG_LONG) || !tag.contains("Period", Tag.TAG_LONG)) return null;
        try { return new CriticalRhythm(tag.getDouble("Bonus"), tag.getLong("LastMatch"),
                tag.getLong("LastCast"), tag.getLong("Period")); }
        catch (IllegalArgumentException invalid) { return null; }
    }

    private static void write(ServerPlayer player, CriticalRhythm state) {
        if (state.equals(CriticalRhythm.EMPTY)) { player.removeData(DATA.get()); return; }
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", 2); tag.putDouble("Bonus", state.bonus());
        tag.putLong("LastMatch", state.lastMatch()); tag.putLong("LastCast", state.lastCast()); tag.putLong("Period", state.period());
        player.setData(DATA.get(), tag);
    }

    public static double chance(ServerPlayer player, double base) {
        CriticalRhythm state = read(player);
        return !player.isAlive() || state == null ? base : state.chance(base, clock(player));
    }

    public static void committed(ServerPlayer player, double base) {
        if (!player.isAlive()) { clear(player); return; }
        CriticalRhythm state = read(player);
        if (state != null) write(player, state.committed(clock(player), base));
    }

    public static void clear(ServerPlayer player) { if (player.hasData(DATA.get())) player.removeData(DATA.get()); }

    @SubscribeEvent public static void cloned(PlayerEvent.Clone event) {
        if (!event.isWasDeath()) return;
        if (event.getEntity() instanceof ServerPlayer player) clear(player);
        if (event.getOriginal() instanceof ServerPlayer original) clear(original);
    }

    @SubscribeEvent public static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) reconnect(player);
    }
    @SubscribeEvent public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) reconnect(player);
    }
    private static void reconnect(ServerPlayer player) {
        if (!player.isAlive()) { clear(player); return; }
        CriticalRhythm state = read(player);
        if (state != null) write(player, state.reconnect(clock(player)));
    }

    // Covers a dead player saving/disconnecting before respawn. No scan and no per-tick NBT writes for living players.
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && !player.isAlive()) clear(player);
    }

    private SlashRhythm() {}
}
