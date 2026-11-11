package dev.senluo.witchcraft.mixin.brew;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Relocations reanchor the stride observation; outstanding ACKs suspend growth and idle expiry. */
@Mixin(ServerGamePacketListenerImpl.class)
public interface StrideTeleportAccess {
    @Accessor("awaitingTeleport") int witchcraft$teleportSequence();
    @Accessor("awaitingPositionFromClient") net.minecraft.world.phys.Vec3 witchcraft$awaitingPosition();
}
