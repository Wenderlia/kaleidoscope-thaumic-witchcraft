package dev.senluo.witchcraft.mixin.worldgen;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.worldgen.LocalGeneration;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Native features, structures, loot seeds and spawn receive this sample's WorldGenRegion. */
@Mixin(WorldGenRegion.class)
public abstract class LocalGenerationRegionMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private ChunkAccess center;

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getSeed()J"))
    private long witchcraft$seed(ServerLevel source, Operation<Long> original) {
        var context = LocalGeneration.at(source, center.getPos());
        return context == null ? original.call(source) : context.seed();
    }

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/server/level/ServerChunkCache;randomState()Lnet/minecraft/world/level/levelgen/RandomState;"))
    private RandomState witchcraft$random(ServerChunkCache chunks, Operation<RandomState> original) {
        var context = LocalGeneration.at(level, center.getPos());
        return context == null ? original.call(chunks) : context.randomState();
    }
}
