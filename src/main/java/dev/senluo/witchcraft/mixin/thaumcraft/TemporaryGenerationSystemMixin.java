package dev.senluo.witchcraft.mixin.thaumcraft;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.senluo.witchcraft.compat.thaumcraft.MineWorkAreas;
import dev.senluo.witchcraft.compat.thaumcraft.TemporaryGeneration;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Pseudo
@Mixin(targets = "dev.tc4port.thaumcraft.worldgen.RetroGenerationSystem", remap = false)
public abstract class TemporaryGenerationSystemMixin {
    @Shadow @Final private static Map<ServerLevel, ?> PENDING;
    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void witchcraft$queues(CallbackInfo ci) { TemporaryGeneration.queues(PENDING); }

    @WrapMethod(method = "generateLoadedScatteredStructureNodes")
    private static boolean witchcraft$ownClaims(ServerLevel level, LevelChunk chunk, RandomSource random,
            @Coerce Object data, Operation<Boolean> original) {
        boolean[] result = {false};
        TemporaryGeneration.during(level, chunk.getPos(), () -> result[0] = original.call(level, chunk, random, data));
        return result[0];
    }
    @WrapMethod(method = "regenerate")
    private static void witchcraft$generating(ServerLevel level, ChunkPos pos, @Coerce Object options,
            @Coerce Object data, Operation<Void> original) {
        TemporaryGeneration.during(level, pos, () -> original.call(level, pos, options, data));
    }
    @Inject(method = "tickLevel", at = @At("RETURN"))
    private static void witchcraft$finished(ServerLevel level, @Coerce Object options, CallbackInfoReturnable<Integer> cir) {
        TemporaryGeneration.finished(level);
    }
    // Restored content already contains the original post-generation result.
    @Inject(method = "enqueue", at = @At("HEAD"), cancellable = true)
    private static void witchcraft$restoredTerrain(ServerLevel level, ChunkPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (MineWorkAreas.contains(level, pos)) cir.setReturnValue(false);
    }
    @Inject(method = "enqueueStructureCheck", at = @At("HEAD"), cancellable = true)
    private static void witchcraft$restoredStructures(ServerLevel level, ChunkPos pos, CallbackInfo ci) {
        if (MineWorkAreas.contains(level, pos)) ci.cancel();
    }
}
