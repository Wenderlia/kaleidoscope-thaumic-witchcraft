package dev.senluo.witchcraft.mixin.thaumcraft;

import dev.senluo.witchcraft.compat.thaumcraft.MixedMine;
import dev.senluo.witchcraft.compat.thaumcraft.MineWorkAreas;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.entity.ChunkEntities;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Disposable entities need neither serialization nor a permanent cache of empty chunk addresses. */
@Mixin(EntityStorage.class)
public abstract class MineEntityStorageMixin implements MineWorkAreas.EntityWorld {
    @Shadow @Final private ServerLevel level;
    @Unique private boolean witchcraft$temporaryMine;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void witchcraft$identifyMine(CallbackInfo ci) {
        witchcraft$temporaryMine = MixedMine.source(level.dimension().location()) != null;
    }

    @Inject(method = "loadEntities", at = @At("HEAD"), cancellable = true)
    private void witchcraft$emptyStoredEntities(ChunkPos pos, CallbackInfoReturnable<CompletableFuture<ChunkEntities<Entity>>> cir) {
        if (witchcraft$temporaryMine) {
            var restored = MineWorkAreas.entities(level, pos);
            cir.setReturnValue(CompletableFuture.completedFuture(restored == null ? new ChunkEntities<>(pos, List.of()) : restored));
        }
    }

    @Inject(method = "storeEntities", at = @At("HEAD"), cancellable = true)
    private void witchcraft$discardSave(ChunkEntities<Entity> entities, CallbackInfo ci) {
        if (witchcraft$temporaryMine) ci.cancel();
    }
    @Override public ServerLevel witchcraft$entityWorld() { return level; }
}
