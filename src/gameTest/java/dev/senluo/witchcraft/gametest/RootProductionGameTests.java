package dev.senluo.witchcraft.gametest;

import dev.senluo.witchcraft.ritual.TreeBond;
import dev.senluo.witchcraft.time.*;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class RootProductionGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=40)
    public static void greatTreePromotionPreservesAccountsAndFruitSites(GameTestHelper h) {
        Tissue tree = new Tissue(); tree.tree = true; tree.rootStage = 4;
        tree.bond = TreeBond.of(UUID.randomUUID(), "owner"); tree.setCoreQuantity(900 * Tissue.TU);
        GiantRoots.restore(tree);
        for (int i = 0; i < tree.sides.size(); i++) {
            Tissue.Side side = tree.sides.get(i);
            side.advance(Tissue.ROOT_TICKS); side.tissue.setCoreQuantity((i + 1) * Tissue.TU);
        }
        Tissue.Side first = tree.sides.getFirst(); first.locked = true;
        first.tissue.ritualCapacity = 77 * Tissue.TU;
        Tissue.Side nested = new Tissue.Side(1); nested.advance(Tissue.ROOT_TICKS);
        nested.tissue.setCoreQuantity(5 * Tissue.TU); nested.tissue.rootStage = 4;
        first.tissue.sides.add(nested);
        UUID outputIdentity = first.tissue.identity;
        tree.fruitSites.ensure(new BlockPos(0, 1, 0), 1).fruit = new BlockPos(1, 3, 0);
        var sites = tree.fruitSites.save();
        long balance = tree.stored();
        tree = TissueTag.load(TissueTag.save(tree));
        h.assertTrue(tree.readyForGiant(), "Current ordinary-tree state remains eligible after saving");
        tree.giant = true; GiantRoots.restore(tree);
        h.assertTrue(tree.sides.isEmpty() && tree.rootProduction != null && tree.fruitSites.save().equals(sites),
                "Normal promotion replaces six roots with one producer without altering node fruit sites");
        h.assertTrue(tree.capacity() == (430_080_000L + 384_000 + 77) * Tissue.TU && tree.stored() == balance,
                "Promotion grants the established great-tree capacity and retains bonuses and real balances");
        h.assertTrue(tree.rootProduction.ready() && tree.selectedSide().locked
                && tree.selectedSide().tissue.identity.equals(outputIdentity)
                && tree.selectedSide().capacity() == GiantRoots.CAPACITY && tree.selectedSide().tissue.sides.isEmpty(),
                "The mature first root becomes one locked harvest account without nested tissue");
        var saved = TissueTag.save(tree);
        h.assertTrue(TissueTag.save(TissueTag.load(saved)).equals(saved), "Current decoding does not repeat promotion or credit");
        var future = saved.copy(); future.getCompound("RootProduction").putInt("Version", 99);
        Tissue unknown = TissueTag.load(future);
        h.assertTrue(!unknown.supported() && TissueTag.save(unknown).equals(future), "Unknown production state stays opaque and intact");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=40)
    public static void harvestConservesStockAndKeepsCapacityThroughoutCooldown(GameTestHelper h) {
        LivingEntity core = new LivingEntity(BlockPos.ZERO, TemporalContent.CORE.get().defaultBlockState());
        core.tissue.giant = true; GiantRoots.restore(core.tissue);
        Colony colony = new Colony(List.of(core), List.of());
        colony.credit(1_000 * Tissue.TU, core);
        long capacity = colony.capacity(), stock = colony.quantity();
        core.tissue.fruitSites.ensure(BlockPos.ZERO, 5).fruit = new BlockPos(1, 3, 0);
        var fruitSites = core.tissue.fruitSites.save();
        Tissue picked = colony.harvest(core);
        h.assertTrue(picked != null && picked.treeRoot && picked.capacity() == GiantRoots.CAPACITY
                && colony.quantity() + picked.stored() == stock && colony.capacity() == capacity,
                "Harvest moves actual stock once and does not shrink great-tree capacity");
        core.tissue.selectedSide = 23;
        h.assertTrue(core.tissue.selectedSideIndex() == 0 && colony.harvest(core) == null
                && colony.harvestBatch(core).isEmpty(), "Selection or an internal batch call cannot bypass the single cooldown");
        core.tissue.rootProduction.advance(Tissue.ROOT_TICKS - 1);
        h.assertTrue(!core.tissue.rootProduction.ready() && colony.capacity() == capacity, "Cooldown never reduces storage");
        core.tissue.rootProduction.advance(1);
        h.assertTrue(core.tissue.rootProduction.ready() && colony.quantity() + picked.stored() == stock
                && core.tissue.fruitSites.save().equals(fruitSites), "Cooling down grants no TU and does not replace fruit slots");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=40)
    public static void giantEligibilityNeedsOwnerAndSixMatureDirectRoots(GameTestHelper h) {
        Tissue tree = new Tissue(); tree.tree = true; tree.rootStage = 4;
        GiantRoots.restore(tree);
        for (Tissue.Side side : tree.sides) side.advance(Tissue.ROOT_TICKS);
        h.assertTrue(!tree.readyForGiant(), "Age and mature roots alone do not establish ownership");
        tree.inheritBond(TreeBond.of(UUID.randomUUID(), "owner"));
        h.assertTrue(tree.readyForGiant(), "Owned tree with six mature direct roots is eligible without full storage");
        tree.sides.getFirst().tissue.growth.ticks--;
        h.assertTrue(!tree.readyForGiant(), "One immature root stops the leap");
        tree.sides.getFirst().advance(1); tree.sides.removeLast();
        h.assertTrue(!tree.readyForGiant(), "Five mature roots are insufficient");
        h.succeed();
    }
}
