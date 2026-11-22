package dev.senluo.witchcraft.mixin.thaumcraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.compat.thaumcraft.TemporaryMazes;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Only a scoped temporary structure gets an unattached native data object. Real worlds keep their ledger. */
@Pseudo
@Mixin(targets = "dev.tc4port.thaumcraft.worldgen.outer.OuterMazeData", remap = false)
public abstract class TemporaryMazeDataMixin implements TemporaryMazes.Data {
    @Shadow @Final private Map<Object, Object> cells;
    @Shadow @Final private Set<Long> mazeCenters;

    @WrapOperation(method = "get(Lnet/minecraft/world/level/WorldGenLevel;)Ldev/tc4port/thaumcraft/worldgen/outer/OuterMazeData;",
            at = @At(value = "INVOKE", target = "Ldev/tc4port/thaumcraft/worldgen/outer/OuterMazeData;native$SavedDataPlatform$get(Lnet/minecraft/world/level/storage/DimensionDataStorage;Lnet/minecraft/world/level/saveddata/SavedData$Factory;Ljava/lang/String;)Lnet/minecraft/world/level/saveddata/SavedData;"), require = 1, allow = 1)
    private static SavedData witchcraft$worldgenData(DimensionDataStorage storage, SavedData.Factory<?> factory,
                                                    String name, Operation<SavedData> original, WorldGenLevel level) {
        SavedData temporary = TemporaryMazes.data(level, factory);
        return temporary != null ? temporary : original.call(storage, factory, name);
    }

    @WrapOperation(method = "get(Lnet/minecraft/server/level/ServerLevel;)Ldev/tc4port/thaumcraft/worldgen/outer/OuterMazeData;",
            at = @At(value = "INVOKE", target = "Ldev/tc4port/thaumcraft/worldgen/outer/OuterMazeData;native$SavedDataPlatform$get(Lnet/minecraft/world/level/storage/DimensionDataStorage;Lnet/minecraft/world/level/saveddata/SavedData$Factory;Ljava/lang/String;)Lnet/minecraft/world/level/saveddata/SavedData;"), require = 1, allow = 1)
    private static SavedData witchcraft$levelData(DimensionDataStorage storage, SavedData.Factory<?> factory,
                                                 String name, Operation<SavedData> original, ServerLevel level) {
        SavedData temporary = TemporaryMazes.data(level, factory);
        return temporary != null ? temporary : original.call(storage, factory, name);
    }

    @WrapOperation(method = "publish", at = @At(value = "INVOKE", target =
            "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"), require = 1, allow = 1)
    private Object witchcraft$ownedCell(Map<Object, Object> target, Object key, Object value, Operation<Object> original) {
        Object previous = original.call(target, key, value);
        TemporaryMazes.cellWritten((SavedData) (Object) this, key);
        return previous;
    }

    @Override public synchronized void witchcraft$discardMaze(long center, List<Object> ownedCells) {
        for (Object key : ownedCells) cells.remove(key);
        mazeCenters.remove(center);
    }
}
