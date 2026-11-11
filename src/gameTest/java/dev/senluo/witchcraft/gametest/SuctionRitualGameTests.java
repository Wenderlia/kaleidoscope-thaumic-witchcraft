package dev.senluo.witchcraft.gametest;

import dev.senluo.witchcraft.ritual.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Contract regressions for the multi-ring ledger. Compile with the project; run only on explicit request. */
@PrefixGameTestTemplate(false)
public final class SuctionRitualGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=40, batch="suction-contracts")
    public static void smallestEligibleRingsRemainIndependent(GameTestHelper h) {
        UUID steam = UUID.randomUUID(), suction = UUID.randomUUID(), third = UUID.randomUUID();
        var requests = List.of(new RitualRings.Request(steam, 1), new RitualRings.Request(suction, 2), new RitualRings.Request(third, 1));
        var yes=RitualShape.Match.VALID;var no=RitualShape.Match.INVALID;
        var sparse = RitualRings.allocate(new RitualRings.Layout(yes,no,yes), requests);
        h.assertTrue(sparse.get(steam) == 1 && sparse.get(suction) == 3 && !sparse.containsKey(third), "Small + large can run small + medium rituals");
        var full = RitualRings.allocate(new RitualRings.Layout(yes,yes,yes), requests);
        h.assertTrue(full.get(steam) == 1 && full.get(suction) == 2 && full.get(third) == 3, "One whole smallest eligible ring per ordered claim");
        h.assertTrue(RitualRings.allocate(new RitualRings.Layout(yes,no,no), List.of(new RitualRings.Request(suction, 2), new RitualRings.Request(steam, 1))).get(steam) == 1,
                "An ineligible older ritual cannot occupy a too-small ring");
        h.assertTrue(!RitualRings.allocate(RitualRings.Layout.NONE, requests).containsKey(steam), "A broken ring grants no execution");
        var partial=RitualRings.allocate(new RitualRings.Layout(yes,RitualShape.Match.UNLOADED,yes),requests);
        h.assertTrue(partial.equals(sparse),"Unknown rings retain their own state but cannot grant a slot");
        var largeOnly=RitualRings.allocate(new RitualRings.Layout(no,no,yes),requests);
        h.assertTrue(largeOnly.size()==1&&largeOnly.get(steam)==3,"Large alone supplies exactly one slot, oldest eligible first");
        h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=40, batch="suction-contracts")
    public static void liveInfusionRateAndWholeGroupPrices(GameTestHelper h) {
        var quote = SuctionCharge.quote(33, 31);
        h.assertTrue(quote.flowCost() == 32 && quote.rangeCost() == 16 && quote.canPay(32, 16) && !quote.canPay(31, 16),
                "Only complete aspect groups are charged");
        h.assertTrue(quote.contains(64) && !quote.contains(65), "Ordo expands the spherical take range, not delivery dimensions");
        for (int interval : new int[]{1, 7, 10, 23, 71}) {
            for (int perPulse : new int[]{1, 3, 16}) {
                var rate = new SuctionCharge(3, 0).rate(interval, perPulse);
                long credit = 0, moved = 0;
                for (int tick = 0; tick < 2 * interval; tick++) {
                    var pulse = rate.advance(credit); credit = pulse.credit(); moved += pulse.amount();
                }
                h.assertTrue(moved == 8L * perPulse && credit == 0,
                        "Half of configured infusion throughput, including pulse amount and exact remainder");
            }
        }
        var changed = new SuctionCharge(0, 0).rate(20, 3);
        h.assertTrue(changed.rebaseCredit(5, 20) == 10 && changed.rebaseCredit(5, 0) == 5,
                "Interval reload preserves the fractional unit; legacy credit needs no migration");
        var huge = new SuctionCharge(SuctionCharge.MAX_GROUPS, SuctionCharge.MAX_GROUPS);
        h.assertTrue(huge.contains(1.0e18) && huge.rate(10, 1_000_000).advance(0).amount() == Long.MAX_VALUE,
                "High quotes are scalar arithmetic, not exponent-sized loops or a new gameplay cap");
        h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=40, batch="suction-contracts")
    public static void multipleReceiptsSurviveReloadWithoutChangingIdentity(GameTestHelper h) throws Exception {
        var level = h.getLevel(); var store = new StructuralRitualStore();
        var ref = new RitualCenters.Reference(level.dimension().location(), h.absolutePos(new BlockPos(3, 3, 3)), UUID.randomUUID());
        var center = new RitualCenters.Center(ref, ref.position(), true);
        UUID player = UUID.randomUUID(); CompoundTag terms = new CompoundTag(); terms.putInt("Format", 3); terms.putBoolean("Armed", false);
        // This tests the generic ledger; real vessel qualification is exercised by SteamRitualGameTests.
        var steam = new StructuralRitualStore.Definition(StructuralRitualStore.STEAM.kind(),1,80,new int[]{20,40,60,80},1,Optional.empty());
        var suction = new StructuralRitualStore.Definition(StructuralRitualStore.SUCTION.kind(),1,100,new int[]{20,40,60,80,100},2,Optional.empty());
        var preparation = new StructuralRitualStore.Definition(ResourceLocation.parse("kaleidoscope_witchcraft:test_preparation"),
                1,1,new int[]{1},1,Optional.empty());
        UUID first = store.begin(level, steam, center, player, terms).orElseThrow();
        UUID second = store.begin(level, suction, center, player, terms).orElseThrow();
        UUID third = store.begin(level, preparation, center, player, terms).orElseThrow();
        h.assertTrue(store.begin(level, preparation, center, player, terms).isEmpty(), "Three physical rings cannot host a fourth claim");
        var clone = new RitualCenters.Center(new RitualCenters.Reference(ref.dimension(), ref.position().east(), ref.identity()), ref.position().east(), true);
        h.assertTrue(store.begin(level, steam, clone, player, terms).isEmpty(), "A copied core cannot claim the original identity elsewhere");
        CompoundTag runtime = new CompoundTag(); runtime.putLong("FlowCredit", 13); store.runtime(level, second, runtime);
        var load = StructuralRitualStore.class.getDeclaredMethod("load", CompoundTag.class, HolderLookup.Provider.class); load.setAccessible(true);
        var restored = (StructuralRitualStore)load.invoke(null, store.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
        h.assertTrue(restored.allAt(ref).stream().map(StructuralRitualStore.Handle::id).toList().equals(List.of(first, second, third))
                && restored.runtime(second).getLong("FlowCredit") == 13 && !restored.progress(second).orElseThrow().available(),
                "Order and fractional clock persist; availability must be revalidated");
        restored.end(level, first); restored.finish(first);
        h.assertTrue(restored.allAt(ref).size() == 2 && restored.progress(second).isPresent(), "Ending one receipt does not erase its siblings");
        h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=40, batch="suction-contracts")
    public static void newResearchGatePausesLegacyPreparationButKeepsPaidRun(GameTestHelper h) throws Exception {
        var level=h.getLevel();var store=new StructuralRitualStore();
        var ref=new RitualCenters.Reference(level.dimension().location(),h.absolutePos(new BlockPos(3,3,3)),UUID.randomUUID());
        var center=new RitualCenters.Center(ref,ref.position(),true);
        var player=SteamRitualGameTests.researchedPlayer(h,"legacy-ritual");
        var kind=ResourceLocation.parse("kaleidoscope_witchcraft:test_research_receipt");
        var legacy=new StructuralRitualStore.Definition(kind,1,1,new int[]{1},1,Optional.empty());
        var current=new StructuralRitualStore.Definition(kind,1,1,new int[]{1},1,RitualResearch.thaumcraft("CRUCIBLE_STABILITY"));
        var preparing=store.begin(level,legacy,center,player.getUUID(),new CompoundTag()).orElseThrow();
        var active=store.begin(level,legacy,center,player.getUUID(),new CompoundTag()).orElseThrow();
        var payments=new java.util.concurrent.atomic.AtomicInteger();
        StructuralRitualStore.StepAction pay=(token,terms)->Optional.of(new StructuralRitualStore.PreparedStep(terms,()->{
            payments.incrementAndGet();return true;
        }));
        h.assertTrue(store.advance(level,active,legacy,StructuralRitualStore.Presence.PRESENT,ref,RitualShape.Match.VALID,pay).mayRun()
                &&payments.get()==1,"The old unguarded receipt was already fully paid before the policy changed");
        var load=StructuralRitualStore.class.getDeclaredMethod("load",CompoundTag.class,HolderLookup.Provider.class);load.setAccessible(true);
        var restored=(StructuralRitualStore)load.invoke(null,store.save(new CompoundTag(),level.registryAccess()),level.registryAccess());
        var waiting=restored.advance(level,preparing,current,StructuralRitualStore.Presence.PRESENT,ref,RitualShape.Match.VALID,pay);
        var running=restored.advance(level,active,current,StructuralRitualStore.Presence.PRESENT,ref,RitualShape.Match.VALID,pay);
        h.assertTrue(waiting.phase()==StructuralRitualStore.Phase.PREPARING&&!waiting.mayRun()
                &&restored.progress(preparing).orElseThrow().committedSteps()==0&&restored.progress(preparing).orElseThrow().elapsed()==0,
                "Reloaded unguarded preparation adopts the current research and cannot advance or spend");
        h.assertTrue(restored.allAt(ref).stream().filter(handle->handle.id().equals(preparing)).findFirst().orElseThrow()
                .definition().research().equals(current.research()),"The preparation saves the current requirement instead of retaining a bypass");
        h.assertTrue(running.mayRun()&&payments.get()==1&&restored.progress(active).orElseThrow().committedSteps()==1,
                "A paid active receipt survives the changed requirement and does not replay its payment");
        h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=40, batch="suction-contracts")
    public static void partialInsertionRetainsExtractedMatter(GameTestHelper h) throws Exception {
        var journal = new SuctionTransferStore();
        var outlet = new SuctionTransferStore.Outlet(h.getLevel().dimension().location(), h.absolutePos(new BlockPos(2, 2, 2)));
        var receipt = journal.record(outlet, Direction.EAST, ResourceLocation.parse("thaumcraft:aer"), 8);
        journal.accepted(receipt, 3);
        var remainder = journal.inDimension(outlet.dimension()).getFirst();
        h.assertTrue(remainder.amount() == 5 && journal.holds(outlet), "Partial insertion retains only the undelivered units and blocks another extraction");
        journal.quarantine(remainder);
        var load = SuctionTransferStore.class.getDeclaredMethod("load", CompoundTag.class, HolderLookup.Provider.class); load.setAccessible(true);
        var restored = (SuctionTransferStore)load.invoke(null, journal.save(new CompoundTag(), h.getLevel().registryAccess()), h.getLevel().registryAccess());
        h.assertTrue(restored.inDimension(outlet.dimension()).getFirst().uncertain() && restored.inDimension(outlet.dimension()).getFirst().amount() == 5,
                "An ambiguous foreign commit is saved and never retried as a new transfer");
        h.succeed();
    }
}
