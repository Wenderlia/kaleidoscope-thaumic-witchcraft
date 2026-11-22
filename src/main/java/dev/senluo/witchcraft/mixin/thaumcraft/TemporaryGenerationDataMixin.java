package dev.senluo.witchcraft.mixin.thaumcraft;

import dev.senluo.witchcraft.compat.thaumcraft.TemporaryGeneration;
import dev.senluo.witchcraft.compat.thaumcraft.MineWorkAreas;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "dev.tc4port.thaumcraft.worldgen.RetroGenerationData", remap = false)
public abstract class TemporaryGenerationDataMixin implements TemporaryGeneration.Data {
    @Shadow @Final private static SavedData.Factory<?> FACTORY;
    @Shadow @Final private Map<String, Set<Long>> generatedByKey;
    @Shadow @Final private Set<Long> claimedStructureNodes;
    @Unique private ServerLevel witchcraft$owner;

    @Inject(method = "get", at = @At("HEAD"), cancellable = true)
    private static void witchcraft$temporary(ServerLevel level, CallbackInfoReturnable<SavedData> cir) {
        SavedData data = TemporaryGeneration.data(level, FACTORY);
        if (data != null) cir.setReturnValue(data);
    }
    @Override public void witchcraft$owner(ServerLevel level) { witchcraft$owner = level; }
    @Inject(method = "generated", at = @At("HEAD"), cancellable = true)
    private void witchcraft$restored(String key, ChunkPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (witchcraft$owner != null && MineWorkAreas.contains(witchcraft$owner, pos)) cir.setReturnValue(true);
    }
    @Inject(method = "claimStructureNode", at = @At("RETURN"))
    private void witchcraft$claim(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) TemporaryGeneration.claimed((SavedData) (Object) this, pos);
    }
    @Override public void witchcraft$discard(long chunk, Set<Long> claims) {
        generatedByKey.values().forEach(chunks -> chunks.remove(chunk));
        claimedStructureNodes.removeAll(claims);
    }
}
