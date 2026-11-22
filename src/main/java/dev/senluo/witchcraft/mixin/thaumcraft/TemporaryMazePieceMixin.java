package dev.senluo.witchcraft.mixin.thaumcraft;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.senluo.witchcraft.compat.thaumcraft.TemporaryMazes;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/** Both native generation and retro placement enter the same reservation method. */
@Pseudo
@Mixin(targets = "dev.tc4port.thaumcraft.worldgen.feature.ThaumcraftScatteredStructurePiece", remap = false)
public abstract class TemporaryMazePieceMixin {
    @Shadow @Final private int startChunkX;
    @Shadow @Final private int startChunkZ;

    @WrapMethod(method = "reserveMaze(Lnet/minecraft/world/level/WorldGenLevel;)Z")
    private boolean witchcraft$localMaze(WorldGenLevel level, Operation<Boolean> original) {
        return TemporaryMazes.reserve(level, startChunkX, startChunkZ,
                ((StructurePiece) (Object) this).getBoundingBox(), () -> original.call(level));
    }
}
