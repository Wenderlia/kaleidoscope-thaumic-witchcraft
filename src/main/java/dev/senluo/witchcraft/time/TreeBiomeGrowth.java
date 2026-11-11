package dev.senluo.witchcraft.time;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.fml.ModList;

/** A natural tree pulse changes one loaded 4x4 biome column. No chunk tickets or catch-up work. */
public final class TreeBiomeGrowth {
    private static final ResourceKey<Biome> FOREST = ResourceKey.create(Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath("thaumcraft", "magical_forest"));
    private static final boolean ENABLED = ModList.get().isLoaded("thaumcraft");
    // Owned by TemporalWorld; only coordinates wait until the end of its current producer pass.
    private final Set<Long> changedChunks = new LinkedHashSet<>();

    void tick(ServerLevel level, LivingEntity core, long tick) {
        if (!ENABLED || !core.tree() || level.dimension().equals(Level.NETHER) || level.dimension().equals(Level.END)) return;
        var origin = core.getBlockPos();
        if (Math.floorMod(tick, 50) != Math.floorMod(origin.hashCode(), 50)) return;
        int radius = 8 + (core.tissue.giant || core.tissue.giantGrowing ? TreeShapeSpace.GIANT_RADIUS : TreeShapeSpace.TREE_RADIUS);
        int x = origin.getX() + level.random.nextInt(radius + 1) - level.random.nextInt(radius + 1);
        int z = origin.getZ() + level.random.nextInt(radius + 1) - level.random.nextInt(radius + 1);
        int minX = QuartPos.toBlock(QuartPos.fromBlock(x)), minZ = QuartPos.toBlock(QuartPos.fromBlock(z));
        if (minX < origin.getX()-radius || minX+3 > origin.getX()+radius
                || minZ < origin.getZ()-radius || minZ+3 > origin.getZ()+radius) return;
        if (!level.getWorldBorder().isWithinBounds(new BlockPos(minX, origin.getY(), minZ))
                || !level.getWorldBorder().isWithinBounds(new BlockPos(minX+3, origin.getY(), minZ+3))) return;
        var chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) return;
        var forest = level.registryAccess().registryOrThrow(Registries.BIOME).getHolder(FOREST).orElse(null);
        if (forest == null) return;
        int qx = QuartPos.quartLocal(QuartPos.fromBlock(x)), qz = QuartPos.quartLocal(QuartPos.fromBlock(z));
        boolean changed = false;
        for (var section : chunk.getSections()) changed |= convert(section, qx, qz, forest);
        if (changed) {
            chunk.setUnsaved(true);
            changedChunks.add(chunk.getPos().toLong());
        }
    }

    void flush(ServerLevel level) {
        if (changedChunks.isEmpty()) return;
        var chunks = new ArrayList<net.minecraft.world.level.chunk.ChunkAccess>(changedChunks.size());
        for (long key : changedChunks) {
            var chunk = level.getChunkSource().getChunkNow(net.minecraft.world.level.ChunkPos.getX(key),
                    net.minecraft.world.level.ChunkPos.getZ(key));
            if (chunk != null) chunks.add(chunk);
        }
        changedChunks.clear();
        if (!chunks.isEmpty()) level.getChunkSource().chunkMap.resendBiomesForChunks(chunks);
    }

    private static boolean convert(LevelChunkSection section, int x, int z, Holder<Biome> forest) {
        var previous = section.getBiomes();
        for (int y = 0; y < 4; y++) {
            if (previous.get(x, y, z).is(FOREST)) continue;
            // Use the section's public palette replacement. Zero origins give section-local quart coordinates.
            section.fillBiomesFromNoise((qx, qy, qz, sampler) ->
                    qx == x && qz == z ? forest : previous.get(qx, qy, qz), null, 0, 0, 0);
            return true;
        }
        return false;
    }

    TreeBiomeGrowth() {}
}
