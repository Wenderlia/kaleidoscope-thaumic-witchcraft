package dev.senluo.witchcraft.gametest;

import dev.senluo.witchcraft.ritual.*;
import dev.senluo.witchcraft.time.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class RitualFrameworkGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=10)
    public static void paymentRechecksAuthorityAfterQuotation(GameTestHelper h) {
        var level = h.getLevel(); var store = new StructuralRitualStore();
        var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
        var paid = new java.util.concurrent.atomic.AtomicBoolean();
        var provider = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft", "authority_" + java.util.UUID.randomUUID());
        RitualResearch.register(provider, (world, owner, key) -> allowed.get() ? RitualResearch.Result.ALLOWED : RitualResearch.Result.DENIED);
        var definition = new StructuralRitualStore.Definition(provider, 1, 1, new int[]{1},
                java.util.Optional.of(new RitualResearch.Requirement(provider, "fixture")));
        var pos = h.absolutePos(new BlockPos(2, 2, 2));
        var center = new RitualCenters.Center(new RitualCenters.Reference(level.dimension().location(), pos, java.util.UUID.randomUUID()), pos, true);
        var id = store.begin(level, definition, center, java.util.UUID.randomUUID(), new net.minecraft.nbt.CompoundTag()).orElseThrow();
        store.advance(level, id, definition, StructuralRitualStore.Presence.PRESENT, center.reference(), RitualShape.Match.VALID,
                (token, terms) -> {
                    allowed.set(false);
                    return java.util.Optional.of(new StructuralRitualStore.PreparedStep(terms, () -> { paid.set(true); return true; }));
                });
        h.assertTrue(!paid.get() && store.progress(id).orElseThrow().committedSteps() == 0,
                "A previously allowed check cannot pay after the quotation callback revokes research");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=10)
    public static void planeIndexTracksReceiptsWithoutChangingPriority(GameTestHelper h) throws Exception {
        var level = h.getLevel(); var dimension = level.dimension().location();
        var store = new StructuralRitualStore(); var owner = java.util.UUID.randomUUID();
        var a = h.absolutePos(new BlockPos(2, 3, 2)); var b = a.offset(8, 0, 0);
        var firstCenter = new RitualCenters.Center(new RitualCenters.Reference(dimension, a.above(), java.util.UUID.randomUUID()), a.above(), true);
        var secondCenter = new RitualCenters.Center(new RitualCenters.Reference(dimension, a.above(2), java.util.UUID.randomUUID()), a.above(2), true);
        var definition = new StructuralRitualStore.Definition(net.minecraft.resources.ResourceLocation.parse("kaleidoscope_witchcraft:index_fixture"),
                1, 1, new int[]{1}, java.util.Optional.empty());
        var terms = new net.minecraft.nbt.CompoundTag(); terms.putLong("Plane", a.asLong());
        terms.putInt(GoldenRitualSlots.PINNED_RING, 2);
        var first = store.begin(level, definition, firstCenter, owner, new net.minecraft.nbt.CompoundTag()).orElseThrow();
        var second = store.begin(level, definition, secondCenter, owner, terms).orElseThrow();
        var resolved = new java.util.concurrent.atomic.AtomicInteger();
        h.assertTrue(store.atPlane(dimension, a, handle -> { resolved.incrementAndGet(); return true; }).size() == 2
                && resolved.get() == 1, "Only an unbound receipt needs geometric resolution");
        h.assertTrue(store.bindSite(level, first, a, false), "Binding an unpaid or paid receipt indexes its plane");
        java.util.function.Predicate<StructuralRitualStore.Handle> noUnbound = handle -> { throw new AssertionError("Unexpected unbound scan"); };
        h.assertTrue(store.atPlane(dimension, a, noUnbound).stream().map(StructuralRitualStore.Handle::id).toList()
                .equals(java.util.List.of(first, second)), "Late binding preserves original start priority");
        var other = terms.copy(); other.putLong("Plane", b.asLong());
        h.assertTrue(store.select(level, first, definition, other, null)
                && store.atPlane(dimension, a, noUnbound).size() == 1, "Changing preparation type updates the old plane");
        h.assertTrue(store.prepareTerms(level, first, terms)
                && store.atPlane(dimension, a, noUnbound).getFirst().id().equals(first)
                && store.bearingRing(first) == 2, "Editing terms restores order and the scalar pinned-ring query");
        store.advance(level, first, definition, StructuralRitualStore.Presence.PRESENT, firstCenter.reference(), RitualShape.Match.VALID,
                (token, current) -> java.util.Optional.of(new StructuralRitualStore.PreparedStep(other, () -> false)));
        h.assertTrue(store.atPlane(dimension, a, noUnbound).size() == 2, "Failed payment cannot move the indexed receipt");
        // A separate receipt can commit in this tick without bypassing per-session advancement rules.
        store.advance(level, second, definition, StructuralRitualStore.Presence.PRESENT, secondCenter.reference(), RitualShape.Match.VALID,
                (token, current) -> java.util.Optional.of(new StructuralRitualStore.PreparedStep(other, () -> true)));
        h.assertTrue(store.atPlane(dimension, b, noUnbound).getFirst().id().equals(second), "Successful commit moves the plane exactly once");
        var load = StructuralRitualStore.class.getDeclaredMethod("load", net.minecraft.nbt.CompoundTag.class, net.minecraft.core.HolderLookup.Provider.class);
        load.setAccessible(true);
        var restored = (StructuralRitualStore) load.invoke(null, store.save(new net.minecraft.nbt.CompoundTag(), level.registryAccess()), level.registryAccess());
        h.assertTrue(restored.atPlane(dimension, a, noUnbound).getFirst().id().equals(first)
                && restored.atPlane(dimension, b, noUnbound).getFirst().id().equals(second), "Reload rebuilds indexes from the same paid receipts");
        restored.end(level, second);
        h.assertTrue(restored.atPlane(dimension, b, noUnbound).isEmpty() && restored.finish(second)
                && restored.atPlane(dimension, b, noUnbound).isEmpty(), "Ended and removed receipts do not occupy rings");
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
                "Equal distances choose the lower plane; the upper large ring grants neither capacity nor another slot");
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
