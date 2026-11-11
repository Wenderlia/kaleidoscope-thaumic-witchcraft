package dev.senluo.witchcraft.ritual;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class RitualPackets {
    private RitualPackets() {}
    public record State(UUID player, UUID session, ResourceLocation dimension, Vec3 anchor,
            int progress, int escaping, boolean active, RitualPose pose) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(id("ownership_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeUUID(value.player); buf.writeUUID(value.session); buf.writeResourceLocation(value.dimension);
            buf.writeDouble(value.anchor.x); buf.writeDouble(value.anchor.y); buf.writeDouble(value.anchor.z);
            buf.writeVarInt(value.progress); buf.writeVarInt(value.escaping); buf.writeBoolean(value.active);
            buf.writeFloat(value.pose.yaw()); buf.writeFloat(value.pose.pitch());
            buf.writeFloat(value.pose.head()); buf.writeFloat(value.pose.body());
        }, buf -> {
            UUID player = buf.readUUID(), session = buf.readUUID();
            ResourceLocation dimension = buf.readResourceLocation();
            Vec3 anchor = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
            int progress = buf.readVarInt(), escaping = buf.readVarInt();
            boolean active = buf.readBoolean();
            RitualPose pose = new RitualPose(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
            if (!Double.isFinite(anchor.x) || !Double.isFinite(anchor.y) || !Double.isFinite(anchor.z)
                    || progress < 0 || progress > SustainedLedger.GRACE_SECONDS * 20 || escaping < 0 || escaping > OwnershipRitual.ESCAPE)
                throw new IllegalArgumentException("Invalid ownership ritual state");
            return new State(player, session, dimension, anchor, progress, escaping, active, pose);
        });
        @Override public Type<State> type() { return TYPE; }
    }
    public record Input(UUID session, boolean escaping) implements CustomPacketPayload {
        public static final Type<Input> TYPE = new Type<>(id("ownership_input"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Input> CODEC = StreamCodec.of(
                (buf, value) -> { buf.writeUUID(value.session); buf.writeBoolean(value.escaping); },
                buf -> new Input(buf.readUUID(), buf.readBoolean()));
        @Override public Type<Input> type() { return TYPE; }
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(KaleidoscopeWitchcraft.NETWORK_VERSION);
        registrar.playToClient(State.TYPE, State.CODEC, (packet, context) -> context.enqueueWork(() -> {
            if (packet.active) RitualView.put(packet.player, new RitualView.State(packet.session,
                    packet.dimension, packet.anchor, packet.progress, packet.escaping, packet.pose));
            else RitualView.remove(packet.player, packet.session);
        }));
        registrar.playToServer(Input.TYPE, Input.CODEC, (packet, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player)
                OwnershipRitual.input(player, packet.session, packet.escaping);
        }));
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID, path); }
}
