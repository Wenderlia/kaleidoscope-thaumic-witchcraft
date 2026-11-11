package dev.senluo.witchcraft.slash.drawing;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.ritual.OwnershipRitual;
import dev.senluo.witchcraft.slash.*;
import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** One server-owned, unsaved draft per player. No uploaded geometry, duration, power or hit claims. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID)
public final class SlashDrawing {
    public static final int RELEASE = 0, CANCEL = 1, KEEP_ALIVE = 2, CHAT_LATCH = 3;
    private static final Map<ServerPlayer, Session> SESSIONS = new WeakHashMap<>();
    private static int nextId;
    private static final class Session {
        final int id = ++nextId;
        final InteractionHand hand;
        final ItemStack wand;
        final Object focus;
        final long catalogGeneration;
        final ResourceLocation dimension;
        final StrokeSphere sphere;
        final StrokeTrace trace = new StrokeTrace();
        long heartbeat, stopped = -1, sampled = -1;
        boolean latched;
        Session(ServerPlayer p, InteractionHand hand) {
            this.hand = hand;
            wand = p.getItemInHand(hand); focus = dev.senluo.witchcraft.compat.thaumcraft.SlashFocus.state(wand);
            catalogGeneration = dev.senluo.witchcraft.compat.thaumcraft.SlashFocus.catalogGeneration();
            dimension = p.level().dimension().location(); sphere = StrokeSphere.facing(p.getLookAngle());
            heartbeat = p.server.getTickCount();
        }
        boolean matches(ServerPlayer p) {
            return p.getItemInHand(hand) == wand && dimension.equals(p.level().dimension().location())
                    && catalogGeneration == dev.senluo.witchcraft.compat.thaumcraft.SlashFocus.catalogGeneration()
                    && Objects.equals(focus, dev.senluo.witchcraft.compat.thaumcraft.SlashFocus.state(wand));
        }
    }
    public record Control(int id, ResourceLocation dimension, int action) implements CustomPacketPayload {
        public static final Type<Control> TYPE = new Type<>(SlashContent.id("drawing_control"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Control> CODEC = StreamCodec.of(
                (b,p) -> { b.writeInt(p.id); b.writeResourceLocation(p.dimension); b.writeByte(p.action); },
                b -> new Control(b.readInt(), b.readResourceLocation(), b.readUnsignedByte()));
        @Override public Type<Control> type() { return TYPE; }
    }
    public record Status(int id, ResourceLocation dimension, boolean open, InteractionHand hand, Vec3 forward) implements CustomPacketPayload {
        public static final Type<Status> TYPE = new Type<>(SlashContent.id("drawing_status"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Status> CODEC = StreamCodec.of(
                (b,p) -> { b.writeInt(p.id); b.writeResourceLocation(p.dimension); b.writeBoolean(p.open); b.writeEnum(p.hand);
                    b.writeFloat((float)p.forward.x); b.writeFloat((float)p.forward.y); b.writeFloat((float)p.forward.z); },
                b -> new Status(b.readInt(), b.readResourceLocation(), b.readBoolean(), b.readEnum(InteractionHand.class), new Vec3(b.readFloat(), b.readFloat(), b.readFloat())));
        @Override public Type<Status> type() { return TYPE; }
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(KaleidoscopeWitchcraft.NETWORK_VERSION);
        registrar.playToServer(Control.TYPE, Control.CODEC, (packet, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) accept(player, packet);
        }));
        registrar.playToClient(Status.TYPE, Status.CODEC, (packet, context) -> context.enqueueWork(() ->
                dev.senluo.witchcraft.client.SlashDrawingClient.status(packet)));
    }
    public static boolean eligible(ServerPlayer p, InteractionHand hand) {
        return p.isAlive() && !p.isSpectator() && !OwnershipRitual.frozen(p)
                && p.containerMenu == p.inventoryMenu && SlashRequests.installed(p.getItemInHand(hand))
                && (!p.isUsingItem() || p.getUsedItemHand() == hand && p.getUseItem() == p.getItemInHand(hand));
    }
    public static boolean active(ServerPlayer p) { return SESSIONS.containsKey(p); }
    public static boolean begin(ServerPlayer p, InteractionHand hand) {
        if (!eligible(p, hand) || active(p)) return false;
        Session session = new Session(p, hand); SESSIONS.put(p, session);
        PacketDistributor.sendToPlayer(p, new Status(session.id, session.dimension, true, hand, session.sphere.forward()));
        return true;
    }
    public static boolean active(ServerPlayer p, InteractionHand hand) {
        Session s = SESSIONS.get(p);
        return s != null && s.hand == hand;
    }
    public static void stopped(ServerPlayer p, InteractionHand hand) {
        Session s = SESSIONS.get(p);
        if (s != null && s.hand == hand && !s.latched && s.stopped < 0) s.stopped = p.server.getTickCount();
    }
    private static void close(ServerPlayer p, Session s) {
        if (!SESSIONS.remove(p, s)) return;
        PacketDistributor.sendToPlayer(p, new Status(s.id, s.dimension, false, s.hand, s.sphere.forward()));
    }
    private static void sample(ServerPlayer p, Session s) {
        long tick = p.server.getTickCount();
        if (s.sampled == tick || s.trace.ticks() >= StrokeTrace.MAX_TICKS) return;
        s.sampled = tick;
        s.trace.sample(s.sphere.project(p.getLookAngle()));
    }
    private static void accept(ServerPlayer p, Control request) {
        Session s = SESSIONS.get(p);
        if (s == null || s.id != request.id || !s.dimension.equals(request.dimension) || request.action > CHAT_LATCH) return;
        if (request.action == CHAT_LATCH) {
            if (eligible(p, s.hand) && s.matches(p)) {
                s.latched = true; s.stopped = -1; s.heartbeat = p.server.getTickCount();
            }
            return;
        }
        if (request.action == KEEP_ALIVE) { s.heartbeat = p.server.getTickCount(); return; }
        boolean release = request.action == RELEASE && eligible(p, s.hand) && s.matches(p);
        if (release) s.trace.finish(s.sphere.project(p.getLookAngle()));
        close(p, s); // Idempotent before any join/debit callbacks; later release packets cannot cast twice.
        if (p.isUsingItem() && p.getUsedItemHand() == s.hand && p.getUseItem() == s.wand) p.stopUsingItem();
        if (release && s.matches(p) && SlashRequests.room(p) && s.trace.fitted().size() >= 2)
            dev.senluo.witchcraft.compat.thaumcraft.SlashFocus.castDrawing(p, s.hand, s.sphere, s.trace);
    }
    @SubscribeEvent public static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        Session s = SESSIONS.get(p); if (s == null) return;
        long tick = p.server.getTickCount();
        if (!eligible(p, s.hand) || !s.matches(p) || tick - s.heartbeat > 30 || s.stopped >= 0 && tick - s.stopped > 8) {
            close(p, s); return;
        }
        if (s.latched || p.isUsingItem() && s.stopped < 0) sample(p, s);
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) SESSIONS.remove(p);
    }
    @SubscribeEvent public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) { Session s = SESSIONS.get(p); if (s != null) close(p, s); }
    }
    @SubscribeEvent public static void block(PlayerInteractEvent.RightClickBlock event) {
        if (SlashRequests.installed(event.getItemStack()) || event.getHand() == InteractionHand.MAIN_HAND
                && event.getItemStack().isEmpty() && SlashRequests.installed(event.getEntity().getOffhandItem()))
            event.setUseBlock(net.neoforged.neoforge.common.util.TriState.FALSE);
    }
}
