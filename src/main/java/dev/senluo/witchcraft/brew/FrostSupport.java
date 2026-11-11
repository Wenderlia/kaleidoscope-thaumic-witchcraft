package dev.senluo.witchcraft.brew;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import java.util.function.Supplier;

/** Transient jump+sneak intent; the server still decides who may stand on a light step. */
public final class FrostSupport {
    private static final DeferredRegister<AttachmentType<?>> DATA = DeferredRegister.create(
            NeoForgeRegistries.ATTACHMENT_TYPES, KaleidoscopeWitchcraft.MOD_ID);
    private static final Supplier<AttachmentType<Boolean>> HELD = DATA.register("frost_crouch_support",
            () -> AttachmentType.builder(() -> false).build());
    private FrostSupport() {}
    static void register(IEventBus bus) { DATA.register(bus); bus.addListener(FrostSupport::packets); }
    public static boolean available(Entity entity) {
        return entity instanceof Player player && FrostAbilities.view(player).support() && player.isAlive()
                && !player.isSpectator() && !player.isPassenger() && !player.isFallFlying() && !player.getAbilities().flying
                && !dev.senluo.witchcraft.ritual.OwnershipRitual.frozen(player);
    }
    public static boolean holding(Entity entity) {
        return available(entity) && entity.isShiftKeyDown() && entity.hasData(HELD.get()) && entity.getData(HELD.get());
    }
    public static void intent(Player player, boolean down) {
        if (down && available(player)) {
            if (!player.hasData(HELD.get())) player.setData(HELD.get(), true);
        } else if (player.hasData(HELD.get())) player.removeData(HELD.get());
    }
    public static boolean onStep(Player player) {
        BlockPos below = BlockPos.containing(player.getX(), Math.floor(player.getY() + 1e-5) - 1, player.getZ());
        return player.onGround() && player.level().getBlockState(below).is(FrostPath.BLOCK.get());
    }
    public record Held(boolean down) implements CustomPacketPayload {
        public static final Type<Held> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                KaleidoscopeWitchcraft.MOD_ID, "frost_support_input"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Held> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeBoolean(value.down), buf -> new Held(buf.readBoolean()));
        @Override public Type<Held> type() { return TYPE; }
    }
    private static void packets(RegisterPayloadHandlersEvent event) {
        event.registrar(KaleidoscopeWitchcraft.NETWORK_VERSION).playToServer(Held.TYPE, Held.CODEC,
                (packet, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) intent(player, packet.down);
                }));
    }
}
