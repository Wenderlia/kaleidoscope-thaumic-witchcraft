package dev.senluo.witchcraft.mixin.thaumcraft;

import dev.senluo.witchcraft.compat.thaumcraft.TemporaryGeneration;
import java.util.Set;
import org.spongepowered.asm.mixin.*;

@Pseudo
@Mixin(targets = "dev.tc4port.thaumcraft.worldgen.RetroGenerationSystem$PendingChunks", remap = false)
public abstract class TemporaryGenerationPendingMixin implements TemporaryGeneration.Pending {
    @Shadow @Final private Set<Long> queued;
    @Shadow @Final private Set<Long> structureQueued;
    @Override public boolean witchcraft$pending(long chunk) { return queued.contains(chunk) || structureQueued.contains(chunk); }
}
