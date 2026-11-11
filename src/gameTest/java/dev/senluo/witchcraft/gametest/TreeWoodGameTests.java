package dev.senluo.witchcraft.gametest;

import dev.senluo.witchcraft.time.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import dev.senluo.witchcraft.ritual.TreeBond;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class TreeWoodGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void overlappingClaimsSurviveIndexReloadUntilCoreRemoval(GameTestHelper h) {
        var first = TreeGameTests.plant(h, true);
        var level = h.getLevel();
        var trees = TemporalWorld.get(level).trees;
        BlockPos other = first.getBlockPos().east(2), shared = first.getBlockPos().offset(1,3,0);
        level.setBlock(other.below(), net.minecraft.world.level.block.Blocks.FARMLAND.defaultBlockState(), 3);
        level.setBlock(other, TemporalContent.CORE.get().defaultBlockState(), 3);
        var second = (LivingEntity)level.getBlockEntity(other);
        second.tissue.rootStage = 4; second.tissue.tree = true; second.changed();
        trees.register(second);
        var claims = TreeClaims.get(level);
        h.assertTrue(first.getBlockPos().equals(claims.ownerAt(shared)), "An overlap has exactly one established owner");
        trees.unregister(first.getBlockPos());
        trees.unregister(other);
        trees.register(second);
        h.assertTrue(first.getBlockPos().equals(claims.ownerAt(shared)), "Removing a loaded index does not relinquish ownership");
        trees.register(first);
        h.assertTrue(first.getBlockPos().equals(claims.ownerAt(shared)), "Reverse registration cannot steal the overlap");
        level.removeBlock(first.getBlockPos(), false);
        trees.tick(second);
        h.assertTrue(other.equals(claims.ownerAt(shared)), "Only actual core removal allows the remaining tree to claim the position");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void ownedBranchChalkCannotBeMinedByStranger(GameTestHelper h) {
        var core = TreeGameTests.plant(h, true);
        TreeGameTests.crown(h, core);
        var trees = TemporalWorld.get(h.getLevel()).trees;
        trees.tick(core);
        var player = DevelopmentGameTests.player(h, core);
        core.tissue.bond = TreeBond.of(java.util.UUID.randomUUID(), "other-owner");
        core.changed();
        BlockPos support = core.getBlockPos().above(4), chalk = support.above();
        h.getLevel().setBlock(chalk, dev.senluo.witchcraft.ritual.RitualContent.CHALK_SURFACE.get().defaultBlockState(), 3);
        var event = new net.neoforged.neoforge.event.level.BlockEvent.BreakEvent(h.getLevel(), chalk,
                h.getLevel().getBlockState(chalk), player);
        dev.senluo.witchcraft.ritual.RitualChalk.protectChalk(event);
        h.assertTrue(event.isCanceled(), "Mining a chalk layer cannot bypass its owned wooden support");
        core.tissue.bond = TreeBond.of(player.getUUID(), "owner"); core.changed();
        var allowed = new net.neoforged.neoforge.event.level.BlockEvent.BreakEvent(h.getLevel(), chalk,
                h.getLevel().getBlockState(chalk), player);
        dev.senluo.witchcraft.ritual.RitualChalk.protectChalk(allowed);
        h.assertTrue(!allowed.isCanceled(), "The actual owner can remove chalk");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void woodMemoryAcceptsThreeDimensionalCorners(GameTestHelper h) {
        var core = TreeGameTests.plant(h, true);
        TreeGameTests.crown(h, core);
        BlockPos corner = core.getBlockPos().offset(1,2,1), tip = core.getBlockPos().offset(2,3,2);
        var log = TreeShapeSpace.state(Direction.Axis.Z);
        h.getLevel().setBlock(corner, log, 3);
        h.getLevel().setBlock(tip, log, 3);
        TemporalWorld.get(h.getLevel()).trees.tick(core);
        for (int i=0; i<16; i++) TemporalWorld.get(h.getLevel()).trees.flush();
        h.assertTrue(core.tissue.shapeSpace.has(corner.subtract(core.getBlockPos()).asLong())
                && core.tissue.shapeSpace.has(tip.subtract(core.getBlockPos()).asLong()), "A diagonal chain across X, Y and Z is connected");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void ownedAxePruningCommitsOnlyAfterSuccessfulRemoval(GameTestHelper h) {
        var core = TreeGameTests.plant(h, true);
        TreeGameTests.crown(h, core);
        core.tissue.setCoreQuantity(1000*Tissue.TU);
        var trees = TemporalWorld.get(h.getLevel()).trees;
        trees.tick(core);
        for (int i=0; i<8; i++) trees.flush();
        var player = DevelopmentGameTests.player(h, core);
        player.setShiftKeyDown(true);
        ItemStack axe = new ItemStack(Items.IRON_AXE);
        axe.setDamageValue(axe.getMaxDamage()-1);
        player.setItemInHand(InteractionHand.MAIN_HAND, axe);
        core.tissue.bond = TreeBond.of(player.getUUID(), "owner");
        BlockPos log = core.getBlockPos().above();
        long relative = log.subtract(core.getBlockPos()).asLong();
        long oldReserve = trees.restorationReserve(core);
        java.util.function.Consumer<net.neoforged.neoforge.event.level.BlockEvent.BreakEvent> deny = event -> {
            if (event.getPos().equals(log) && event.getPlayer() == player) event.setCanceled(true);
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(deny);
        try {
            player.gameMode.destroyBlock(log);
            h.assertTrue(h.getLevel().getBlockState(log).is(TemporalContent.TIME_LOG.get())
                    && !core.tissue.shapeSpace.pruned(relative), "Canceled mining cannot prune or consume the axe");
        } finally { net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(deny); }
        player.gameMode.destroyBlock(log);
        h.assertTrue(core.tissue.shapeSpace.pruned(relative) && player.getMainHandItem().isEmpty(),
                "The last durability point still performs owner pruning");
        h.assertTrue(trees.restorationReserve(core) <= oldReserve, "Pruned wood cannot reserve additional restoration funds");
        Tissue portable = TissueItem.read(TissueItem.stack(core.tissue));
        h.assertTrue(portable.shapeSpace.pruned(relative), "The portable core preserves pruning");
        trees.unregister(core.getBlockPos());
        core.tissue = portable;
        trees.register(core);
        trees.tick(core);
        for (int i=0; i<8; i++) trees.flush();
        h.assertTrue(h.getLevel().getBlockState(log).isAir(), "Neither reloading nor the natural template regrows the pruned position");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void connectedWoodRemembersShapeAndPaysForRepairs(GameTestHelper h) {
        var core = TreeGameTests.plant(h, true);
        TreeGameTests.crown(h, core);
        var trees = TemporalWorld.get(h.getLevel()).trees;
        trees.tick(core);
        for (int i=0; i<8; i++) trees.flush();
        long originalReserve = trees.restorationReserve(core);
        BlockPos origin = core.getBlockPos();
        var log = TreeShapeSpace.state(Direction.Axis.X);
        BlockPos bridge = origin.offset(1,1,0), tip = origin.offset(2,1,0);
        BlockPos outside = origin.offset(TreeShapeSpace.TREE_RADIUS+1,1,0), isolated = origin.offset(-8,1,8);
        for (BlockPos p : new BlockPos[]{bridge, tip, outside, isolated}) h.getLevel().setBlock(p, log, 3);
        trees.tick(core);
        for (int i=0; i<16; i++) trees.flush();
        h.assertTrue(core.tissue.shapeSpace.has(tip.subtract(origin).asLong()), "Connected wood inside 5x5 is recorded");
        h.assertTrue(!core.tissue.shapeSpace.has(outside.subtract(origin).asLong())
                && !core.tissue.shapeSpace.has(isolated.subtract(origin).asLong()), "Out-of-range and isolated wood is not recorded");
        h.assertTrue(trees.restorationReserve(core) == originalReserve,
                "Intact grafts do not reserve money for nonexistent damage");
        core.tissue.setCoreQuantity(1000*Tissue.TU);
        long before = core.tissue.coreQuantity();
        h.getLevel().removeBlock(bridge, false);
        h.getLevel().removeBlock(tip, false);
        for (int i=0; i<32; i++) trees.flush();
        h.assertTrue(h.getLevel().getBlockState(bridge).equals(log) && h.getLevel().getBlockState(tip).equals(log),
                "Breaking the connecting branch preserves both positions and their axes");
        h.assertTrue(core.tissue.coreQuantity() == before - 2*TreeBalance.LOG_REPAIR, "Player-added logs pay the normal repair fee");
        Tissue portable = TissueItem.read(TissueItem.stack(core.tissue));
        h.assertTrue(portable.shapeSpace.entries().equals(core.tissue.shapeSpace.entries()), "Core item preserves the complete wood memory");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void woodMemoryRespectsStageBoundsAndSaveValidation(GameTestHelper h) {
        Tissue t = new Tissue(); t.tree = true; t.rootStage = 4;
        var log = TemporalContent.TIME_LOG.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
        h.assertTrue(t.shapeSpace.remember(t, new BlockPos(2,6,2), log), "Small tree includes its horizontal edge and crown envelope");
        h.assertTrue(!t.shapeSpace.remember(t, new BlockPos(TreeShapeSpace.TREE_RADIUS + 1,1,0), log), "Small tree cannot record beyond its own radius");
        t.giant = true;
        h.assertTrue(t.shapeSpace.remember(t, new BlockPos(5,-4,5), log), "Great tree includes its lower and horizontal bounds");
        h.assertTrue(!t.shapeSpace.remember(t, new BlockPos(TreeShapeSpace.GIANT_RADIUS + 1,0,0), log)
                && !t.shapeSpace.remember(t, new BlockPos(0,25,0), log)
                && !t.shapeSpace.remember(t, BlockPos.ZERO, log), "No memory beyond the envelope or over the core");
        t.shapeSpace.initialize();
        Tissue loaded = TissueTag.load(TissueTag.save(t));
        h.assertTrue(loaded.shapeSpace.entries().equals(t.shapeSpace.entries()), "Axes and positions survive world serialization");
        var malformed = t.shapeSpace.save();
        malformed.putIntArray("States",new int[0]);
        loaded.shapeSpace.load(malformed, loaded);
        h.assertTrue(loaded.shapeSpace.entries().isEmpty() && !loaded.shapeSpace.initialized(), "An incomplete material record cannot partially restore a structure");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void leafGraftsPreserveProvenanceAndPruningAcrossSave(GameTestHelper h) {
        Tissue tree = new Tissue(); tree.tree = true; tree.rootStage = 4;
        BlockPos pos = new BlockPos(5, 2, 0);
        var leaf = TemporalContent.TIME_LEAVES.get().naturalState()
                .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true)
                .setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        h.assertTrue(tree.shapeSpace.rememberLeaf(tree, pos, leaf), "A graft records actual axis and placement provenance");
        Tissue loaded = TissueTag.load(TissueTag.save(tree));
        h.assertTrue(loaded.supported() && loaded.shapeSpace.leaves().equals(tree.shapeSpace.leaves()),
                "Leaf memory survives portable organism serialization");
        var restored = loaded.shapeSpace.cells().get(pos.asLong());
        h.assertTrue(restored.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)
                && !restored.getValue(TreeLeavesBlock.GREAT_TREE)
                && loaded.shapeSpace.grafted(pos.asLong())
                && restored.getValue(RotatedPillarBlock.AXIS) == Direction.Axis.X,
                "A graft keeps its material, visual state, axis and source across save");
        h.assertTrue(loaded.shapeSpace.prune(loaded, pos) && !loaded.shapeSpace.has(pos.asLong()),
                "Pruning removes the graft from the repair memory");
        Tissue pruned = TissueTag.load(TissueTag.save(loaded));
        h.assertTrue(pruned.shapeSpace.pruned(pos.asLong()) && pruned.shapeSpace.leaves().isEmpty(),
                "Pruning persists without reconstructing a leaf");
        var malformed = TissueTag.save(tree);
        malformed.getCompound("WoodMemory").putIntArray("States",new int[]{-1});
        h.assertTrue(!TissueTag.load(malformed).supported(), "Invalid leaf provenance quarantines the full tissue");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=80)
    public static void connectedLeafGraftsRepairWithoutBecomingWoodSupply(GameTestHelper h) {
        var core = TreeGameTests.plant(h, true);
        var level = h.getLevel(); var trees = TemporalWorld.get(level).trees;
        BlockPos origin = core.getBlockPos();
        TreeGameTests.crown(h, core);
        for (int x=1; x<=3; x++) level.setBlock(origin.offset(x,1,0), TreeShapeSpace.state(Direction.Axis.X), 3);
        BlockPos first = origin.offset(4,2,1), tip = origin.offset(5,3,2), detachedWood = origin.offset(6,4,3);
        var leaf = TemporalContent.TIME_LEAVES.get().naturalState()
                .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
        level.setBlock(first, leaf, 3); level.setBlock(tip, leaf, 3);
        level.setBlock(detachedWood, TreeShapeSpace.state(Direction.Axis.Y), 3);
        trees.beforeWoodRemoval(tip);
        h.assertTrue(core.tissue.shapeSpace.leaves().containsKey(first.subtract(origin).asLong())
                && core.tissue.shapeSpace.leaves().containsKey(tip.subtract(origin).asLong()),
                "Leaf-to-leaf corner grafts are recorded before quick removal");
        h.assertTrue(core.tissue.shapeSpace.entries().containsKey(detachedWood.subtract(origin).asLong()),
                "Leaves connect the far wood into the shape; machine supply has its own wood-only query");
        core.tissue.setCoreQuantity(1000*Tissue.TU);
        level.removeBlock(first, false); level.removeBlock(tip, false);
        h.assertTrue(trees.plannedLeaf(core, first) == TreeLogic.LeafSupport.PRESENT
                && trees.plannedLeaf(core, tip) == TreeLogic.LeafSupport.PRESENT,
                "Fruit support sees remembered grafts even when the real leaf bridge is missing");
        long reserve = trees.restorationReserve(core);
        h.assertTrue(reserve == 0, "Unknown physical connectivity does not authorize a paid repair reserve");
        for (int i=0; i<64; i++) trees.flush();
        h.assertTrue(level.getBlockState(first).is(TemporalContent.TIME_LEAVES.get())
                && level.getBlockState(tip).is(TemporalContent.TIME_LEAVES.get())
                && level.getBlockState(tip).getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT),
                "Both remembered grafts regrow even after the leaf bridge is removed");
        h.succeed();
    }
}
