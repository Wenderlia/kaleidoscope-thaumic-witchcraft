package dev.senluo.witchcraft.mixin.brew;

import dev.senluo.witchcraft.brew.FrostPath;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep non-vibration game-event consumers intact. */
@Mixin(VibrationSystem.Listener.class)
public abstract class FrostVibrationMixin {
    @Inject(method="handleGameEvent",at=@At("HEAD"),cancellable=true)
    private void witchcraft$quiet(ServerLevel level,Holder<GameEvent> event,GameEvent.Context context,Vec3 pos,CallbackInfoReturnable<Boolean> cir){
        if(FrostPath.quiet(context.sourceEntity(),event))cir.setReturnValue(false);
    }
}
