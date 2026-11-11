package dev.senluo.witchcraft.mixin.ritual;

import dev.senluo.witchcraft.ritual.GeasView;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Enforce the post-break wait on client digging packets, not the block's hardness/work progress. */
@Mixin(ServerPlayerGameMode.class)
public abstract class RestraintMiningMixin {
    @Shadow @Final protected ServerPlayer player;
    @Unique private long witchcraft$nextMining;

    @Inject(method = "handleBlockBreakAction", at = @At("HEAD"), cancellable = true)
    private void witchcraft$interval(BlockPos pos, ServerboundPlayerActionPacket.Action action,
            Direction direction, int height, int sequence, CallbackInfo ci) {
        if ((action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                || action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) && GeasView.restrained(player)
                && player.server.overworld().getGameTime() < witchcraft$nextMining) {
            player.connection.send(new ClientboundBlockUpdatePacket(pos, player.serverLevel().getBlockState(pos)));
            ci.cancel();
        }
    }

    @WrapOperation(method = {"destroyAndAck", "tick"}, require = 2, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayerGameMode;destroyBlock(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean witchcraft$finish(ServerPlayerGameMode gameMode, BlockPos pos, Operation<Boolean> original) {
        var state = player.serverLevel().getBlockState(pos);
        // Vanilla instant-mining in survival does not set destroyDelay; preserve that distinction.
        boolean timed = !state.isAir() && GeasView.restrained(player)
                && (player.isCreative() || state.getDestroyProgress(player, player.level(), pos) < 1);
        boolean removed = original.call(gameMode, pos);
        if (timed && removed)
            witchcraft$nextMining = player.server.overworld().getGameTime() + 10;
        return removed;
    }
}
