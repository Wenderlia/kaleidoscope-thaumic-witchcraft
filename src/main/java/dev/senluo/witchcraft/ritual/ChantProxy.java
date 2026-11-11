package dev.senluo.witchcraft.ritual;

import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Event actor substitution, not a second chat submission or a forged signed chat packet. */
public final class ChantProxy {
    public static ServerPlayer actor(ServerPlayer player) {
        if (player == null || player.isFakePlayer() || !GeasBindings.has(player, GeasBindings.CHANT_PAIR.negative())) return player;
        // A message-local actor cannot retain another listener's inventory, effects or custom state.
        // Same name/position, distinct identity: never borrow the real player's permissions or bindings.
        UUID id = UUID.nameUUIDFromBytes(("witchcraft:chant_proxy:" + player.getUUID()).getBytes(StandardCharsets.UTF_8));
        var proxy = new FakePlayer(player.serverLevel(), new GameProfile(id, player.getGameProfile().getName())) {
            @Override protected int getPermissionLevel() { return 0; }
        };
        proxy.moveTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        proxy.setYHeadRot(player.getYHeadRot());
        proxy.setPose(player.getPose());
        return proxy;
    }
    private ChantProxy() {}
}
