package dev.senluo.witchcraft.mixin.time;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.senluo.witchcraft.time.ScheduledTime;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(LevelTicks.class)
public abstract class ScheduledQueueMixin<T> {
    @WrapMethod(method="schedule")
    private void senluo$accepted(ScheduledTick<T> tick,Operation<Void> original){
        LevelTicks<T> queue=(LevelTicks<T>)(Object)this;
        if(ScheduledTime.alreadyDeferred(queue,tick))return;
        boolean existed=queue.hasScheduledTick(tick.pos(),tick.type());
        original.call(tick);
        if(!existed&&queue.hasScheduledTick(tick.pos(),tick.type()))ScheduledTime.accepted(queue,tick);
    }
}
