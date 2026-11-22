package dev.senluo.witchcraft.mixin.thaumcraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.compat.thaumcraft.MineStructureCache;
import dev.senluo.witchcraft.compat.thaumcraft.MixedMine;
import it.unimi.dsi.fastutil.longs.Long2BooleanFunction;
import it.unimi.dsi.fastutil.longs.Long2BooleanMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Actual structure reports live with their chunks; ungenerated predictions have no stored history. */
@Mixin(StructureCheck.class)
public abstract class MineStructureCacheMixin implements MineStructureCache {
    @Shadow @Final private ResourceKey<Level> dimension;
    @Shadow @Final private Long2ObjectMap<Object2IntMap<Structure>> loadedChunks;
    @Shadow public abstract void onStructureLoad(ChunkPos pos, Map<Structure, StructureStart> starts);
    @Unique private boolean witchcraft$temporaryMine;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void witchcraft$identifyMine(CallbackInfo ci) {
        witchcraft$temporaryMine = MixedMine.source(dimension.location()) != null;
    }

    @WrapOperation(method = "checkStart", at = @At(value = "INVOKE", target =
            "Lit/unimi/dsi/fastutil/longs/Long2BooleanMap;computeIfAbsent(JLit/unimi/dsi/fastutil/longs/Long2BooleanFunction;)Z"), require = 1, allow = 1)
    private boolean witchcraft$predictWithoutHistory(Long2BooleanMap checks, long chunk,
            Long2BooleanFunction calculate, Operation<Boolean> original) {
        return witchcraft$temporaryMine ? calculate.get(chunk) : original.call(checks, chunk, calculate);
    }

    @Override public void witchcraft$refreshStructureCache(ChunkPos pos, @Nullable ChunkAccess current) {
        if (!witchcraft$temporaryMine) return;
        loadedChunks.remove(pos.toLong());
        if (current != null) onStructureLoad(pos, current.getAllStarts());
    }
}
