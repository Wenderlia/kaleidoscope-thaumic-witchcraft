package dev.senluo.witchcraft.mixin.time;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.time.EntityTime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
// INVOKE hooks into an overwritten method require a strictly higher priority
// than its writer (Lithium's task launcher uses 1000). Mixin merges method
// bodies before preparing injections, so this wraps the optimized clock read
// too, without replacing the scheduler or changing the world's shared clock.
@Mixin(value = Brain.class, priority = 1100)
public abstract class BrainTimeMixin {
    @WrapOperation(method={"startEachNonRunningBehavior","tickEachRunningBehavior"},at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerLevel;getGameTime()J"))
    private long senluo$behaviorClock(ServerLevel level,Operation<Long> original,ServerLevel owner,LivingEntity entity){
        return EntityTime.localTime(entity,original.call(level));
    }
    @WrapOperation(method="stopAll",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;getGameTime()J"))
    private long senluo$stopClock(Level level,Operation<Long> original,ServerLevel owner,LivingEntity entity){
        return EntityTime.localTime(entity,original.call(level));
    }
}
