package dev.senluo.witchcraft.mixin.brew;

import dev.senluo.witchcraft.brew.TimeStride;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class FlightMovementCheckMixin {
    @Shadow public ServerPlayer player;

    @ModifyConstant(method="handleMovePlayer",constant={@Constant(floatValue=100.0F),@Constant(floatValue=300.0F)})
    private float witchcraft$authorizedMovementAllowance(float ordinary) {
        // Vanilla compares squared distance. Scale its allowance only for the server's
        // paid ramp in either movement mode; collision, packet frequency and teleports remain intact.
        double factor=TimeStride.movementMultiplier(player);
        return (float)(ordinary*factor*factor);
    }
}
