package dev.senluo.witchcraft.gametest;

import dev.senluo.witchcraft.ritual.*;
import dev.senluo.witchcraft.time.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class RitualFrameworkGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=20)
    public static void geometryRefreshOnlyRevokesLostPermission(GameTestHelper h) {
        var level = h.getLevel(); var store = StructuralRitualStore.get(level);
        var plane = h.absolutePos(new BlockPos(8, 3, 8));
        circle(h, plane, RitualShape.SMALL, RitualShape.MEDIUM);
        var center = center(h, plane.above());
        var kind = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft", "refresh_" + java.util.UUID.randomUUID());
        var revoked = new java.util.concurrent.atomic.AtomicInteger();
        RitualCenters.observeAvailability(kind, (world, handle) -> revoked.incrementAndGet());
        var definition = new StructuralRitualStore.Definition(kind, 1, 1, new int[]{1}, 1, java.util.Optional.empty());
        var id = store.begin(level, definition, center, java.util.UUID.randomUUID(), new net.minecraft.nbt.CompoundTag()).orElseThrow();
        store.advance(level, id, definition, StructuralRitualStore.Presence.PRESENT, RitualShape.Match.VALID,
                (token, terms) -> java.util.Optional.of(new StructuralRitualStore.PreparedStep(terms, () -> true)));
        h.assertTrue(store.running(level, id), "The paid receipt initially runs");
        level.setBlock(plane.offset(1, 0, 1), Blocks.STONE.defaultBlockState(), 3);
        h.assertTrue(store.running(level, id) && revoked.get() == 0, "Unrelated changes preserve the active receipt");
        level.removeBlock(plane.offset(RitualShape.SMALL.marks().getFirst()), false);
        h.assertTrue(store.running(level, id) && revoked.get() == 0, "Remaining capacity keeps the same work running after a real ring loss");
        level.removeBlock(plane.offset(RitualShape.MEDIUM.marks().getFirst()), false);
        h.assertTrue(!store.running(level, id) && revoked.get() == 1, "The actual loss of all supply revokes once before work can commit");
        h.assertTrue(store.progress(id).orElseThrow().committedSteps() == 1, "Pausing preserves paid progress");
        circle(h, plane, RitualShape.SMALL);
        h.assertTrue(store.eligible(level, id), "Repair wakes the cached negative result");
        RitualCenters.end(level, id);
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=10)
    public static void paymentRechecksAuthorityAfterQuotation(GameTestHelper h) {
        var level = h.getLevel(); var store = StructuralRitualStore.get(level);
        var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
        var paid = new java.util.concurrent.atomic.AtomicBoolean();
        var provider = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft", "authority_" + java.util.UUID.randomUUID());
        RitualResearch.register(provider, (world, owner, key) -> allowed.get() ? RitualResearch.Result.ALLOWED : RitualResearch.Result.DENIED);
        var definition = new StructuralRitualStore.Definition(provider, 1, 1, new int[]{1},
                java.util.Optional.of(new RitualResearch.Requirement(provider, "fixture")));
        var pos = h.absolutePos(new BlockPos(8, 3, 8));
        circle(h, pos, RitualShape.SMALL);
        var center = center(h, pos.above());
        var id = store.begin(level, definition, center, java.util.UUID.randomUUID(), new net.minecraft.nbt.CompoundTag()).orElseThrow();
        store.advance(level, id, definition, StructuralRitualStore.Presence.PRESENT, RitualShape.Match.VALID,
                (token, terms) -> {
                    allowed.set(false);
                    return java.util.Optional.of(new StructuralRitualStore.PreparedStep(terms, () -> { paid.set(true); return true; }));
                });
        h.assertTrue(!paid.get() && store.progress(id).orElseThrow().committedSteps() == 0,
                "A previously allowed check cannot pay after the quotation callback revokes research");
        RitualCenters.end(level, id);
        h.succeed();
    }

    static void circle(GameTestHelper h, BlockPos plane, RitualShape... shapes) {
        for (var shape : shapes) for (var offset : shape.marks()) {
            var at = plane.offset(offset);
            h.getLevel().setBlock(at, Blocks.STONE.defaultBlockState(), 3);
            h.getLevel().setBlock(at.above(), RitualContent.CHALK_SURFACE.get().defaultBlockState(), 3);
        }
    }
    static RitualCenters.Center center(GameTestHelper h, BlockPos pos) {
        h.getLevel().setBlock(pos, Blocks.BARREL.defaultBlockState(), 3);
        return RitualCenters.observe(h.getLevel().getBlockEntity(pos)).orElseThrow();
    }
    static RitualCenters.Center savingCenter(GameTestHelper h, BlockPos axis) {
        h.getLevel().setBlock(axis, Blocks.STONE.defaultBlockState(), 3);
        h.getLevel().setBlock(axis.above(), RitualContent.GOLDEN_HEART.get().defaultBlockState(), 3);
        return RitualCenters.observe(h.getLevel().getBlockEntity(axis.above())).orElseThrow();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=20)
    public static void savingMembersDoNotRelayPointSupply(GameTestHelper h) {
        var level = h.getLevel(); var store = StructuralRitualStore.get(level);
        var plane = h.absolutePos(new BlockPos(8, 3, 8));
        var boundary = plane.above(7); var remote = plane.above(14);
        circle(h, plane, RitualShape.SMALL);
        circle(h, boundary, RitualShape.SMALL);
        circle(h, remote, RitualShape.SMALL);
        var work = center(h, plane);
        savingCenter(h, boundary);
        var kind = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft", "direct_supply_" + java.util.UUID.randomUUID());
        RitualCenters.registerHost(kind, entity -> entity instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity);
        var definition = new StructuralRitualStore.Definition(kind, 1, 1, new int[]{1}, 2, java.util.Optional.empty());
        var id = store.begin(level, definition, work, java.util.UUID.randomUUID(), new net.minecraft.nbt.CompoundTag()).orElseThrow();
        var payments = new java.util.concurrent.atomic.AtomicInteger();
        store.advance(level, id, definition, StructuralRitualStore.Presence.PRESENT, RitualShape.Match.VALID,
                (token, terms) -> java.util.Optional.of(new StructuralRitualStore.PreparedStep(terms, () -> { payments.incrementAndGet(); return true; })));
        h.assertTrue(store.running(level, id), "Two directly covering one-point rings combine, including the seventh layer");
        level.removeBlock(boundary.offset(RitualShape.SMALL.marks().getFirst()), false);
        h.assertTrue(RitualPlane.at(level, remote).isPresent(), "The remote ring remains complete beside the saving station");
        h.assertTrue(!store.running(level, id), "A saving station cannot lend its remote ring to the work center fourteen layers away");
        h.assertTrue(payments.get() == 1 && store.progress(id).orElseThrow().committedSteps() == 1,
                "Losing direct capacity preserves the paid milestone");
        circle(h, boundary, RitualShape.SMALL);
        h.assertTrue(store.eligible(level, id), "Repairing the direct boundary ring wakes the cached missing supply");
        RitualCenters.end(level, id);
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=20)
    public static void savingHandoffKeepsOriginalExecutionAndSupply(GameTestHelper h) {
        var level = h.getLevel(); var store = StructuralRitualStore.get(level);
        var plane = h.absolutePos(new BlockPos(8, 3, 8));
        var remote = plane.above(14);
        circle(h, plane, RitualShape.SMALL);
        circle(h, remote, RitualShape.SMALL);
        var primary = savingCenter(h, plane);
        savingCenter(h, plane.above(7));
        var kind = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft", "saved_execution_" + java.util.UUID.randomUUID());
        var definition = new StructuralRitualStore.Definition(kind, 1, 1, new int[]{1}, 1, java.util.Optional.empty());
        var id = store.begin(level, definition, primary, java.util.UUID.randomUUID(), new net.minecraft.nbt.CompoundTag()).orElseThrow();
        store.advance(level, id, definition, StructuralRitualStore.Presence.PRESENT, RitualShape.Match.VALID,
                (token, terms) -> java.util.Optional.of(new StructuralRitualStore.PreparedStep(terms, () -> true)));
        h.assertTrue(store.running(level, id), "A generic rite can start with a saving station as its original center");
        level.removeBlock(primary.reference().position(), false);
        h.assertTrue(store.running(level, id), "Another saving station preserves the generic rite without its original physical member");
        var continued = store.site(level, id);
        h.assertTrue(continued.position().equals(primary.reference().position()) && continued.axis().equals(plane)
                        && continued.footprint().anchor().equals(plane),
                "Saving handoff preserves the execution position, axis and effect plane");
        level.removeBlock(plane.offset(RitualShape.SMALL.marks().getFirst()), false);
        h.assertTrue(RitualPlane.at(level, remote).isPresent() && !store.running(level, id),
                "The surviving station does not move the receiver to its own remote supply");
        circle(h, plane, RitualShape.SMALL);
        h.assertTrue(store.eligible(level, id) && store.progress(id).orElseThrow().committedSteps() == 1,
                "Restoring original direct supply resumes eligibility without losing payment");
        RitualCenters.end(level, id);
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40)
    public static void sharedPointsAndSavingMembersSurviveReload(GameTestHelper h) throws Exception {
        var level = h.getLevel(); var store = StructuralRitualStore.get(level);
        var plane = h.absolutePos(new BlockPos(8, 3, 8));
        circle(h, plane, RitualShape.SMALL, RitualShape.MEDIUM);
        var work = center(h, plane.above());
        for (int y : new int[]{3, 5}) {
            level.setBlock(plane.above(y-1), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(plane.above(y), RitualContent.GOLDEN_HEART.get().defaultBlockState(), 3);
        }
        var kind = net.minecraft.resources.ResourceLocation.parse("kaleidoscope_witchcraft:member_fixture");
        RitualCenters.registerHost(kind, entity -> entity instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity);
        var definition = new StructuralRitualStore.Definition(kind, 1, 1, new int[]{1}, 3, java.util.Optional.empty());
        var id = store.begin(level, definition, work, java.util.UUID.randomUUID(), new net.minecraft.nbt.CompoundTag()).orElseThrow();
        h.assertTrue(store.members(id).size() == 3 && store.eligible(level, id), "Small plus medium funds one three-point receipt with three members");
        h.assertTrue(store.begin(level, definition, work, java.util.UUID.randomUUID(), new net.minecraft.nbt.CompoundTag()).isEmpty(), "Members cannot duplicate shared capacity");
        store.advance(level, id, definition, StructuralRitualStore.Presence.PRESENT, RitualShape.Match.VALID,
                (token, terms) -> java.util.Optional.of(new StructuralRitualStore.PreparedStep(terms, () -> true)));
        var load = StructuralRitualStore.class.getDeclaredMethod("load", net.minecraft.nbt.CompoundTag.class, net.minecraft.core.HolderLookup.Provider.class);
        load.setAccessible(true);
        var restored = (StructuralRitualStore)load.invoke(null, store.save(new net.minecraft.nbt.CompoundTag(), level.registryAccess()), level.registryAccess());
        h.assertTrue(restored.members(id).equals(store.members(id)) && restored.progress(id).orElseThrow().committedSteps() == 1
                && !restored.progress(id).orElseThrow().available(), "Reload keeps all members and payment, and requires fresh authorization");
        level.removeBlock(plane.above(3), false);
        h.assertTrue(store.eligible(level, id), "Removing one saving station leaves the required work function available");
        level.removeBlock(work.reference().position(), false);
        h.assertTrue(!store.eligible(level, id) && !RitualCenters.state(level, id).gone(), "A saving station preserves progress while the work function is absent");
        center(h, work.reference().position());
        h.assertTrue(store.eligible(level, id), "A surviving saving station can restore the work function at its registered address");
        RitualCenters.end(level, id);
        h.assertTrue(store.progress(id).isEmpty(), "Successful cleanup removes the receipt immediately");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40)
    public static void coveredChalkKeepsCurrentEngravingAndCircle(GameTestHelper h) {
        var level=h.getLevel();var plane=h.absolutePos(new BlockPos(8,3,8));
        var wood=TemporalContent.TIME_LOG.get().defaultBlockState();
        for(var offset:RitualShape.SMALL.marks()) {
            var support=plane.offset(offset);
            level.setBlock(support,wood,3);
            level.setBlock(support.above(),RitualContent.CHALK_SURFACE.get().defaultBlockState()
                    .setValue(ChalkSurfaceBlock.DESIGN,7).setValue(ChalkSurfaceBlock.ROTATION,2),3);
            h.assertTrue(RitualChalk.coverChalk(level,support.above(),wood),"Growing wood covers a real chalk layer");
        }
        var found=RitualPlane.find(level,plane).orElseThrow();
        h.assertTrue(found.rings().complete(RitualRings.Ring.SMALL),"All sixteen covered engravings remain a valid circle");
        var support=plane.offset(RitualShape.SMALL.marks().getFirst());
        var records=RitualChalk.chunk(level,support).getData(RitualContent.CHALK_MARKS);
        var mark=records.get(support);
        h.assertTrue(mark.medium()==1&&mark.design()==ChalkPattern.CARVING_DESIGNS+7&&mark.rotation()==2,
                "Engraving keeps the chalk's exact symbol and orientation in the carving atlas");
        var loaded=new ChalkMarks();loaded.deserializeNBT(level.registryAccess(),records.serializeNBT(level.registryAccess()));
        h.assertTrue(loaded.supported()&&mark.equals(loaded.get(support)),"Current chunk marks round trip without positional synthesis");
        level.removeBlock(support,false);
        h.assertTrue(records.get(support)==null,"Removing the support removes the world engraving");
        level.setBlock(support,wood,3);RitualChalk.restoreCarving(level,support,mark);
        h.assertTrue(mark.equals(records.get(support)),"Paid tree regeneration can restore the remembered engraving under its cover");
        h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40)
    public static void ritualHeightBandIsInclusiveAndNeverSplicesFloors(GameTestHelper h) {
        var level = h.getLevel(); BlockPos axis = h.absolutePos(new BlockPos(8, 10, 8));
        for (int offset : new int[]{-8, -7, -4, 0, 4, 7, 8}) {
            BlockPos plane = axis.offset(0, offset, 0);
            for (var mark : RitualShape.SMALL.marks()) {
                var at = plane.offset(mark); level.setBlock(at, Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(at.above(), RitualContent.CHALK_SURFACE.get().defaultBlockState(), 3);
            }
            var found = RitualPlane.find(level, axis);
            h.assertTrue(RitualPlane.match(level, axis, RitualShape.SMALL).isPresent() == (Math.abs(offset) <= 7),
                    "Custom-template matching uses the same center height policy");
            h.assertTrue(found.isPresent() == (Math.abs(offset) <= 7), "Exactly seven levels each way are accepted; eight are rejected");
            found.ifPresent(value -> h.assertTrue(value.anchor().equals(plane), "Height is the marked supporting block, not the chalk layer"));
            for (var mark : RitualShape.SMALL.marks()) {
                var at = plane.offset(mark); level.removeBlock(at.above(), false); level.removeBlock(at, false);
            }
        }
        for (var shape : new RitualShape[]{RitualShape.SMALL, RitualShape.LARGE}) {
            BlockPos plane = shape == RitualShape.SMALL ? axis.below() : axis.above();
            for (var mark : shape.marks()) {
                var at = plane.offset(mark); level.setBlock(at, Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(at.above(), RitualContent.CHALK_SURFACE.get().defaultBlockState(), 3);
            }
        }
        var selected = RitualPlane.find(level, axis).orElseThrow();
        var offerings = RitualOfferingArea.outermost(selected).bounds();
        h.assertTrue(offerings.minY == selected.anchor().getY() - 7
                && offerings.maxY == selected.anchor().getY() + 8,
                "Centers and material collection share the inclusive seven-block band");
        var dropped = new java.util.ArrayList<net.minecraft.world.entity.item.ItemEntity>();
        for (int height : new int[]{-8, -7, 7, 8}) {
            var item = new net.minecraft.world.entity.item.ItemEntity(level,
                    selected.anchor().getX() + 1.5, selected.anchor().getY() + height + .5,
                    selected.anchor().getZ() + .5, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COAL));
            item.setNoGravity(true); item.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            level.addFreshEntity(item); dropped.add(item);
        }
        var sources = RitualMaterials.find(level, RitualOfferingArea.outermost(selected),
                stack -> stack.is(net.minecraft.world.item.Items.COAL));
        h.assertTrue(sources.size() == 2 && sources.stream().allMatch(source ->
                Math.abs(net.minecraft.util.Mth.floor(source.position().y) - selected.anchor().getY()) == 7),
                "Real dropped offerings at both seventh layers are found, both eighth layers are excluded");
        for (var item : dropped) item.discard();
        h.assertTrue(selected.anchor().equals(axis.below()) && selected.rings().count() == 1
                && selected.rings().complete(RitualRings.Ring.SMALL),
                "Spatial selection prefers the lower plane; the separate point allocator may also use the upper pool");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40)
    public static void ritualCoreRemainsUsableWithoutOptionalCrucible(GameTestHelper h) {
        var level=h.getLevel();BlockPos pos=h.absolutePos(new BlockPos(8,3,8));
        level.setBlock(pos.below(),Blocks.FARMLAND.defaultBlockState(),3);
        level.setBlock(pos,TemporalContent.CORE.get().defaultBlockState(),3);
        var tree=(LivingEntity)level.getBlockEntity(pos);tree.tissue.tree=true;
        var center=RitualCenters.observe(tree).orElseThrow();BlockPos support=center.axis().below();
        for(var offset:RitualShape.SMALL.marks()) {
            BlockPos at=support.offset(offset);level.setBlock(at,Blocks.STONE.defaultBlockState(),3);
            level.setBlock(at.above(),RitualContent.CHALK_SURFACE.get().defaultBlockState(),3);
        }
        h.assertTrue(center.match(level,RitualShape.SMALL).isPresent(),"Tree center still recognizes the shared 16-mark circle without TC4R");
        BlockPos missing=support.offset(RitualShape.SMALL.marks().getFirst()).above();level.removeBlock(missing,false);
        h.assertTrue(center.match(level,RitualShape.SMALL).isEmpty(),"Incomplete original ownership circle is still rejected");
        var cap=RitualCapacity.quote(Long.MAX_VALUE-5,Long.MAX_VALUE,3,9);
        h.assertTrue(cap.accepted()==1&&cap.rejected()==8,"Admission remains whole-unit and overflow safe");
        h.succeed();
    }
}
