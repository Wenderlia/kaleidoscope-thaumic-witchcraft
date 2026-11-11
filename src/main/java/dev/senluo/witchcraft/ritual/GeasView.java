package dev.senluo.witchcraft.ritual;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import java.util.UUID;
import java.util.function.Supplier;

/** Only the local mining prediction bit is synced; the world ledger remains authoritative. */
public final class GeasView {
    private static final DeferredRegister<AttachmentType<?>> TYPES = DeferredRegister.create(
            NeoForgeRegistries.ATTACHMENT_TYPES, KaleidoscopeWitchcraft.MOD_ID);
    private static final Supplier<AttachmentType<Boolean>> RESTRAINED = TYPES.register("restrained_actions",
            () -> AttachmentType.builder(() -> false).sync((holder, receiver) -> holder == receiver, ByteBufCodecs.BOOL).build());

    public static void register(IEventBus bus) {
        TYPES.register(bus);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> refresh(event.getEntity()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerRespawnEvent event) -> refresh(event.getEntity()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent event) -> refresh(event.getEntity()));
    }
    private static void refresh(Player player) {
        if (player instanceof ServerPlayer server) server.setData(RESTRAINED.get(), GeasBindings.has(server, GeasBindings.RESTRAINT));
    }
    public static void changed(MinecraftServer server, UUID subject) {
        ServerPlayer player = server.getPlayerList().getPlayer(subject);
        if (player != null) refresh(player);
    }
    public static boolean restrained(Player player) {
        if (player instanceof ServerPlayer server) return GeasBindings.has(server, GeasBindings.RESTRAINT);
        return player != null && player.hasData(RESTRAINED.get()) && player.getData(RESTRAINED.get());
    }
    private GeasView() {}
}
