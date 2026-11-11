package dev.senluo.witchcraft.gametest;

import dev.senluo.witchcraft.time.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class TissueDataGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=20)
    public static void futureTissuesRemainPortableWithoutSpending(GameTestHelper h) {
        Tissue original = new Tissue();
        original.tree = true;
        original.rootStage = 4;
        original.setCoreQuantity(700*Tissue.TU);
        original.sides.add(Tissue.Side.graft(new Tissue()));
        CompoundTag future = TissueTag.save(original);
        future.getList("Sides", Tag.TAG_COMPOUND).getCompound(0).getCompound("Tissue").putInt("Version", 999);
        Tissue suspended = TissueTag.load(future);
        h.assertTrue(!suspended.supported() && suspended.room() == 0 && suspended.spendable() == 0,
                "An unreadable child suspends the complete organism account");
        h.assertTrue(!suspended.credit(Tissue.TU) && !TimeStorage.of(suspended).spend(Tissue.TU)
                && suspended.receiveRitual(Tissue.TU) == 0, "Unsupported tissue cannot earn or spend resources");
        Tissue portable = TissueItem.read(TissueItem.stack(suspended));
        h.assertTrue(future.equals(TissueTag.save(portable)), "A portable core retains the entire unknown payload");
        h.assertTrue(TissueTag.load(new CompoundTag()).supported(), "A normal new item is still usable");
        CompoundTag unversioned = new CompoundTag();
        unversioned.putLong("Quantity", 123456);
        h.assertTrue(!TissueTag.load(unversioned).supported(), "Nonempty unversioned data is not a new seedling");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=20)
    public static void futureWoodMemoryNeverRegrowsPrunedWood(GameTestHelper h) {
        var core = TreeGameTests.plant(h, true);
        BlockPos relative = new BlockPos(1, 1, 0);
        core.tissue.shapeSpace.remember(core.tissue, relative, TreeShapeSpace.state(Direction.Axis.X));
        core.tissue.shapeSpace.prune(core.tissue, relative);
        CompoundTag future = TissueTag.save(core.tissue);
        future.getCompound("WoodMemory").putInt("Version", 999);
        core.tissue = TissueTag.load(future);
        var trees = TemporalWorld.get(h.getLevel()).trees;
        trees.register(core);
        trees.tick(core);
        trees.flush();
        h.assertTrue(future.equals(TissueTag.save(core.tissue)), "Registration cannot bootstrap over an unknown pruning format");
        h.assertTrue(h.getLevel().getBlockState(core.getBlockPos().offset(relative)).isAir(),
                "Suspended structure memory cannot recreate pruned wood");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=20)
    public static void blockDisplayCannotReloadAuthoritativeAccounts(GameTestHelper h) {
        var core = TreeGameTests.plant(h, true);
        core.tissue.setCoreQuantity(234*Tissue.TU);
        CompoundTag saved = TissueTag.save(core.tissue);
        CompoundTag display = core.getUpdateTag(h.getLevel().registryAccess());
        h.assertTrue(!display.contains("Tissue") && !display.contains("WoodMemory")
                && !display.contains("OutputBudget"), "Block display excludes portable structure and output accounts");
        core.handleUpdateTag(display, h.getLevel().registryAccess());
        h.assertTrue(saved.equals(TissueTag.save(core.tissue)), "A display callback cannot alter a server account");
        var fruit = new FruitEntity(core.getBlockPos().above(), TemporalContent.FRUIT.get().defaultBlockState());
        fruit.corePos = core.getBlockPos();
        fruit.invested = 345*Tissue.TU;
        CompoundTag fruitDisplay = fruit.getUpdateTag(h.getLevel().registryAccess());
        h.assertTrue(!fruitDisplay.contains("CorePos") && !fruitDisplay.contains("Invested")
                && !fruitDisplay.contains("HarvestRoll"), "Fruit display excludes ownership, payments and drop rolls");
        h.succeed();
    }
}
