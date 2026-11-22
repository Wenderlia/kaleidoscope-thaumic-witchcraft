package dev.senluo.witchcraft.mixin.thaumcraft;

import dev.senluo.witchcraft.compat.thaumcraft.MixedMine;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mine terrain, entities and POIs are disposable; native chunk generation and unloading remain intact. */
@Mixin(IOWorker.class)
public abstract class MineStorageMixin {
    @Unique private boolean witchcraft$temporaryMine;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void witchcraft$identifyMine(RegionStorageInfo info, Path folder, boolean sync, CallbackInfo ci) {
        witchcraft$temporaryMine = MixedMine.source(info.dimension().location()) != null;
    }

    @Inject(method = "store", at = @At("HEAD"), cancellable = true)
    private void witchcraft$discardSave(ChunkPos pos, @Nullable CompoundTag tag, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        if (witchcraft$temporaryMine) cir.setReturnValue(CompletableFuture.completedFuture(null));
    }

    @Inject(method = "loadAsync", at = @At("HEAD"), cancellable = true)
    private void witchcraft$ignoreStoredChunk(ChunkPos pos, CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> cir) {
        if (witchcraft$temporaryMine) cir.setReturnValue(CompletableFuture.completedFuture(Optional.empty()));
    }

    @Inject(method = "scanChunk", at = @At("HEAD"), cancellable = true)
    private void witchcraft$ignoreStoredScan(ChunkPos pos, StreamTagVisitor visitor, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        if (witchcraft$temporaryMine) cir.setReturnValue(CompletableFuture.completedFuture(null));
    }

    @Inject(method = "isOldChunkAround", at = @At("HEAD"), cancellable = true)
    private void witchcraft$skipOldTerrainScan(ChunkPos pos, int radius, CallbackInfoReturnable<Boolean> cir) {
        if (witchcraft$temporaryMine) cir.setReturnValue(false);
    }
}
