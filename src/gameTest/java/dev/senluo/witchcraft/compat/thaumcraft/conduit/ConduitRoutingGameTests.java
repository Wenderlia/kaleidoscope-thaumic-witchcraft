package dev.senluo.witchcraft.compat.thaumcraft.conduit;

import dev.senluo.witchcraft.ritual.SuctionTransferStore;
import dev.tc4port.thaumcraft.api.aspect.AspectId;
import dev.tc4port.thaumcraft.api.essentia.EssentiaTransferMode;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class ConduitRoutingGameTests {
    private static ConduitEntity pipe(GameTestHelper h, BlockPos pos) {
        h.getLevel().setBlock(pos, ConduitContent.BLOCK.get().defaultBlockState(), 3);
        var pipe = (ConduitEntity) h.getLevel().getBlockEntity(pos);
        ConduitNetworks.add(h.getLevel(), pipe);
        return pipe;
    }
    private static void credit(GameTestHelper h, ConduitEntity... pipes) {
        for (var pipe : pipes) {
            ConduitEntity.tick(h.getLevel(), pipe.getBlockPos(), pipe.getBlockState(), pipe);
            pipe.batch(h.getLevel(), 200).run();
        }
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=20)
    public static void partialConduitReceiptsFollowSplitAndRejoin(GameTestHelper h) {
        var level = h.getLevel(); var start = h.absolutePos(new BlockPos(2, 3, 2));
        var first = pipe(h, start); var middle = pipe(h, start.east()); var last = pipe(h, start.east(2));
        var foreign = pipe(h, start.south(4));
        var jar = start.east(2).below();
        level.setBlock(jar, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("thaumcraft:warded_jar")).defaultBlockState(), 3);
        ConduitNetworks.tick(level); credit(h, first, middle, last, foreign);
        var journal = SuctionTransferStore.get(level); var aspect = AspectId.parse("aer");
        var local = new SuctionTransferStore.Outlet(level.dimension().location(), start, SuctionTransferStore.Scope.CONDUIT);
        var other = new SuctionTransferStore.Outlet(level.dimension().location(), foreign.getBlockPos(), SuctionTransferStore.Scope.CONDUIT);
        journal.recordBuffer(local, aspect.value(), 8); journal.recordBuffer(other, aspect.value(), 5);
        try {
            h.assertTrue(last.takeEssentia(aspect, 2, Direction.DOWN, EssentiaTransferMode.EXECUTE) == 2
                    && journal.receipt(local).amount() == 6, "A native boundary pull settles only its actual amount");
            middle.toggle(Direction.EAST); ConduitNetworks.tick(level); credit(h, first, middle, last);
            h.assertTrue(last.takeEssentia(aspect, 2, Direction.DOWN, EssentiaTransferMode.EXECUTE) == 0
                    && journal.receipt(local).amount() == 6, "Disconnected pending matter remains owned by its original outlet");
            middle.toggle(Direction.EAST); ConduitNetworks.tick(level); credit(h, first, middle, last);
            h.assertTrue(last.takeEssentia(aspect, 6, Direction.DOWN, EssentiaTransferMode.EXECUTE) == 6
                    && !journal.holds(local), "Rejoining exposes the same remaining receipt without duplication");
            h.assertTrue(first.addEssentia(aspect, 4, Direction.UP, EssentiaTransferMode.EXECUTE) == 4
                    && last.takeEssentia(aspect, 4, Direction.DOWN, EssentiaTransferMode.EXECUTE) == 4,
                    "New local stock participates without rebuilding the topology");
            h.assertTrue(journal.receipt(other).amount() == 5, "A separate network's pending matter is never consumed");
        } finally {
            for (var outlet : List.of(local, other)) {
                var receipt = journal.receipt(outlet);
                if (receipt != null) journal.accepted(receipt, receipt.amount());
            }
            for (var pipe : List.of(first, middle, last, foreign)) level.setBlock(pipe.getBlockPos(), Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(jar, Blocks.AIR.defaultBlockState(), 3);
        }
        h.succeed();
    }
}
