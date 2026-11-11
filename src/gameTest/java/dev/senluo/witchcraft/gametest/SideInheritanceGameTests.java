package dev.senluo.witchcraft.gametest;

import dev.senluo.witchcraft.time.*;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class SideInheritanceGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=40)
    public static void inheritedCapacityStillRequiresRealRootGrowth(GameTestHelper h) {
        Tissue mother = new Tissue(); mother.rootStage = 4;
        GiantRoots.restore(mother);
        Tissue.Side first = mother.sides.getFirst();
        h.assertTrue(first.capacity() == Tissue.TU && !first.mature(), "A replacement starts with only one TU of space");
        first.advance(Tissue.ROOT_TICKS / 2);
        Tissue saved = TissueTag.load(TissueTag.save(mother));
        h.assertTrue(saved.sides.getFirst().capacity() == 48_000 * Tissue.TU && saved.stored() == 0,
                "A half-grown inherited root survives saving with half its target and no invented stock");
        first.advance(Tissue.ROOT_TICKS / 2);
        h.assertTrue(first.capacity() == 96_000 * Tissue.TU && first.mature(), "A plant's mature side is one capacity stage lower");
        mother.tree = true; GiantRoots.synchronize(mother);
        h.assertTrue(first.capacity() == 384_000 * Tissue.TU, "Tree formation raises existing mature roots to the plant tier");
        mother.giant = true; GiantRoots.restore(mother);
        h.assertTrue(mother.selectedSide().capacity() == 15_360_000 * Tissue.TU && mother.selectedSide().tissue.treeRoot,
                "A great tree produces one ordinary-tree-capacity root");
        h.assertTrue(mother.sides.isEmpty() && mother.rootProduction != null
                && mother.capacity() == 430_080_000 * Tissue.TU && mother.stored() == 0,
                "Promotion folds root capacity into the trunk, keeps one output and never creates TU");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=40)
    public static void inheritedCapacityAndBonusesSurviveCurrentReload(GameTestHelper h) {
        Tissue mother = new Tissue(); mother.rootStage = 4;
        Tissue.Side side = new Tissue.Side(4); side.advance(Tissue.ROOT_TICKS / 2);
        side.locked = true;
        side.tissue.setCoreQuantity(77 * Tissue.TU); side.tissue.amberStage = 1;
        side.tissue.ritualCapacity = 1_234 * Tissue.TU;
        mother.sides.add(side);
        GiantRoots.synchronize(mother);
        Tissue restored = TissueTag.load(TissueTag.save(mother));
        GiantRoots.synchronize(restored);
        Tissue reloaded = TissueTag.load(TissueTag.save(restored));
        Tissue.Side result = reloaded.sides.getFirst();
        h.assertTrue(result.capacity() == (48_000 + 4_800 + 1_234) * Tissue.TU,
                "Inherited capacity and permanent bonuses remain separate through saving");
        h.assertTrue(result.locked && result.stored() == 77 * Tissue.TU && result.tissue.identity.equals(side.tissue.identity)
                && result.tissue.growth.ticks == Tissue.ROOT_TICKS / 2 && reloaded.sides.size() == 1,
                "Current saving preserves reservations, money, maturity and root identity without spawning roots");
        result.tissue.rootStage = 4; result.tissue.birthCapacity = 9_600 * Tissue.TU;
        long higher = result.capacity(); GiantRoots.synchronize(reloaded);
        h.assertTrue(result.capacity() == higher, "An older upgraded or grafted root is never downgraded to the natural target");
        var future = TissueTag.save(reloaded); future.putInt("Version", 999);
        Tissue unknown = TissueTag.load(future); GiantRoots.restore(unknown);
        h.assertTrue(!unknown.supported() && TissueTag.save(unknown).equals(future), "Unknown organisms remain opaque and unchanged");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=40)
    public static void batchHarvestMovesSixtyPercentWithoutDuplicatingIt(GameTestHelper h) {
        LivingEntity entity = new LivingEntity(BlockPos.ZERO, TemporalContent.RHIZOME.get().defaultBlockState());
        entity.tissue.rootStage = 4; GiantRoots.restore(entity.tissue);
        for (Tissue.Side side : entity.tissue.sides) side.advance(Tissue.ROOT_TICKS);
        Colony group = new Colony(List.of(entity), List.of());
        group.credit(10_000 * Tissue.TU, entity);
        List<Tissue> harvested = group.harvestBatch(entity);
        long carried = harvested.stream().mapToLong(Tissue::stored).sum();
        h.assertTrue(harvested.size() == 6 && carried == 6_000 * Tissue.TU && group.quantity() == 4_000 * Tissue.TU,
                "Mature side capacity owns sixty percent; harvested and remaining accounts conserve every TU");
        h.assertTrue(entity.tissue.sides.stream().noneMatch(Tissue.Side::mature)
                && group.capacity() == 384_006 * Tissue.TU,
                "A fresh replacement cannot instantly restore the capacity or maturity just harvested");
        h.succeed();
    }
}
