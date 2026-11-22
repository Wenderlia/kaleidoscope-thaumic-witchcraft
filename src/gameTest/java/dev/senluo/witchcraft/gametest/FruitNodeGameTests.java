package dev.senluo.witchcraft.gametest;

import dev.senluo.witchcraft.time.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Prepared regression scenarios; compilation does not imply these have run. */
@PrefixGameTestTemplate(false)
public final class FruitNodeGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=100)
    public static void nodeCreatesOneDirectFruitAndKeepsItsSite(GameTestHelper h) {
        var core = TreeGameTests.plant(h,true);
        TreeGameTests.crown(h,core);
        var trees = TemporalWorld.get(h.getLevel()).trees;
        trees.tick(core);
        h.assertTrue(trees.fruits(core).isEmpty(), "No automatic side-root fruit in a new tree");
        BlockPos node = core.getBlockPos().above();
        h.getLevel().setBlock(node,FruitWoods.FAMILIES.get(1).node().get().defaultBlockState(),3);
        for (int i=0;i<20;i++) { trees.tick(core); trees.flush(); }
        var fruit = trees.fruits(core).getFirst();
        var decor = core.getBlockPos().offset(6,1,5);
        h.getLevel().setBlock(decor,Blocks.STONE.defaultBlockState(),3);
        h.getLevel().setBlock(decor,Blocks.ANDESITE.defaultBlockState(),3);
        h.assertTrue(fruit.limit()==2,"An unrelated decoration leaves the current honey route usable");
        h.assertTrue(trees.fruits(core).size()==1 && fruit.grade==0 && fruit.targetGrade==2,
                "One honey node starts one common juvenile, not an intermediate dawn fruit");
        h.assertTrue(fruit.remainingFee()==6000*Tissue.TU && fruit.remainingTicks()==6000,
                "Honey keeps the old cumulative price and five-minute work contract");
        var id=fruit.sideIdentity; var pos=fruit.getBlockPos();
        var saved=TissueItem.read(TissueItem.stack(core.tissue));
        h.assertTrue(saved.supported() && saved.fruitSites.get(new BlockPos(0,1,0)).identity.equals(id),
                "Portable tree retains the source slot identity");
        h.assertTrue(saved.shapeSpace.state(new BlockPos(0,1,0).asLong(),Direction.Axis.Y)
                .is(FruitWoods.FAMILIES.get(1).channel().get()), "Repair memory retains wood, not a duplicable functional node");
        trees.invalidateGeometry(); trees.tick(core);
        for(int i=0;i<20;i++)trees.flush();
        h.assertTrue(trees.fruits(core).size()==1 && trees.fruits(core).getFirst().getBlockPos().equals(pos)
                && trees.fruits(core).getFirst().sideIdentity.equals(id), "Cache rebuild neither relocates nor duplicates the fruit");
        h.assertTrue(h.getLevel().getBlockState(node).is(FruitWoods.FAMILIES.get(1).node().get()),
                "An installed live node must not be replaced by its repair material");
        core.tissue.setCoreQuantity(1000*Tissue.TU);
        trees.beforeWoodRemoval(node);
        h.getLevel().removeBlock(node,false);
        h.assertTrue(fruit.limit()==0,"Removing the active source immediately suspends its fruit");
        for(int i=0;i<20;i++)trees.flush();
        h.assertTrue(h.getLevel().getBlockState(node).is(FruitWoods.FAMILIES.get(1).channel().get()),
                "Harvesting a node restores the matching channel, never another node");
        h.succeed();
    }

    @GameTest(batch="regrowthFruitSite", templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=100)
    public static void clearedHangingSiteWakesWaitingSource(GameTestHelper h) {
        var core = TreeGameTests.plant(h,true);
        TreeGameTests.crown(h,core);
        var level = h.getLevel();
        var trees = TemporalWorld.get(level).trees;
        BlockPos origin = core.getBlockPos(), open = null;
        for (var cell : TreeLogic.shape(origin).entrySet()) {
            BlockPos below = cell.getKey().below();
            if (!cell.getValue().is(TemporalContent.TIME_LEAVES.get()) || !level.getBlockState(below).isAir()) continue;
            level.setBlock(below,Blocks.STONE.defaultBlockState(),3);
            open = below;
        }
        h.assertTrue(open != null, "The crown has at least one hanging site");
        level.setBlock(origin.above(),FruitWoods.FAMILIES.get(1).node().get().defaultBlockState(),3);
        for (int i=0;i<30;i++) { trees.tick(core); trees.flush(); }
        h.assertTrue(trees.fruits(core).isEmpty(), "Obstructed sites cannot spawn a fruit");
        level.removeBlock(open,false);
        for (int i=0;i<30;i++) { trees.tick(core); trees.flush(); }
        h.assertTrue(level.getBlockEntity(open) instanceof FruitEntity fruit && fruit.limit()==2
                && trees.fruits(core).size()==1, "Clearing the only site starts one fruit on the original source");
        level.removeBlock(origin,false);
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=20)
    public static void oldNodeMemoriesRestoreChannelsOnEveryAxis(GameTestHelper h) {
        var core=TreeGameTests.plant(h,true);
        BlockPos offset=new BlockPos(0,1,0);
        for(var family:FruitWoods.FAMILIES)for(var axis:Direction.Axis.values()){
            var old=new net.minecraft.nbt.CompoundTag();
            old.putInt("Version",6); old.putBoolean("Initialized",true);
            old.putLongArray("Positions",new long[]{offset.asLong()});
            old.putByteArray("Axes",new byte[]{(byte)(axis==Direction.Axis.X?0:axis==Direction.Axis.Y?1:2)});
            old.putLongArray("LeafPositions",new long[0]); old.putByteArray("LeafStates",new byte[0]);
            old.putLongArray("Pruned",new long[0]); old.put("Carvings",new net.minecraft.nbt.ListTag());
            var ids=new net.minecraft.nbt.ListTag();
            ids.add(net.minecraft.nbt.StringTag.valueOf(family.node().getId().toString()));
            old.put("Materials",ids); // v6 before this fix remembered functional node IDs.
            TreeShapeSpace restored=new TreeShapeSpace();restored.load(old,core.tissue);
            var state=restored.state(offset.asLong(),axis);
            h.assertTrue(state.is(family.channel().get())
                    && state.getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS)==axis,
                    "Every legacy node/axis restores only its matching channel");
        }
        h.succeed();
    }
}
