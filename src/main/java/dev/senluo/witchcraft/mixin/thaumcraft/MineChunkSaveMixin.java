package dev.senluo.witchcraft.mixin.thaumcraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.compat.thaumcraft.MineStructureCache;
import dev.senluo.witchcraft.compat.thaumcraft.MixedMine;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Skip serialization of disposable chunks without disabling the native unload loop. */
@Mixin(ChunkMap.class)
public abstract class MineChunkSaveMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> updatingChunkMap;
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;
    @Unique private boolean witchcraft$temporaryMine;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void witchcraft$identifyMine(CallbackInfo ci) {
        witchcraft$temporaryMine = MixedMine.source(level.dimension().location()) != null;
    }

    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void witchcraft$discardSave(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
        if (!witchcraft$temporaryMine) return;
        chunk.setUnsaved(false);
        cir.setReturnValue(false);
    }

    @WrapOperation(method = "lambda$scheduleUnload$12", at = @At(value = "INVOKE", target =
            "Lit/unimi/dsi/fastutil/longs/Long2LongMap;remove(J)J"), require = 1, allow = 1)
    private long witchcraft$retireStructureReport(Long2LongMap cooldowns, long key, Operation<Long> original) {
        long previous = original.call(cooldowns, key);
        if (!witchcraft$temporaryMine) return previous;
        var server = level.getServer();
        // Worldgen queued its structure report before unload became eligible. Queue behind that report.
        server.tell(new TickTask(server.getTickCount(), () -> {
            ChunkHolder holder = updatingChunkMap.get(key);
            if (holder == null) holder = pendingUnloads.get(key);
            ChunkAccess current = holder == null ? null : holder.getChunkIfPresentUnchecked(ChunkStatus.STRUCTURE_STARTS);
            // A new holder can report structures before its stage future completes; that report is still in use.
            if (holder != null && current == null) return;
            // The same address may already have a new holder; retain that holder's actual structure data.
            ((MineStructureCache) level).witchcraft$refreshStructureCache(new ChunkPos(key), current);
        }));
        return previous;
    }
}
