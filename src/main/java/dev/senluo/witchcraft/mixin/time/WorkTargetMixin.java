package dev.senluo.witchcraft.mixin.time;

import dev.senluo.witchcraft.time.WorkTicks;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** A transient route field, with no injected tick callback. */
@Mixin(BlockEntity.class)
public abstract class WorkTargetMixin implements WorkTicks.Target {
    @Unique private int senluo$workRoutes = -1;
    public int senluo$workRoutes() { return senluo$workRoutes; }
    public void senluo$workRoutes(int routes) { senluo$workRoutes = routes; }
}
