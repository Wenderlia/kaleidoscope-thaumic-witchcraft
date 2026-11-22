package dev.senluo.witchcraft.mixin.thaumcraft;

import dev.senluo.witchcraft.compat.thaumcraft.MineWorkAreas;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
public abstract class MineSampleLoadMixin {
    @Shadow @Final private ServerLevel level;
    @Inject(method = "readChunk", at = @At("HEAD"), cancellable = true)
    private void witchcraft$memory(ChunkPos pos, CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> cir) {
        CompoundTag data = MineWorkAreas.terrain(level, pos);
        if (data != null) cir.setReturnValue(CompletableFuture.completedFuture(Optional.of(data)));
    }
    @Inject(method = "handleChunkLoadFailure", at = @At("HEAD"))
    private void witchcraft$failed(Throwable error, ChunkPos pos, CallbackInfoReturnable<ChunkAccess> cir) {
        MineWorkAreas.failed(level, pos);
    }
}
