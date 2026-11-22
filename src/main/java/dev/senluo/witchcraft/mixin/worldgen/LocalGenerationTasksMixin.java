package dev.senluo.witchcraft.mixin.worldgen;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.senluo.witchcraft.worldgen.LocalGeneration;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Select immutable per-owner inputs at the native generation boundary, never mutate shared world state. */
@Mixin(ChunkStatusTasks.class)
public abstract class LocalGenerationTasksMixin {
    @WrapOperation(method = "generateStructureStarts", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/server/level/ServerChunkCache;getGeneratorState()Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;"))
    private static ChunkGeneratorStructureState witchcraft$structures(ServerChunkCache chunks,
            Operation<ChunkGeneratorStructureState> original, @Local(argsOnly = true) WorldGenContext world,
            @Local(argsOnly = true) ChunkAccess chunk) {
        var context = LocalGeneration.at(world.level(), chunk.getPos());
        return context == null ? original.call(chunks) : context.structures();
    }

    @WrapOperation(method = {"generateBiomes", "generateNoise", "generateSurface", "generateCarvers"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;randomState()Lnet/minecraft/world/level/levelgen/RandomState;"))
    private static RandomState witchcraft$noise(ServerChunkCache chunks, Operation<RandomState> original,
            @Local(argsOnly = true) WorldGenContext world, @Local(argsOnly = true) ChunkAccess chunk) {
        var context = LocalGeneration.at(world.level(), chunk.getPos());
        return context == null ? original.call(chunks) : context.randomState();
    }

    @WrapOperation(method = "generateCarvers", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/server/level/ServerLevel;getSeed()J"))
    private static long witchcraft$carvingSeed(ServerLevel level, Operation<Long> original,
            @Local(argsOnly = true) ChunkAccess chunk) {
        var context = LocalGeneration.at(level, chunk.getPos());
        return context == null ? original.call(level) : context.seed();
    }

    @WrapOperation(method = "generateCarvers", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/server/level/ServerLevel;getBiomeManager()Lnet/minecraft/world/level/biome/BiomeManager;"))
    private static BiomeManager witchcraft$carvingBiomes(ServerLevel level, Operation<BiomeManager> original,
            @Local(argsOnly = true) ChunkAccess chunk) {
        var context = LocalGeneration.at(level, chunk.getPos());
        return context == null ? original.call(level) : new BiomeManager(level, BiomeManager.obfuscateSeed(context.seed()));
    }
}
