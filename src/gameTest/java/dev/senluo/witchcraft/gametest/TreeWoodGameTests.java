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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import dev.senluo.witchcraft.ritual.TreeBond;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class TreeWoodGameTests {
    // Observe traversal identity without instrumenting the production loop.
    private static Set<?> publishedPaths(TreeLogic trees, BlockPos origin) {
        try {
            var field = TreeLogic.class.getDeclaredField("livePaths");
            field.setAccessible(true);
            return (Set<?>) ((Map<?,?>) field.get(trees)).get(origin);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    @GameTest(batch="regrowthGiant", templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=240)
    public static void giantLeavesRegrowAsTheyAreSheared(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos origin = h.absolutePos(new BlockPos(19,6,19));
        var layout = GiantTreeShape.layout(Direction.NORTH.get3DDataValue());
        for (var entry : layout.entrySet()) level.setBlock(origin.offset(entry.getKey()),entry.getValue().state(),3);
        level.setBlock(origin.below(),Blocks.FARMLAND.defaultBlockState(),3);
        level.setBlock(origin,TemporalContent.CORE.get().defaultBlockState(),3);
        var core = (LivingEntity)level.getBlockEntity(origin);
        core.tissue.rootStage=4; core.tissue.tree=true; core.tissue.giant=true;
        core.tissue.treeFacing=Direction.NORTH.get3DDataValue();
        core.tissue.setCoreQuantity(1_000_000L*Tissue.TU);
        core.changed();
        TemporalWorld.get(level).add(core);
        BlockPos loose = origin.offset(14,3,14);
        level.setBlock(loose,TemporalContent.TIME_LOG.get().defaultBlockState(),3);
        h.runAfterDelay(140,()->{
            var leaves = layout.keySet().stream().map(origin::offset)
                    .filter(p -> level.getBlockState(p).is(TemporalContent.TIME_LEAVES.get()))
                    .filter(p -> Arrays.stream(Direction.values()).anyMatch(d -> level.getBlockState(p.relative(d)).is(TreeWood.LOGS)))
                    .sorted(Comparator.comparingInt((BlockPos p) -> p.getY()).reversed().thenComparingLong(BlockPos::asLong))
                    .limit(70).toList();
            h.assertTrue(leaves.size()==70,"The mature crown supplies supported leaves for a batch harvest");
            var player = DevelopmentGameTests.player(h,core);
            player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.SHEARS));
            Set<?> graph = publishedPaths(TemporalWorld.get(level).trees, origin);
            h.assertTrue(graph != null && !graph.contains(loose), "The detached wood was already observed outside the tree");
            player.setPos(loose.getX()+0.5,loose.getY(),loose.getZ()+1.5);
            h.assertTrue(player.gameMode.destroyBlock(loose) && graph == publishedPaths(TemporalWorld.get(level).trees,origin),
                    "Mining disconnected wood must reuse the published tree without a synchronous rebuild");
            var sequence=h.startSequence();
            for (var leaf : leaves.subList(0,12)) {
                sequence.thenExecute(()->{
                    player.setPos(leaf.getX()+0.5,leaf.getY(),leaf.getZ()+1.5);
                    h.assertTrue(player.gameMode.destroyBlock(leaf) && level.getBlockState(leaf).isAir(),"Shearing removes the remembered leaf");
                }).thenExecuteAfter(1,()->{
                    var regrown = level.getBlockState(leaf);
                    h.assertTrue(regrown.is(TemporalContent.TIME_LEAVES.get()) && regrown.getValue(TreeLeavesBlock.GREAT_TREE),
                            "Every harvest regrows a mature leaf on the following native tick");
                });
            }
            sequence.thenExecute(()->{
                for (BlockPos leaf:leaves) h.assertTrue(level.removeBlock(leaf,false),"The batch removes every leaf");
            }).thenExecuteAfter(1,()->h.assertTrue(leaves.stream().filter(p->level.getBlockState(p).is(TemporalContent.TIME_LEAVES.get())).count()<=16,
                    "Batch recovery cannot overrun the world's repair budget"))
                    .thenWaitUntil(()->h.assertTrue(leaves.stream().allMatch(p->level.getBlockState(p).is(TemporalContent.TIME_LEAVES.get())),
                            "Every eligible leaf wakes after more than one slice of cuts")).thenSucceed();
        });
    }

    @GameTest(batch="regrowthBridge", templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void twoBridgeCutsCannotAuthorizeFloatingRepairs(GameTestHelper h) {
        var core = TreeGameTests.plant(h,true);
        TreeGameTests.crown(h,core);
        var level=h.getLevel();
        var trees=TemporalWorld.get(level).trees;
        var origin=core.getBlockPos();
        var log=TreeShapeSpace.state(Direction.Axis.X);
        for (int x=1;x<=7;x++) level.setBlock(origin.east(x),log,3);
        var bridge=origin.east(4);
        var parallel=bridge.south();
        var tip=origin.east(7);
        level.setBlock(parallel,log,3);
        core.tissue.setCoreQuantity(1000*Tissue.TU);
        trees.tick(core);
        for (int i=0;i<12;i++) trees.flush();
        h.assertTrue(core.tissue.shapeSpace.has(tip.subtract(origin).asLong()),"The far branch has a restoration target");
        long balance=core.tissue.coreQuantity();
        level.setBlock(bridge,Blocks.STONE.defaultBlockState(),3);
        level.removeBlock(tip,false);
        trees.flush();
        h.assertTrue(level.getBlockState(tip)==log && core.tissue.coreQuantity()==balance-TreeBalance.LOG_REPAIR,
                "One surviving route permits one paid repair");
        level.removeBlock(bridge,false);
        for(int i=0;i<4;i++) trees.flush();
        balance=core.tissue.coreQuantity();
        level.setBlock(parallel,Blocks.STONE.defaultBlockState(),3);
        level.setBlock(bridge,Blocks.STONE.defaultBlockState(),3);
        level.removeBlock(tip,false);
        for(int i=0;i<12;i++) trees.flush();
        h.assertTrue(level.getBlockState(tip).isAir() && core.tissue.coreQuantity()==balance,
                "Cutting both bridges together leaves the detached branch unpaid");
        level.removeBlock(bridge,false);
        for(int i=0;i<12;i++) trees.flush();
        h.assertTrue(level.getBlockState(bridge)==log && level.getBlockState(tip)==log
                && core.tissue.coreQuantity()==balance-2*TreeBalance.LOG_REPAIR,
                "An opening first repairs the bridge, then wakes and pays for the far branch");
        h.succeed();
    }

    @GameTest(batch="regrowthFairness", templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void fiveTreesShareOneConnectionBudget(GameTestHelper h) {
        var level=h.getLevel();
        var world=TemporalWorld.get(level);
        var trees=world.trees;
        var leaves=new ArrayList<BlockPos>();
        for (int i=0;i<5;i++) {
            BlockPos origin=h.absolutePos(new BlockPos(4+7*(i%3),4,4+7*(i/3)));
            level.setBlock(origin.below(),Blocks.FARMLAND.defaultBlockState(),3);
            level.setBlock(origin,TemporalContent.CORE.get().defaultBlockState(),3);
            var core=(LivingEntity)level.getBlockEntity(origin);
            core.tissue.rootStage=4; core.tissue.tree=true; core.tissue.setCoreQuantity(1000*Tissue.TU);
            core.changed(); world.add(core);
            TreeGameTests.crown(h,core);
            trees.tick(core);
            leaves.add(origin.offset(1,3,0));
        }
        for (int i=0;i<64;i++) trees.flush();
        for (var leaf:leaves) level.removeBlock(leaf,false);
        trees.flush();
        long first=leaves.stream().filter(p->level.getBlockState(p).is(TemporalContent.TIME_LEAVES.get())).count();
        h.assertTrue(first>0 && first<=4,"A round serves at most four affected trees");
        for (int i=0;i<4;i++) trees.flush();
        h.assertTrue(leaves.stream().allMatch(p->level.getBlockState(p).is(TemporalContent.TIME_LEAVES.get())),
                "Rotation eventually serves every tree");
        h.succeed();
    }

    @GameTest(batch="regrowthDoor", templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void regrowthPaysForBothDoorHalvesTogether(GameTestHelper h) {
        var core=TreeGameTests.plant(h,true);
        TreeGameTests.crown(h,core);
        var level=h.getLevel();
        var trees=TemporalWorld.get(level).trees;
        var lower=core.getBlockPos().east().above();
        var upper=lower.above();
        var door=YearsWoodContent.DOOR.get().defaultBlockState();
        var half=net.minecraft.world.level.block.DoorBlock.HALF;
        var upperHalf=net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER;
        level.setBlock(lower.below(),Blocks.STONE.defaultBlockState(),3);
        level.setBlock(lower,door,18);
        level.setBlock(upper,door.setValue(half,upperHalf),18);
        trees.tick(core);
        for (int i=0;i<12;i++) trees.flush();
        h.assertTrue(core.tissue.shapeSpace.has(lower.subtract(core.getBlockPos()).asLong())
                && core.tissue.shapeSpace.has(upper.subtract(core.getBlockPos()).asLong()),"Both connected halves were grafted");
        core.tissue.setCoreQuantity(TreeBalance.LOG_REPAIR);
        level.removeBlock(lower,false);
        if (!level.getBlockState(upper).isAir()) level.removeBlock(upper,false);
        level.removeBlock(lower.below(),false);
        for (int i=0;i<4;i++) trees.flush();
        var colony = TemporalWorld.get(level).colony(core);
        h.assertTrue(FruitFeeding.reserve(colony)==0, "A door without its floor leaves all funds available");
        level.setBlock(lower.below(),Blocks.STONE.defaultBlockState(),3);
        h.assertTrue(FruitFeeding.reserve(colony)==2*TreeBalance.LOG_REPAIR, "Two missing halves are one door quote");
        var floor = lower.below();
        var chunk = level.getChunkAt(floor);
        chunk.setBlockState(floor, TemporalContent.TIME_LOG.get().defaultBlockState(), false);
        h.assertTrue(FruitFeeding.reserve(colony)==2*TreeBalance.LOG_REPAIR, "A solid wood floor still supports both halves");
        chunk.setBlockState(floor, Blocks.AIR.defaultBlockState(), false);
        h.assertTrue(FruitFeeding.reserve(colony)==0, "Direct chunk removal of the floor releases the cached door quote");
        level.setBlock(floor,Blocks.STONE.defaultBlockState(),3);
        trees.flush();
        h.assertTrue(level.getBlockState(lower).isAir() && level.getBlockState(upper).isAir()
                && core.tissue.coreQuantity()==TreeBalance.LOG_REPAIR,"A half-funded door waits without spending");
        core.tissue.setCoreQuantity(2*TreeBalance.LOG_REPAIR);
        trees.tick(core);
        for (int i=0;i<4;i++) trees.flush();
        h.assertTrue(level.getBlockState(lower).is(door.getBlock()) && level.getBlockState(upper).is(door.getBlock())
                && level.getBlockState(upper).getValue(half)==upperHalf && core.tissue.coreQuantity()==0,
                "A restored pair charges its two missing halves once");
        level.setBlock(upper,Blocks.AIR.defaultBlockState(),18);
        for (int i=0;i<4;i++) trees.flush();
        h.assertTrue(level.getBlockState(lower).is(door.getBlock()) && FruitFeeding.reserve(colony)==TreeBalance.LOG_REPAIR,
                "A surviving lower half reserves only its missing upper half");
        core.tissue.setCoreQuantity(TreeBalance.LOG_REPAIR);
        trees.tick(core);
        trees.flush();
        h.assertTrue(level.getBlockState(upper).is(door.getBlock()) && core.tissue.coreQuantity()==0,
                "Completing one half costs exactly one repair");
        level.removeBlock(core.getBlockPos(),false);
        h.succeed();
    }

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

    @GameTest(batch="regrowthGraft", templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=80)
    public static void sameTickGraftAndDirectChunkRemovalPreserveTheBranch(GameTestHelper h) {
        var level = h.getLevel();
        var core = TreeGameTests.plant(h,true);
        TreeGameTests.crown(h,core);
        var trees = TemporalWorld.get(level).trees;
        for (int i=0; i<12; i++) trees.flush();
        BlockPos origin = core.getBlockPos(), bridge = origin.east().above(), tip = bridge.east();
        var log = TreeShapeSpace.state(Direction.Axis.X);
        level.setBlock(bridge,log,3);
        level.setBlock(tip,log,3);
        var player = DevelopmentGameTests.player(h,core);
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        player.setPos(bridge.getX()+0.5,bridge.getY(),bridge.getZ()+1.5);
        h.assertTrue(player.gameMode.destroyBlock(bridge) && core.tissue.shapeSpace.has(tip.subtract(origin).asLong()),
                "Harvesting the new bridge in the placement tick captures its far end");
        level.getChunkAt(tip).setBlockState(tip,Blocks.AIR.defaultBlockState(),false);
        core.tissue.setCoreQuantity(2*TreeBalance.LOG_REPAIR);
        for (int i=0; i<24; i++) trees.flush();
        h.assertTrue(level.getBlockState(bridge).equals(log) && level.getBlockState(tip).equals(log)
                && core.tissue.coreQuantity()==0, "Normal and direct chunk removals each restore and charge once");

        var snapshots = level.capturedBlockSnapshots;
        int prior = snapshots.size();
        level.captureBlockSnapshots = true;
        try { level.setBlock(tip,Blocks.AIR.defaultBlockState(),3); }
        finally { level.captureBlockSnapshots = false; }
        level.restoringBlockSnapshots = true;
        try {
            for (int i=snapshots.size()-1; i>=prior; i--) snapshots.remove(i).restore();
        } finally { level.restoringBlockSnapshots = false; }
        core.tissue.setCoreQuantity(TreeBalance.LOG_REPAIR);
        for (int i=0; i<12; i++) trees.flush();
        h.assertTrue(level.getBlockState(tip).equals(log) && core.tissue.coreQuantity()==TreeBalance.LOG_REPAIR
                && trees.restorationReserve(core)==0, "A reverted removal leaves no bill");
        level.removeBlock(origin,false);
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

    @GameTest(batch="matureLeafPlacement", templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=220)
    public static void matureCoreLightsPlayerPlacedGraft(GameTestHelper h) {
        var core = TreeGameTests.plant(h, true);
        var level = h.getLevel();
        var trees = TemporalWorld.get(level).trees;
        core.tissue.giant = true;
        trees.tick(core);
        BlockPos origin = core.getBlockPos(), branch = origin.east(2), graft = branch.east();
        BlockPos loose = origin.west(6);
        var log = TreeShapeSpace.state(Direction.Axis.X);
        level.setBlock(origin.east(), log, 3);
        level.setBlock(branch, log, 3);
        level.setBlock(loose, TemporalContent.TIME_LEAVES.get().defaultBlockState(), 3);
        var player = DevelopmentGameTests.player(h, core);
        var item = new ItemStack(TemporalContent.LEAVES_ITEM.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, item);
        player.gameMode.useItemOn(player, level, item, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(branch).add(0.5, 0, 0), Direction.EAST, branch, false));
        h.assertTrue(level.getBlockState(graft).is(TemporalContent.TIME_LEAVES.get()), "BlockItem placed a real leaf");
        h.succeedWhen(() -> {
            var state = level.getBlockState(graft);
            h.assertTrue(core.tissue.shapeSpace.grafted(graft.subtract(origin).asLong()), "The branch joined this core");
            h.assertTrue(state.getValue(TreeLeavesBlock.GREAT_TREE) && state.getValue(LeavesBlock.PERSISTENT),
                    "A new graft on a mature tree uses the bright model and stays player-placed");
            h.assertTrue(!level.getBlockState(loose).getValue(TreeLeavesBlock.GREAT_TREE)
                    && !core.tissue.shapeSpace.has(loose.subtract(origin).asLong()), "A loose leaf in range stays ordinary");
        });
    }

    @GameTest(batch="matureLeafReload", templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=100)
    public static void matureCoreRepairsOldGraftOnLoad(GameTestHelper h) {
        var core = TreeGameTests.plant(h, true);
        var level = h.getLevel();
        BlockPos offset = new BlockPos(1, 0, 0), pos = core.getBlockPos().offset(offset);
        var oldLeaf = TemporalContent.TIME_LEAVES.get().defaultBlockState()
                .setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z).setValue(LeavesBlock.WATERLOGGED, true);
        level.setBlock(pos, oldLeaf, 3);
        core.tissue.shapeSpace.remember(core.tissue, offset, oldLeaf);
        core.tissue.formedTree.add(offset.asLong());
        var saved = TissueTag.save(core.tissue);
        // Old saves can already have a completed template while the graft still has its ordinary model.
        saved.putBoolean("Giant", true);
        saved.getCompound("WoodMemory").putInt("Phase", 4);
        core.tissue = TissueTag.load(saved);
        var trees = TemporalWorld.get(level).trees;
        trees.register(core);
        h.succeedWhen(() -> {
            var state = level.getBlockState(pos);
            h.assertTrue(state.getValue(TreeLeavesBlock.GREAT_TREE), "Registration repairs the existing world leaf");
            h.assertTrue(state.getValue(LeavesBlock.PERSISTENT) && state.getValue(LeavesBlock.WATERLOGGED)
                    && state.getValue(RotatedPillarBlock.AXIS) == Direction.Axis.Z, "Repair preserves its placed form");
            var reloaded = TissueTag.load(TissueTag.save(core.tissue)).shapeSpace;
            h.assertTrue(reloaded.grafted(offset.asLong()) && reloaded.cells().get(offset.asLong()).getValue(TreeLeavesBlock.GREAT_TREE),
                    "The corrected graft survives the next save");
        });
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
        var regrown = level.getBlockState(tip);
        h.assertTrue(level.getBlockState(first).is(TemporalContent.TIME_LEAVES.get())
                && regrown.is(TemporalContent.TIME_LEAVES.get()) && regrown.getValue(LeavesBlock.PERSISTENT)
                && !regrown.getValue(TreeLeavesBlock.GREAT_TREE),
                "Both grafts regrow on the small tree and the tip keeps its ordinary placed form");
        h.succeed();
    }
}
