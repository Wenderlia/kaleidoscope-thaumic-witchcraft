package dev.senluo.witchcraft.time;

import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerRO;

public final class TreeBiomeGrowthGameTests {
    @GameTest(templateNamespace = "kaleidoscope_witchcraft", template = "empty", timeoutTicks = 20)
    public static void forestPulseChangesOnlyItsQuartColumn(GameTestHelper h) {
        var level = h.getLevel();
        var pos = h.absolutePos(new BlockPos(3, 3, 3));
        h.setBiome(Biomes.PLAINS);
        level.setBlock(pos, TemporalContent.CORE.get().defaultBlockState(), 3);
        var core = (LivingEntity)level.getBlockEntity(pos);
        var chunk = level.getChunkAt(pos);
        var sections = chunk.getSections();
        var previous = Arrays.stream(sections).map(LevelChunkSection::getBiomes).toList();
        int qx = QuartPos.quartLocal(QuartPos.fromBlock(pos.getX()));
        int qz = QuartPos.quartLocal(QuartPos.fromBlock(pos.getZ()));
        long due = Math.floorMod(pos.hashCode(), 50);
        var growth = new TreeBiomeGrowth();
        try {
            growth.tick(level, core, due + 1);
            for (int i = 0; i < sections.length; i++)
                h.assertTrue(sections[i].getBiomes() == previous.get(i), "A core cannot spread between pulses");

            // Select the core's own horizontal column without altering the distribution used by production.
            var probe = RandomSource.create();
            long seed = 0;
            do { probe.setSeed(seed++); }
            while (probe.nextInt(17) != probe.nextInt(17) || probe.nextInt(17) != probe.nextInt(17));
            level.random.setSeed(seed - 1);
            growth.tick(level, core, due);
            var forest = ResourceLocation.fromNamespaceAndPath("thaumcraft", "magical_forest");
            for (int i = 0; i < sections.length; i++) {
                var now = sections[i].getBiomes();
                for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) {
                    if (x == qx && z == qz) h.assertTrue(now.get(x,y,z).unwrapKey().orElseThrow().location().equals(forest),
                            "The forest must reach all heights of its column");
                    else h.assertTrue(now.get(x,y,z).equals(previous.get(i).get(x,y,z)), "Neighbouring biomes changed");
                }
            }
            growth.flush(level);
        } finally {
            for (int i = 0; i < sections.length; i++) {
                PalettedContainerRO<Holder<Biome>> old = previous.get(i);
                if (old != sections[i].getBiomes())
                    sections[i].fillBiomesFromNoise((x,y,z,sampler) -> old.get(x,y,z), null, 0, 0, 0);
            }
            chunk.setUnsaved(true);
            level.getChunkSource().chunkMap.resendBiomesForChunks(java.util.List.of(chunk));
        }
        h.succeed();
    }
    private TreeBiomeGrowthGameTests() {}
}
