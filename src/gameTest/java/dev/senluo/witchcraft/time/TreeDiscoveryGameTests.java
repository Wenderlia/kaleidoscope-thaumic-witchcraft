package dev.senluo.witchcraft.time;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class TreeDiscoveryGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty")
    public static void interruptedSearchRetainsRootedPathsAndFindsRemoteDetour(GameTestHelper h) {
        var root = BlockPos.ZERO;
        var world = new HashSet<BlockPos>();
        for (int x=-8;x<=8;x++) world.add(root.east(x));
        for (int z=1;z<=2;z++) {
            world.add(new BlockPos(2,0,z));
            world.add(new BlockPos(4,0,z));
        }
        world.add(new BlockPos(3,0,3));
        var search = new TreeDiscovery(root);
        settle(search,world,world,h);
        var east = root.east(3);
        var west = root.west(3);
        world.remove(east);
        h.assertFalse(TreeShapeSpace.neighboursStayConnected(east,world::contains),"The east bypass is outside the local shell");
        search.remove(east);
        search.trimOne();
        world.remove(west);
        search.remove(west);
        h.assertTrue(search.proven(root.west()),"A cut leaves the proven core side available during retraction");
        var attached = new HashSet<>(world);
        for (int x=4;x<=8;x++) attached.remove(root.west(x));
        settle(search,world,attached,h);
        h.assertTrue(search.proven(root.east(8)) && !search.proven(root.west(8)),
                "The longer east route reconnects and the west tip remains detached");
        world.add(west);
        search.revisit(west);
        settle(search,world,world,h);
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=40)
    public static void graftAndNaturalLeavesShareEachGrowthStage(GameTestHelper h) {
        Tissue tree = new Tissue();
        tree.tree = true;
        BlockPos offset = new BlockPos(1, 0, 0);
        var leaves = TemporalContent.TIME_LEAVES.get();
        var graft = leaves.defaultBlockState().setValue(LeavesBlock.WATERLOGGED, true)
                .setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        var shape = tree.shapeSpace;
        shape.remember(tree, offset, graft);
        for (int stage = -1; stage <= 4; stage++) {
            var template = TreeShapeSpace.template(tree, stage);
            h.assertTrue(shape.commit(shape.preview(template, stage)), "Stage commits");
            boolean mature = stage == 4;
            h.assertTrue(shape.grafted(offset.asLong())
                    && shape.cells().get(offset.asLong()) == graft.setValue(TreeLeavesBlock.GREAT_TREE, mature),
                    "Only the graft's stage changes at growth stage " + stage);
            var natural = shape.cells().entrySet().stream()
                    .filter(e -> !shape.grafted(e.getKey()) && e.getValue().is(leaves)).findFirst().orElseThrow();
            h.assertTrue(natural.getValue().getValue(TreeLeavesBlock.GREAT_TREE) == mature,
                    "The natural leaf reaches the same stage");
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty")
    public static void permanentCutsKeepRootProofThroughSaveAndGraft(GameTestHelper h) {
        var tree = new Tissue();
        tree.tree = true;
        var shape = tree.shapeSpace;
        var root = BlockPos.ZERO;
        var side = root.east().south();
        var spur = root.east(2).south();
        var wood = TemporalContent.TIME_LOG.get().defaultBlockState();
        var template = new java.util.LinkedHashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        for (BlockPos p : java.util.List.of(root.east(), root.east(2), root.east(3), root.east(4), side, spur, root.west()))
            template.put(p, wood);
        h.assertTrue(shape.commit(shape.preview(template, 0)), "Publish a proven connected shape");
        h.assertTrue(shape.prune(tree, side) && !shape.calculatingCuts()
                && shape.takeRemoved().equals(Set.of(side.asLong())), "The local detour still publishes the actual cut");

        var saved = shape.save();
        h.assertTrue(TreeShapeSpace.readable(saved), "The fixture must use registered tree tissue");
        shape.load(saved, tree);
        h.assertTrue(shape.prune(tree, root.west()) && shape.calculatingCuts(), "Loaded topology gets a fresh proof");
        int reachable = shape.cells().size();
        h.assertTrue(shape.advanceCuts(reachable + 1) == reachable + 1 && !shape.calculatingCuts(),
                "Visiting the root and all remaining cells completes without a second classification");
        shape.takeRemoved();

        h.assertTrue(shape.prune(tree, root.east()) && shape.calculatingCuts(), "A bridge needs the full root walk");
        shape.advanceCuts(2); // The classification of the detached branch has already begun.
        var detour = root.east().north();
        h.assertTrue(shape.remember(tree, detour, wood), "A new graft reconnects the branch during classification");
        shape.save();
        h.assertTrue(shape.has(root.east(4).asLong()) && shape.takeRemoved().equals(Set.of(root.east().asLong())),
                "Immediate save uses the revised topology and retains the reconnected branch");

        h.assertTrue(shape.prune(tree, detour) && shape.prune(tree, root.east(4)) && shape.calculatingCuts(),
                "An easy second cut cannot cancel the unresolved first cut");
        var finalShape = shape.save();
        h.assertTrue(shape.cells().isEmpty() && shape.pruned(spur.asLong()), "Only the rooted component survives");
        shape.load(finalShape, tree);
        h.assertTrue(shape.cells().isEmpty() && shape.pruned(root.east(2).asLong()), "Reload preserves permanent cuts");
        h.succeed();
    }

    private static void settle(TreeDiscovery search, Set<BlockPos> world, Set<BlockPos> expected, GameTestHelper h) {
        while (search.trimming() || !search.frontier.isEmpty()) {
            for (BlockPos pos : search.connected) {
                h.assertTrue(!search.proven(pos) || expected.contains(pos),"Partial evidence cannot include a floating branch");
            }
            if (search.trimming()) search.trimOne();
            else {
                BlockPos parent = search.frontier.removeFirst();
                for (BlockPos offset : TreeShapeSpace.NEIGHBORS) {
                    BlockPos pos = parent.offset(offset);
                    if (search.seen.add(pos) && world.contains(pos)) search.connect(pos,parent);
                }
            }
        }
        h.assertTrue(search.connected.equals(expected),"Every reachable branch is present after the queue drains");
    }
}
