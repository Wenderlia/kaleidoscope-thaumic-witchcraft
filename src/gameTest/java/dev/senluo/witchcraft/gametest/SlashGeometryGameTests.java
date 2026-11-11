package dev.senluo.witchcraft.gametest;

import dev.senluo.witchcraft.accessory.ProjectileBarrier;
import dev.senluo.witchcraft.accessory.PropagationField;
import dev.senluo.witchcraft.motion.TravelStep;
import dev.senluo.witchcraft.slash.RadialSlash;
import dev.senluo.witchcraft.slash.drawing.RadialOutline;
import dev.senluo.witchcraft.slash.drawing.StrokeSphere;
import dev.senluo.witchcraft.slash.drawing.StrokeTrace;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class SlashGeometryGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=10)
    public static void drawingHoldTimeAccruesWithoutDuplicateLength(GameTestHelper h) {
        var sphere = StrokeSphere.facing(new Vec3(0, 0, 1));
        var trace = new StrokeTrace();
        Vec3 point = sphere.project(new Vec3(.1, 0, 1));
        trace.sample(point);
        double length = trace.uniqueLength(), first = trace.multiplier(1.5);
        for (int i = 0; i < 19; i++) trace.sample(point);
        h.assertTrue(Math.abs(trace.uniqueLength() - length) < 1e-9 && Math.abs(trace.multiplier(1.5) - first * 20) < 1e-9,
                "A stationary held stroke gains time, but no new length");
        for (int i = 0; i < 400; i++) trace.sample(point);
        h.assertTrue(trace.ticks() == 320 && trace.multiplier(1.5) <= 32, "Duration and power stay bounded");
        var blank = new StrokeTrace();
        for (int i = 0; i < 320; i++) blank.sample(StrokeSphere.START);
        h.assertTrue(blank.multiplier(1.5) == 0, "Holding without any drawn line cannot create a blade");
        h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=10)
    public static void emptyFieldYieldsCommittableTravelPrefix(GameTestHelper h) {
        // No world mutation or chunk load: isolate the query from fields used by other test fixtures.
        Vec3 from = new Vec3(23_000_000, 200, 23_000_000), to = from.add(0, 0, 2);
        Vec3 axis = new Vec3(1, 0, 0), direction = new Vec3(0, 0, 1);
        Arrow carrier = new Arrow(EntityType.ARROW, h.getLevel()); carrier.setPos(from);
        h.assertTrue(PropagationField.EMPTY.segmentEntry(from, to, axis, 0, .25, .06)
                == Double.POSITIVE_INFINITY, "Raw geometry distinguishes no intersection from a hit");
        var space = ProjectileBarrier.space(carrier);
        double fraction = space.segmentEntry(from, to, axis, 0, .25, .06);
        h.assertTrue(fraction == 1, "Flight adapter converts no intersection to a complete finite prefix");
        TravelStep quote = space.quotePoint(from, direction, 2, 2);
        var receipt = quote.prefix(fraction);
        h.assertTrue(receipt.complete() && receipt.distance() == 2 && receipt.work() == 2
                && receipt.position().distanceToSqr(to) < 1.0E-12,
                "An unobstructed radial step commits both distance and work without Invalid travel prefix");
        carrier.discard(); h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=10)
    public static void radialReleaseRadiusSurvivesCurrentSave(GameTestHelper h) {
        StrokeSphere sphere = StrokeSphere.facing(new Vec3(0, 0, 1));
        StrokeTrace trace = new StrokeTrace(); trace.sample(sphere.project(new Vec3(.3, 0, 1)));
        RadialOutline shape = RadialOutline.from(sphere, trace, 24);
        h.assertTrue(shape.initialRadius() == 3 && sphere.project(new Vec3(1, 0, 0)).length() == 3,
                "New drawing and release geometry share the three-block radius");
        var saved = new RadialSlash(shape).save();
        h.assertTrue(RadialSlash.load(saved, 24).outline().initialRadius() == 3,
                "Saving and reloading keeps the release radius rather than reading a mutable default");
        h.assertTrue(RadialSlash.load(saved, 24).save().equals(saved),
                "Current geometry and per-edge progress round-trip without changing release state");
        h.succeed();
    }
}
