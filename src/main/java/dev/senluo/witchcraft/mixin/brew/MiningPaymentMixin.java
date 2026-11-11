package dev.senluo.witchcraft.mixin.brew;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.senluo.witchcraft.brew.MiningClock;
import dev.senluo.witchcraft.brew.TimeMining;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerGameMode.class)
public abstract class MiningPaymentMixin implements MiningClock {
    @Shadow @Final protected ServerPlayer player;
    @Shadow private int gameTicks;
    @Unique private TimeMining.Payment witchcraft$payment;
    @Override public int witchcraft$miningTick() {return gameTicks;}
    @Inject(method="handleBlockBreakAction",at=@At("HEAD"))
    private void witchcraft$trackMining(BlockPos pos,ServerboundPlayerActionPacket.Action action,
            Direction direction,int height,int sequence,CallbackInfo ci) {
        if(action==ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK)TimeMining.start(player,pos);
        else if(action==ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK)TimeMining.stop(player);
    }
    @WrapMethod(method="destroyBlock")
    private boolean witchcraft$paySuccessfulMining(BlockPos pos,Operation<Boolean> original) {
        TimeMining.Payment previous=witchcraft$payment;
        TimeMining.Payment payment=TimeMining.beginRemoval(player,pos);
        if(!payment.allowed) {
            player.connection.send(new ClientboundBlockUpdatePacket(pos,player.serverLevel().getBlockState(pos)));
            return false;
        }
        witchcraft$payment=payment;
        try {return original.call(pos);}
        finally {
            payment.finish();
            witchcraft$payment=previous;
            TimeMining.stop(player);
        }
    }
    @Inject(method="removeBlock",at=@At("RETURN"))
    private void witchcraft$confirmRemoval(BlockPos pos,BlockState state,boolean harvest,CallbackInfoReturnable<Boolean> cir) {
        if(witchcraft$payment!=null)witchcraft$payment.removed(cir.getReturnValue());
    }
}
