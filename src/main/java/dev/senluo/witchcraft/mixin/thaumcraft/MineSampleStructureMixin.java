package dev.senluo.witchcraft.mixin.thaumcraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.senluo.witchcraft.compat.thaumcraft.MineWorkAreas;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChunkSerializer.class)
public abstract class MineSampleStructureMixin {
    @WrapOperation(method = "read", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/storage/ChunkSerializer;unpackStructureStart(Lnet/minecraft/world/level/levelgen/structure/pieces/StructurePieceSerializationContext;Lnet/minecraft/nbt/CompoundTag;J)Ljava/util/Map;"))
    private static Map<Structure, StructureStart> witchcraft$originalSeed(StructurePieceSerializationContext context,
            CompoundTag tag, long seed, Operation<Map<Structure, StructureStart>> original,
            @Local(argsOnly = true) ServerLevel level, @Local(argsOnly = true) ChunkPos pos) {
        var starts = MineWorkAreas.starts(level, pos);
        return starts == null ? original.call(context, tag, seed) : starts;
    }
}
