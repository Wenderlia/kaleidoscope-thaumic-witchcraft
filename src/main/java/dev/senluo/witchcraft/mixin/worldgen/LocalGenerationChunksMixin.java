package dev.senluo.witchcraft.mixin.worldgen;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import dev.senluo.witchcraft.worldgen.LocalGeneration;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native holder lifetime pins a context until generation and unloading actually finish. No tick wrapper. */
@Mixin(ChunkMap.class)
public abstract class LocalGenerationChunksMixin {
    @Shadow @Final private ServerLevel level;

    @ModifyExpressionValue(method = "updateChunkScheduling", at = @At(value = "NEW", target = "net/minecraft/server/level/ChunkHolder"))
    private ChunkHolder witchcraft$created(ChunkHolder holder) {
        LocalGeneration.chunkCreated(level, holder.getPos());
        return holder;
    }

    @WrapOperation(method = "lambda$scheduleUnload$12", at = @At(value = "INVOKE", target =
            "Lit/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap;remove(JLjava/lang/Object;)Z"), require = 1, allow = 1)
    private boolean witchcraft$retired(Long2ObjectLinkedOpenHashMap<?> pending, long key, Object holder,
            Operation<Boolean> original, @Share("witchcraft$retired") LocalBooleanRef retired) {
        boolean removed = original.call(pending, key, holder);
        retired.set(removed);
        return removed;
    }

    @Inject(method = "lambda$scheduleUnload$12", at = @At("RETURN"))
    private void witchcraft$unloaded(ChunkHolder holder, long key, CallbackInfo ci,
            @Share("witchcraft$retired") LocalBooleanRef retired) {
        if (retired.get()) LocalGeneration.chunkUnloaded(level, key);
    }
}
