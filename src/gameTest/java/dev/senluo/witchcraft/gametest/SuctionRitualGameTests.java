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
    public static void unknownSupplyPreservesReservationsAndStableWaiting(GameTestHelper h) {
        UUID large = UUID.randomUUID(), small = UUID.randomUUID();
        var requests = List.of(
                new RitualPointAllocation.Request(large, 3, 10L, 0, List.of(10L), true),
                new RitualPointAllocation.Request(small, 1, 20L, 1, List.of(20L), true));
        var confirmed = java.util.Map.of(10L, 0, 20L, 1);
        var possible = java.util.Map.of(10L, 3, 20L, 1);
        var waiting = RitualPointAllocation.observe(confirmed, possible, requests, List.of());
        h.assertTrue(waiting.reservations().size() == 2 && waiting.pending().equals(java.util.Set.of(large)),
                "An unknown ring keeps its receipt but cannot run; independent known supply still runs");
        var restored = RitualPointAllocation.observe(possible, possible, requests, List.of());
        h.assertTrue(restored.pending().isEmpty() && restored.reservations().size() == 2,
                "Loaded evidence resumes both receipts without deleting their reservation");
        var broken = RitualPointAllocation.observe(confirmed, confirmed, requests,
                List.of(new RitualPointAllocation.Loss(10L, 3)));
        h.assertTrue(!broken.reservations().containsKey(large) && broken.reservations().containsKey(small),
                "Confirmed loss releases only the affected receipt");

        var shared = List.of(
                new RitualPointAllocation.Request(small, 1, 10L, 0, List.of(10L, 20L), true),
                new RitualPointAllocation.Request(large, 2, 20L, 1, List.of(10L, 20L), false));
        var low = java.util.Map.of(10L, 2, 20L, 0);
        var high = java.util.Map.of(10L, 3, 20L, 0);
        var first = RitualPointAllocation.observe(low, high, shared, List.of(new RitualPointAllocation.Loss(20L, 2)));
        var reloaded = RitualPointAllocation.observe(low, high, shared.stream().map(r ->
                new RitualPointAllocation.Request(r.id(), r.cost(), r.origin(), r.order(), r.pools(), true)).toList(), List.of());
        h.assertTrue(first.pending().equals(reloaded.pending()),
                "Uncertain waiting order does not depend on first admission or transient loss notifications");
        h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=40, batch="suction-contracts")
    public static void sharedPointsRerouteAndPauseByLostTier(GameTestHelper h) {
        UUID small = UUID.randomUUID(), medium = UUID.randomUUID(), large = UUID.randomUUID();
        var combined = RitualPointAllocation.allocate(java.util.Map.of(10L, 1, 20L, 2), List.of(
                new RitualPointAllocation.Request(large, 3, 10L, 0, List.of(10L, 20L), false)), List.of());
        h.assertTrue(combined.grants().get(large).values().stream().mapToInt(Integer::intValue).sum() == 3,
                "A three-point ritual may combine small and medium pools");
        var rerouted = RitualPointAllocation.allocate(java.util.Map.of(10L, 1, 20L, 1), List.of(
                new RitualPointAllocation.Request(small, 1, 10L, 0, List.of(10L, 20L), true),
                new RitualPointAllocation.Request(medium, 1, 10L, 1, List.of(10L), true)), List.of());
        h.assertTrue(rerouted.grants().get(small).containsKey(20L) && rerouted.grants().get(medium).containsKey(10L),
                "Reroute a flexible claimant before suspending the claimant that can only reach one pool");
        var loss = RitualPointAllocation.allocate(java.util.Map.of(10L, 4), List.of(
                new RitualPointAllocation.Request(medium, 2, 10L, 0, List.of(10L), true),
                new RitualPointAllocation.Request(large, 3, 10L, 1, List.of(10L), true)),
                List.of(new RitualPointAllocation.Loss(10L, 2)));
        h.assertTrue(loss.paused().contains(medium) && loss.grants().containsKey(large),
                "Losing two points pauses the two-point ritual even when the actual deficit is only one");
        var fallback = RitualPointAllocation.allocate(java.util.Map.of(10L, 2), List.of(
                new RitualPointAllocation.Request(small, 1, 10L, 0, List.of(10L), true),
                new RitualPointAllocation.Request(medium, 2, 10L, 1, List.of(10L), true)), List.of());
        h.assertTrue(fallback.paused().contains(small) && fallback.grants().containsKey(medium),
                "Without a loss-tier basis, smaller costs are released first");
        var origin = RitualPointAllocation.allocate(java.util.Map.of(10L, 0, 20L, 2), List.of(
                new RitualPointAllocation.Request(small, 2, 10L, 0, List.of(10L, 20L), true),
                new RitualPointAllocation.Request(medium, 2, 20L, 1, List.of(10L, 20L), true)),
                List.of(new RitualPointAllocation.Loss(10L, 2)));
        h.assertTrue(origin.paused().contains(small) && origin.grants().containsKey(medium),
                "Within the lost tier, the damaged pool's originating ritual is paused first");
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
        var level = h.getLevel(); var store = StructuralRitualStore.get(level);
        var plane = h.absolutePos(new BlockPos(8, 3, 8));
        RitualFrameworkGameTests.circle(h, plane, RitualShape.SMALL, RitualShape.MEDIUM, RitualShape.LARGE);
        var center = RitualFrameworkGameTests.center(h, plane.above()); var ref = center.reference();
        UUID player = UUID.randomUUID(); var terms = new CompoundTag();
        var small = new StructuralRitualStore.Definition(ResourceLocation.parse("kaleidoscope_witchcraft:small_fixture"), 1, 1, new int[]{1}, 1, Optional.empty());
        var medium = new StructuralRitualStore.Definition(ResourceLocation.parse("kaleidoscope_witchcraft:medium_fixture"), 1, 1, new int[]{1}, 2, Optional.empty());
        UUID first = store.begin(level, small, center, player, terms).orElseThrow();
        UUID second = store.begin(level, medium, center, player, terms).orElseThrow();
        UUID third = store.begin(level, small, center, player, terms).orElseThrow();
        UUID fourth = store.begin(level, small, center, player, terms).orElseThrow();
        UUID fifth = store.begin(level, small, center, player, terms).orElseThrow();
        h.assertTrue(store.begin(level, small, center, player, terms).isEmpty(), "Six points admit five receipts costing 1+2+1+1+1, without an old three-receipt limit");
        CompoundTag runtime = new CompoundTag(); runtime.putLong("FlowCredit", 13); store.runtime(level, second, runtime);
        var load = StructuralRitualStore.class.getDeclaredMethod("load", CompoundTag.class, HolderLookup.Provider.class); load.setAccessible(true);
        var restored = (StructuralRitualStore)load.invoke(null, store.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
        var ids = List.of(first, second, third, fourth, fifth);
        h.assertTrue(restored.allAt(ref).stream().map(StructuralRitualStore.Handle::id).toList().equals(ids)
                && restored.runtime(second).getLong("FlowCredit") == 13 && !restored.progress(second).orElseThrow().available(),
                "Order and fractional clock persist; availability must be revalidated");
        RitualCenters.end(level, first);
        h.assertTrue(store.allAt(ref).size() == 4 && store.progress(second).isPresent(), "Ending one receipt does not erase its siblings");
        for (var id : ids) RitualCenters.end(level, id);
        h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=40, batch="suction-contracts")
    public static void newResearchGatePausesLegacyPreparationButKeepsPaidRun(GameTestHelper h) throws Exception {
        var level=h.getLevel();var store=StructuralRitualStore.get(level);
        var plane=h.absolutePos(new BlockPos(8,3,8));
        RitualFrameworkGameTests.circle(h,plane,RitualShape.MEDIUM);
        var center=RitualFrameworkGameTests.center(h,plane.above());
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
        h.assertTrue(store.advance(level,active,legacy,StructuralRitualStore.Presence.PRESENT,RitualShape.Match.VALID,pay).mayRun()
                &&payments.get()==1,"The old unguarded receipt was already fully paid before the policy changed");
        h.runAfterDelay(1, () -> {
            var waiting=store.advance(level,preparing,current,StructuralRitualStore.Presence.PRESENT,RitualShape.Match.VALID,pay);
            var running=store.advance(level,active,current,StructuralRitualStore.Presence.PRESENT,RitualShape.Match.VALID,pay);
            h.assertTrue(waiting.phase()==StructuralRitualStore.Phase.PREPARING&&!waiting.mayRun()
                    &&store.progress(preparing).orElseThrow().committedSteps()==0&&store.progress(preparing).orElseThrow().elapsed()==0,
                    "An unfinished preparation adopts the current research without spending");
            h.assertTrue(running.mayRun()&&payments.get()==1&&store.progress(active).orElseThrow().committedSteps()==1,
                    "An already-paid receipt keeps working without replaying payment");
            RitualCenters.end(level, preparing);RitualCenters.end(level, active);h.succeed();
        });
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
