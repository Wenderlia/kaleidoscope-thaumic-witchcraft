package dev.senluo.witchcraft.mixin.time;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.time.PlayerActionTime;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PlayerActionConnectionMixin {
    @Shadow public ServerPlayer player;
    @WrapMethod(method = "handleMovePlayer")
    private void witchcraft$validatedMotion(ServerboundMovePlayerPacket packet, Operation<Void> original) {
        PlayerActionTime.movementPacket(player, () -> original.call(packet));
    }
    @WrapOperation(method = "handleMovePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"))
    private void witchcraft$motionQuote(ServerPlayer player, MoverType type, Vec3 movement, Operation<Void> original) {
        PlayerActionTime.packetStep(player, movement, () -> original.call(player, type, movement));
    }
}
