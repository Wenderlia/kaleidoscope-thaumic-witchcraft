package dev.senluo.witchcraft.compat.thaumcraft;

import dev.tc4port.thaumcraft.api.ThaumcraftContent;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class RitualWaterIndexGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty")
    public static void waterWorkListFollowsOrdinaryCrucibles(GameTestHelper h) {
        var level = h.getLevel();
        var pos = h.absolutePos(new BlockPos(2, 2, 2));
        var pot = ThaumcraftContent.block("crucible").defaultBlockState();
        var index = RitualWaterIndex.get(level);
        var chunk = level.getChunkAt(pos);
        index.nearby(level, pos, false);
        h.assertFalse(index.centers(level).contains(pos), "The checked empty position starts outside the result");
        level.setBlockAndUpdate(pos, pot);
        h.assertTrue(index.nearby(level, pos, false).contains(pos) && index.centers(level).contains(pos),
                "Refreshing sources first publishes an ordinary crucible to the center list");
        level.removeBlock(pos, false);
        h.assertFalse(index.centers(level).contains(pos), "Removing the last crucible invalidates its chunk");
        level.setBlockAndUpdate(pos, pot);
        h.assertTrue(index.centers(level).contains(pos), "A reinstalled pot wakes even before source discovery");
        RitualWaterIndex.unload(new ChunkEvent.Unload(chunk));
        h.assertFalse(index.centers(level).contains(pos), "Unload retires the entire bucket");
        RitualWaterIndex.load(new ChunkEvent.Load(chunk, false));
        h.assertTrue(index.centers(level).contains(pos), "Loading the same chunk rediscovers its pot");
        var tag = chunk.getBlockEntity(pos).saveWithFullMetadata(level.registryAccess());
        chunk.removeBlockEntity(pos);
        chunk.setBlockEntityNbt(tag);
        RitualWaterIndex.load(new ChunkEvent.Load(chunk, false));
        h.assertFalse(index.centers(level).contains(pos), "A still-packed crucible cannot supply water");
        h.assertTrue(chunk.getBlockEntity(pos) != null && index.centers(level).contains(pos),
                "Native unpacking restores eligibility without a block replacement");
        level.removeBlock(pos, false);
        h.succeed();
    }
}
