package dev.senluo.witchcraft.mixin.thaumcraft;

import dev.senluo.witchcraft.compat.thaumcraft.MineStructureCache;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Keeps the native structure checker private to its level while exposing one unload operation. */
@Mixin(ServerLevel.class)
public abstract class MineStructureLevelMixin implements MineStructureCache {
    @Shadow @Final private StructureCheck structureCheck;

    @Override public void witchcraft$refreshStructureCache(ChunkPos pos, @Nullable ChunkAccess current) {
        ((MineStructureCache) structureCheck).witchcraft$refreshStructureCache(pos, current);
    }
}
