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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class SlashGeometryGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=10)
    public static void travelWorkInverseKeepsEndpointsAndFieldBudget(GameTestHelper h) {
        Vec3 direction = new Vec3(1, 0, 0);
        var free = new TravelStep(Vec3.ZERO, direction, 4, 4, 0, 0);
        h.assertTrue(free.fractionForWork(1, 1) == .25 && free.fractionForWork(8, .75) == .75
                        && free.limitWork(4) == free, "Linear travel and a complete quote retain exact endpoints");
        var fixed = TravelStep.quote(Vec3.ZERO, direction, 4, 4, 1e-6, 4);
        h.assertTrue(fixed.distance() == 0 && fixed.limitWork(1).work() == 1,
                "The precision floor still consumes the payable work while position remains fixed");
        var field = TravelStep.quote(Vec3.ZERO, direction, 2, 4, 2, 4);
        h.assertTrue(Math.abs(field.fractionForWork(1, 1) - .5) < 1e-12,
                "One work unit traverses half of the two-times-scaled field segment");
        for (double gap : new double[]{Double.POSITIVE_INFINITY, 0, 1e-6, Math.nextUp(1e-6), .1, 2, 4, 8})
            for (double work : new double[]{0, 1e-11, .001, .1, 1, 10}) {
                var quote = TravelStep.quote(Vec3.ZERO, direction, work, 8, gap, 4);
                for (double upper : new double[]{0, .25, .9, 1}) for (double ratio : new double[]{0, .1, .5, 1}) {
                    double payable = quote.spentAt(upper) * ratio;
                    double fraction = quote.fractionForWork(payable, upper);
                    h.assertTrue(Double.isFinite(fraction) && fraction >= 0 && fraction <= upper
                                    && quote.spentAt(fraction) <= payable,
                            "Work inversion stays within both geometric and payment limits");
                }
            }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=10)
    public static void radialSlicesPreserveChordAndTinyRemainders(GameTestHelper h) {
        var edge = outline(.1).edges().getFirst();
        var child = edge.slice(.2, .8);
        h.assertTrue(child.first().distanceToSqr(edge.first().lerp(edge.last(), .2)) < 1e-24
                        && child.last().distanceToSqr(edge.first().lerp(edge.last(), .8)) < 1e-24,
                "Trimming preserves the original chord instead of projecting endpoints back onto the sphere");
        var tiny = edge.slice(0, 1e-6);
        h.assertTrue(tiny.half() > 0 && tiny.half() < 1e-6 && tiny.axis().equals(edge.axis()),
                "A valid tiny remainder inherits its direction without a new minimum-width gate");
        var nested = child.slice(.25, .75);
        var direct = edge.slice(.35, .65);
        h.assertTrue(nested.first().distanceToSqr(direct.first()) < 1e-24
                        && nested.last().distanceToSqr(direct.last()) < 1e-24
                        && Math.abs(nested.half() - direct.half()) < 1e-15,
                "Repeated clipping stays on the same chord and composes by parameter intervals");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=10)
    public static void shortOriginalRadialEdgeKeepsUnitAxis(GameTestHelper h) {
        var edge = outline(1e-5).edges().getFirst();
        h.assertTrue(Math.abs(edge.axis().lengthSqr() - 1) < 1e-12,
                "An accepted original edge cannot inherit Vec3.normalize's small-vector zero result");
        h.assertTrue(edge.middle().add(edge.axis().scale(edge.half())).distanceToSqr(edge.last()) < 1e-24,
                "The collision centre, direction and extent reconstruct the baked endpoint");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=10)
    public static void tinyRadialIntervalsSurviveSaveAndSync(GameTestHelper h) {
        var tag = new RadialSlash(outline(.1)).geometry().copy();
        tag.putInt("Topology", 1);
        var intervals = new ListTag();
        for (double from : new double[]{0, 1 - 1e-6}) {
            var interval = new CompoundTag();
            interval.putInt("Part", 0); interval.putDouble("From", from);
            interval.putDouble("To", from == 0 ? 1e-6 : 1);
            intervals.add(interval);
        }
        tag.put("Intervals", intervals);
        var server = RadialSlash.fromGeometry(tag, null);
        var saved = server.save();
        h.assertTrue(RadialSlash.load(saved, 24).save().equals(saved),
                "Tiny surviving intervals retain their exact saved topology and movement state");
        var client = RadialSlash.fromGeometry(server.geometry(), null);
        client.acceptSnapshot(server.snapshot(), 24);
        h.assertTrue(client.size() == 2 && client.edge(0).equals(server.edge(0))
                        && client.edge(1).equals(server.edge(1))
                        && client.edge(0).last().distanceToSqr(client.edge(1).first()) > .001,
                "The client uses the same two clipped chords and does not reconnect the consumed gap");
        h.succeed();
    }

    private static RadialOutline outline(double angle) {
        var tag = new CompoundTag();
        tag.putInt("GeometryVersion", 1); tag.putDouble("InitialRadius", 3);
        var points = new ListTag();
        for (Vec3 value : new Vec3[]{new Vec3(0, 0, 1), new Vec3(Math.sin(angle), 0, Math.cos(angle))}) {
            var point = new CompoundTag();
            point.putDouble("X", value.x); point.putDouble("Y", value.y); point.putDouble("Z", value.z);
            points.add(point);
        }
        tag.put("Directions", points);
        return RadialOutline.load(tag);
    }

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
