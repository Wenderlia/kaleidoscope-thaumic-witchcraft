package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.slash.SlashContent;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public record ShockBolt(int source, int target) implements CustomPacketPayload {
    private static final Type<ShockBolt> TYPE = new Type<>(SlashContent.id("chain_bolt"));
    private static final StreamCodec<RegistryFriendlyByteBuf, ShockBolt> CODEC = StreamCodec.of(
            (buffer, packet) -> { buffer.writeVarInt(packet.source); buffer.writeVarInt(packet.target); },
            buffer -> new ShockBolt(buffer.readVarInt(), buffer.readVarInt()));

    public Type<ShockBolt> type() { return TYPE; }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(KaleidoscopeWitchcraft.NETWORK_VERSION).playToClient(TYPE, CODEC,
                (packet, context) -> context.enqueueWork(() ->
                        dev.senluo.witchcraft.compat.thaumcraft.client.FocusBarrierVisuals.chainBolt(packet)));
    }

    static void send(ServerLevel level, LivingEntity source, LivingEntity target) {
        var packet = new ShockBolt(source.getId(), target.getId());
        for (var observer : level.players()) if (observer.distanceToSqr(source) <= 64 * 64)
            PacketDistributor.sendToPlayer(observer, packet);
    }
}
