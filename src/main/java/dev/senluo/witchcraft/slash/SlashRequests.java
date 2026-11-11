package dev.senluo.witchcraft.slash;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.ritual.OwnershipRitual;
import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID)
public final class SlashRequests {
    public static final int HOLD_INTERVAL = 4;
    // A burst bound is abuse protection, not a gameplay cooldown. Distinct clicks may share a tick.
    private record Input(int sequence, long castTick, long packetTick, int packets) {}
    private static final Map<ServerPlayer, Input> INPUTS = new WeakHashMap<>();
    private static final Map<ServerLevel, Set<SlashEntity>> LOADED = new IdentityHashMap<>();

    public record Cast(int sequence, ResourceLocation dimension, boolean held) implements CustomPacketPayload {
        public static final Type<Cast> TYPE = new Type<>(SlashContent.id("cast_slash"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Cast> CODEC = StreamCodec.of(
                (buf, p) -> { buf.writeVarInt(p.sequence); buf.writeResourceLocation(p.dimension); buf.writeBoolean(p.held); },
                buf -> new Cast(buf.readVarInt(), buf.readResourceLocation(), buf.readBoolean()));
        @Override public Type<Cast> type() { return TYPE; }
    }

    public static boolean installed(ItemStack stack) {
        return ModList.get().isLoaded("thaumcraft") && dev.senluo.witchcraft.compat.thaumcraft.SlashFocus.installed(stack);
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(KaleidoscopeWitchcraft.NETWORK_VERSION).playToServer(Cast.TYPE, Cast.CODEC, (packet, context) ->
                context.enqueueWork(() -> { if (context.player() instanceof ServerPlayer player) accept(player, packet); }));
    }

    private static void accept(ServerPlayer player, Cast request) {
        long tick = player.server.getTickCount();
        Input old = INPUTS.get(player);
        if (request.sequence < 0 || old != null && request.sequence <= old.sequence) return;
        // Remember rejected input too: delayed/replayed packets cannot become a later paid cast.
        int packets = old != null && old.packetTick == tick ? old.packets + 1 : 1;
        long castTick = old == null ? Long.MIN_VALUE : old.castTick;
        INPUTS.put(player, new Input(request.sequence, castTick, tick, Math.min(33, packets)));
        if (packets > 32 || request.held && castTick != Long.MIN_VALUE && tick - castTick < HOLD_INTERVAL
                || !request.dimension.equals(player.level().dimension().location())
                || !player.isAlive() || player.isSpectator() || OwnershipRitual.frozen(player)
                || player.containerMenu != player.inventoryMenu || !installed(player.getMainHandItem())
                || dev.senluo.witchcraft.slash.drawing.SlashDrawing.active(player)) return;
        if (!room(player)) return;
        if (dev.senluo.witchcraft.compat.thaumcraft.SlashFocus.cast(player))
            INPUTS.put(player, new Input(request.sequence, tick, tick, packets));
    }

    public static boolean room(ServerPlayer player) {
        var loaded = LOADED.getOrDefault(player.serverLevel(), Set.of());
        if (!loaded.isEmpty()) loaded.removeIf(s -> s.isRemoved() || !s.isAddedToLevel());
        return loaded.size() < 512 && loaded.stream().filter(s -> player.getUUID().equals(s.ownerId())).count() < 128;
    }

    @SubscribeEvent public static void join(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof SlashEntity slash)
            LOADED.computeIfAbsent(level, k -> new HashSet<>()).add(slash);
    }
    @SubscribeEvent public static void leave(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof SlashEntity slash) {
            var set = LOADED.get(level); if (set != null) set.remove(slash);
        }
    }
    @SubscribeEvent public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) LOADED.remove(level);
    }
    @SubscribeEvent public static void trackLook(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!player.isAlive() || player.isSpectator() || OwnershipRitual.frozen(player)
                || player.containerMenu != player.inventoryMenu || player.isUsingItem()
                || !installed(player.getMainHandItem())) SlashGesture.clear(player);
        else SlashGesture.sample(player);
    }
    @SubscribeEvent public static void attack(AttackEntityEvent event) {
        if (installed(event.getEntity().getMainHandItem())) event.setCanceled(true);
    }
    @SubscribeEvent public static void mine(PlayerInteractEvent.LeftClickBlock event) {
        if (installed(event.getItemStack())) event.setCanceled(true);
    }
}
