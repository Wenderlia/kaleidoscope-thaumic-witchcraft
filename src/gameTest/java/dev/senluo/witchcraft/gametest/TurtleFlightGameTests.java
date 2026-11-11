package dev.senluo.witchcraft.gametest;

import com.mojang.authlib.GameProfile;
import dev.senluo.witchcraft.accessory.AccessoryContent;
import dev.senluo.witchcraft.accessory.FlightPath;
import dev.senluo.witchcraft.accessory.MotionConstraints;
import dev.senluo.witchcraft.accessory.ProjectileBarrier;
import dev.senluo.witchcraft.accessory.SonicBarrier;
import dev.senluo.witchcraft.accessory.TurtleField;
import java.util.UUID;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.behavior.warden.SonicBoom;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import top.theillusivec4.curios.api.CuriosApi;

@PrefixGameTestTemplate(false)
public final class TurtleFlightGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=80)
    public static void arrowsConvergeWithoutReversingAndRelease(GameTestHelper h) {
        Vec3 farStart = new Vec3(-5_807_899.5, -54.45, -160_789.5);
        Vec3 farCenter = new Vec3(-5_807_905.5, -54.60000002384186, -160_789.5);
        Vec3 incoming = new Vec3(-3, 0, 0);
        Vec3 farLimit = farStart.add(incoming.scale(MotionConstraints.entry(farStart, incoming, farCenter, 4)));
        Vec3 next = MotionConstraints.approachByTravel(farStart, farLimit, 3, 4);
        h.assertTrue(Double.isFinite(MotionConstraints.entry(next, farLimit.subtract(next), farCenter, 4)),
                "Rounding at large world coordinates cannot release a trajectory at its sphere endpoint");
        ServerLevel level = h.getLevel();
        FakePlayer wearer = wearer(h);
        var inventory = CuriosApi.getCuriosInventory(wearer).orElseThrow();
        Arrow arrow = new Arrow(EntityType.ARROW, level);
        arrow.moveTo(wearer.position().add(6, .8, 0));
        arrow.shoot(-1, 0, 0, 3, 0);
        float yaw = arrow.getYRot(), pitch = arrow.getXRot();
        level.addFreshEntity(arrow);
        Arrow own = new Arrow(EntityType.ARROW, level);
        own.moveTo(wearer.position().add(6, 1.1, 0)); own.setOwner(wearer);
        own.shoot(-1, 0, 0, 3, 0); level.addFreshEntity(own);
        Runnable cleanup = () -> { arrow.discard(); own.discard(); wearer.discard(); TurtleField.invalidate(level); };
        double[] previous = { 100 };
        for (int tick = 2; tick <= 40; tick++) {
            checked(h, tick, () -> {
                double distance = arrow.getBoundingBox().getCenter().distanceTo(wearer.getBoundingBox().getCenter());
                h.assertTrue(ProjectileBarrier.path(arrow).active(), "A real arrow retains one constrained trajectory: ticks="
                        + arrow.tickCount + ", position=" + arrow.position() + ", velocity=" + arrow.getDeltaMovement()
                        + ", wearer=" + wearer.position() + ", removed=" + arrow.isRemoved());
                h.assertTrue(distance >= 4 - 1.0E-5 && distance <= previous[0] + 1.0E-5,
                        "Travel converges monotonically without crossing or jumping away from the boundary");
                h.assertTrue(arrow.getYRot() == yaw && arrow.getXRot() == pitch,
                        "Native rotation from a vanishing velocity cannot turn the arrow outward");
                h.assertTrue(!ProjectileBarrier.path(own).active(), "The wearer's projectile remains exempt");
                previous[0] = distance;
            }, cleanup);
        }
        checked(h, 42, () -> {
            inventory.setEquippedCurio("rune", 0, ItemStack.EMPTY);
            TurtleField.invalidate(level);
        }, cleanup);
        checked(h, 45, () -> {
            h.assertTrue(!ProjectileBarrier.path(arrow).active() && arrow.getDeltaMovement().y < 0,
                    "Unequipping releases native gravity without returning the incoming impulse");
            cleanup.run(); h.succeed();
        }, cleanup);
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=30)
    public static void sonicRayBlocksOnlyTargetsBehindItsIntersection(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer wearer = wearer(h);
        Warden warden = new Warden(EntityType.WARDEN, level);
        warden.moveTo(wearer.position().add(-8, 0, 0)); warden.setNoAi(true); level.addFreshEntity(warden);
        Cow target = new Cow(EntityType.COW, level);
        target.moveTo(wearer.position().add(5, 0, 0)); target.setNoAi(true); level.addFreshEntity(target);
        var boom = new TestSonic();
        try {
            float health = target.getHealth();
            Vec3 velocity = target.getDeltaMovement();
            fire(boom, level, warden, target);
            h.assertTrue(target.getHealth() == health && target.getDeltaMovement().equals(velocity),
                    "An intervening field blocks the actual native damage and conditional knockback");
            h.assertTrue(warden.getBrain().hasMemoryValue(MemoryModuleType.SONIC_BOOM_SOUND_COOLDOWN),
                    "The Warden still spends the native strike/cooldown opportunity");
            target.moveTo(wearer.position().add(-6, 0, 0));
            SonicBarrier.Trace front = SonicBarrier.trace(warden, level, target);
            h.assertTrue(front != null && !front.blocksDamage(), "A field behind the target cannot erase an earlier hit");
            fire(boom, level, warden, target);
            h.assertTrue(target.getHealth() < health, "An unobstructed native sonic hit is preserved");
            warden.moveTo(wearer.position().add(1, 0, 0));
            h.assertTrue(SonicBarrier.trace(warden, level, target).blocksDamage(), "A wave emitted inside the field is blocked");
            CuriosApi.getCuriosInventory(wearer).orElseThrow().setEquippedCurio("rune", 0, ItemStack.EMPTY);
            TurtleField.invalidate(level);
            h.assertTrue(SonicBarrier.trace(warden, level, target) == null, "Removing the field restores future native rays");
            Vec3 start = new Vec3(6, 0, 0), limit = new Vec3(4, 0, 0);
            h.assertTrue(Math.abs(MotionConstraints.approachByTravel(start, limit, 2, 4).x - 5) < 1.0E-10,
                    "Equal virtual distance halves the remaining gap");
            h.assertTrue(MotionConstraints.approachByTravel(start, limit, 1_000_000, 4).x > 4,
                    "Finite-precision convergence never crosses the limit even with a huge travel budget");
        } finally {
            target.discard(); warden.discard(); wearer.discard(); TurtleField.invalidate(level);
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=30)
    public static void virtualTravelPreservesSpeedBudgetAndOverlapRules(GameTestHelper h) {
        double gap = 1.75, first = .3, second = .5, width = 2;
        double once = MotionConstraints.mapTravel(gap, first + second, width);
        double split = MotionConstraints.mapTravel(gap, first, width);
        split += MotionConstraints.mapTravel(gap - split, second, width);
        h.assertTrue(Math.abs(once - split) < 1.0E-12,
                "Splitting the same radial travel does not create extra slowing or extra movement");
        h.assertTrue(MotionConstraints.mapTravel(10, 3, 2) == 3,
                "Travel outside the mapping band remains native");
        h.assertTrue(MotionConstraints.mapTravel(gap, 0, width) == 0,
                "No virtual travel means no spontaneous approach");
        Vec3 start = new Vec3(6, 0, 0), limit = new Vec3(4, 0, 0);
        FlightPath slow = new FlightPath(true, start, limit, new Vec3(-.5, 0, 0), 10, -90, 0, 1);
        FlightPath fast = new FlightPath(true, start, limit, new Vec3(-2, 0, 0), 10, -90, 0, 1);
        FlightPath half = new FlightPath(true, start, limit, fast.input(), 10, -90, 0, .5);
        h.assertTrue(fast.at(11).x < slow.at(11).x && fast.at(11).x > limit.x,
                "The faster incoming request advances farther while respecting the same boundary");
        h.assertTrue(half.at(14).distanceToSqr(fast.at(12)) < 1.0E-20,
                "A half-rate tree clock spends half the virtual travel without changing the mapping");
        var field = new MotionConstraints.Boundary(Vec3.ZERO, 2);
        Vec3 body = new Vec3(3, 0, 0), request = new Vec3(-.6, 0, .25);
        Vec3 single = MotionConstraints.apply(body, request, List.of(field), true);
        Vec3 overlap = MotionConstraints.apply(body, request, List.of(field, field), true);
        h.assertTrue(single.distanceToSqr(overlap) < 1.0E-20,
                "Identical overlapping fields do not charge the same path twice");
        h.assertTrue(single.z == request.z && single.lengthSqr() <= request.lengthSqr(),
                "The tangent is preserved and no extra movement is created");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=30, batch="flight-disposal")
    public static void momentumAttacksDissipateAtEveryCapturePath(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer wearer = wearer(h);
        try {
            for (EntityType<? extends Projectile> type : List.of(EntityType.SMALL_FIREBALL, EntityType.FIREBALL,
                    EntityType.DRAGON_FIREBALL, EntityType.WITHER_SKULL, EntityType.WIND_CHARGE, EntityType.BREEZE_WIND_CHARGE)) {
                Projectile incoming = type.create(level);
                try {
                    incoming.moveTo(wearer.position().add(6, .8, 0));
                    incoming.setDeltaMovement(-3, 0, 0);
                    ProjectileBarrier.tick(incoming, () -> h.fail("Captured momentum attacks must not execute another native hit: " + type));
                    h.assertTrue(incoming.isRemoved() && !ProjectileBarrier.path(incoming).active(),
                            "Every native momentum family dissipates and clears its trajectory: " + type);
                } finally { incoming.discard(); }
            }
            Projectile own = EntityType.SMALL_FIREBALL.create(level);
            Projectile away = EntityType.SMALL_FIREBALL.create(level);
            Projectile late = EntityType.SMALL_FIREBALL.create(level);
            Projectile impact = EntityType.SMALL_FIREBALL.create(level);
            try {
                own.moveTo(wearer.position().add(6, .8, 0)); own.setOwner(wearer); own.setDeltaMovement(-3, 0, 0);
                boolean[] nativeRan = { false };
                ProjectileBarrier.tick(own, () -> nativeRan[0] = true);
                h.assertTrue(nativeRan[0] && !own.isRemoved(), "The wearer's own attack is exempt from disposal");
                away.moveTo(wearer.position().add(6, .8, 0)); away.setDeltaMovement(1, 0, 0);
                ProjectileBarrier.tick(away, () -> {});
                h.assertTrue(!away.isRemoved(), "Presence near a field does not count as interception");
                late.moveTo(wearer.position().add(6, .8, 0));
                ProjectileBarrier.tick(late, () -> late.setPos(late.position().add(-3, 0, 0)));
                h.assertTrue(late.isRemoved(), "A native direct-position update uses the same disposal policy");
                impact.moveTo(wearer.position().add(6, .8, 0));
                ProjectileBarrier.tick(impact, () -> {
                    var event = new ProjectileImpactEvent(impact, new EntityHitResult(wearer));
                    ProjectileBarrier.impact(event);
                    h.assertTrue(event.isCanceled() && impact.isRemoved(),
                            "A denied native impact dissipates without invoking onHit or an explosion");
                });
            } finally { own.discard(); away.discard(); late.discard(); impact.discard(); }
        } finally { wearer.discard(); TurtleField.invalidate(level); }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=300, batch="flight-release-lifetime")
    public static void stalledUnknownFlightsExpireAfterFieldRelease(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer wearer = wearer(h);
        Snowball stalled = weightless(level), untouched = weightless(level);
        Snowball finite = new Snowball(EntityType.SNOWBALL, level) {
            private int updates;
            @Override protected double getDefaultGravity() { return 0; }
            @Override public void tick() { super.tick(); if (++updates >= 60) discard(); }
        };
        stalled.moveTo(wearer.position().add(6, .8, 0)); stalled.setDeltaMovement(-3, 0, 0);
        finite.moveTo(wearer.position().add(6, .8, 1)); finite.setDeltaMovement(-3, 0, 0);
        untouched.moveTo(wearer.position().add(30, 10, 0));
        level.addFreshEntity(stalled); level.addFreshEntity(finite); level.addFreshEntity(untouched);
        Runnable cleanup = () -> { stalled.discard(); finite.discard(); untouched.discard(); wearer.discard(); TurtleField.invalidate(level); };
        checked(h, 40, () -> {
            h.assertTrue(!stalled.isRemoved() && ProjectileBarrier.path(stalled).active(),
                    "Unknown weightless projectiles receive their native lifetime before stall cleanup");
            h.assertTrue(!finite.isRemoved(), "A finite native projectile is not immediately classified as immortal");
            CuriosApi.getCuriosInventory(wearer).orElseThrow().setEquippedCurio("rune", 0, ItemStack.EMPTY);
            TurtleField.invalidate(level);
        }, cleanup);
        checked(h, 65, () -> {
            h.assertTrue(finite.isRemoved(), "Native expiry still runs before the fallback deadline");
            h.assertTrue(!stalled.isRemoved() && !ProjectileBarrier.path(stalled).active(),
                    "Releasing the field preserves the pending watch instead of prematurely deleting the projectile");
        }, cleanup);
        checked(h, 270, () -> {
            h.assertTrue(stalled.isRemoved(), "A captured flight that cannot resume does not remain forever after unequipping");
            h.assertTrue(!untouched.isRemoved(), "A stationary projectile never intercepted by the field is not cleaned up");
            cleanup.run(); h.succeed();
        }, cleanup);
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=300, batch="flight-held-lifetime")
    public static void onlyDisposableFlightsExpireDuringLongInterception(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer wearer = wearer(h);
        Snowball disposable = weightless(level);
        Arrow arrow = new Arrow(EntityType.ARROW, level);
        ThrownTrident trident = new ThrownTrident(EntityType.TRIDENT, level);
        List<Projectile> flights = List.of(disposable, arrow, trident);
        for (int i = 0; i < flights.size(); i++) {
            Projectile flight = flights.get(i);
            flight.moveTo(wearer.position().add(6, .8, i - 1));
            flight.setDeltaMovement(-3, 0, 0); level.addFreshEntity(flight);
        }
        Runnable cleanup = () -> { flights.forEach(Projectile::discard); wearer.discard(); TurtleField.invalidate(level); };
        checked(h, 40, () -> {
            for (Projectile flight : flights)
                h.assertTrue(!flight.isRemoved() && ProjectileBarrier.path(flight).active(),
                        "All test flights must actually enter the field: " + flight.getType());
        }, cleanup);
        checked(h, 270, () -> {
            h.assertTrue(disposable.isRemoved(), "An unknown disposable attack has a bounded stalled lifetime");
            h.assertTrue(!arrow.isRemoved() && !trident.isRemoved(),
                    "Recoverable arrows and tridents are not discarded by the stall deadline");
            CuriosApi.getCuriosInventory(wearer).orElseThrow().setEquippedCurio("rune", 0, ItemStack.EMPTY);
            TurtleField.invalidate(level);
        }, cleanup);
        checked(h, 275, () -> {
            h.assertTrue(!arrow.isRemoved() && !trident.isRemoved() && arrow.getDeltaMovement().y < 0
                            && trident.getDeltaMovement().y < 0,
                    "Long-held recoverable items resume native gravity after release");
            cleanup.run(); h.succeed();
        }, cleanup);
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=310, batch="flight-resumption")
    public static void resumedOrReownedFlightsLeaveTheOldCleanupWatch(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer wearer = wearer(h);
        Snowball resumed = weightless(level), reowned = weightless(level);
        resumed.moveTo(wearer.position().add(6, .8, -1)); resumed.setDeltaMovement(-3, 0, 0);
        reowned.moveTo(wearer.position().add(6, .8, 1)); reowned.setDeltaMovement(-3, 0, 0);
        level.addFreshEntity(resumed); level.addFreshEntity(reowned);
        Runnable cleanup = () -> { resumed.discard(); reowned.discard(); wearer.discard(); TurtleField.invalidate(level); };
        checked(h, 40, () -> {
            h.assertTrue(ProjectileBarrier.path(resumed).active() && ProjectileBarrier.path(reowned).active(),
                    "Both flights must be captured before checking cleanup cancellation");
            reowned.setOwner(wearer);
            CuriosApi.getCuriosInventory(wearer).orElseThrow().setEquippedCurio("rune", 0, ItemStack.EMPTY);
            TurtleField.invalidate(level);
        }, cleanup);
        Vec3[] releasePosition = { Vec3.ZERO };
        checked(h, 45, () -> {
            releasePosition[0] = resumed.position();
            resumed.setDeltaMovement(.2, 0, 0);
        }, cleanup);
        checked(h, 55, () -> {
            h.assertTrue(resumed.position().distanceTo(releasePosition[0]) > .5,
                    "Native propulsion really resumed after release");
            resumed.setDeltaMovement(Vec3.ZERO);
        }, cleanup);
        checked(h, 290, () -> {
            h.assertTrue(!resumed.isRemoved(), "A later native pause cannot reuse an obsolete capture watch");
            h.assertTrue(!reowned.isRemoved(), "Changing the owner invalidates the old interception lifecycle");
            cleanup.run(); h.succeed();
        }, cleanup);
    }

    private static Snowball weightless(ServerLevel level) {
        return new Snowball(EntityType.SNOWBALL, level) {
            @Override protected double getDefaultGravity() { return 0; }
        };
    }

    private static FakePlayer wearer(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var wearer = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "flight-wearer"));
        wearer.moveTo(Vec3.atCenterOf(h.absolutePos(new BlockPos(3, 4, 3))));
        level.addNewPlayer(wearer);
        var inventory = CuriosApi.getCuriosInventory(wearer).orElseThrow(); inventory.reset();
        inventory.setEquippedCurio("rune", 0, new ItemStack(AccessoryContent.TURTLE_RUNE.get()));
        TurtleField.invalidate(level);
        return wearer;
    }

    private static void fire(TestSonic boom, ServerLevel level, Warden warden, Cow target) {
        warden.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, target);
        warden.getBrain().eraseMemory(MemoryModuleType.SONIC_BOOM_SOUND_DELAY);
        warden.getBrain().eraseMemory(MemoryModuleType.SONIC_BOOM_SOUND_COOLDOWN);
        boom.fire(level, warden);
    }

    private static final class TestSonic extends SonicBoom {
        void fire(ServerLevel level, Warden warden) { super.tick(level, warden, level.getGameTime()); }
    }

    private static void checked(GameTestHelper h, long delay, Runnable check, Runnable cleanup) {
        h.runAfterDelay(delay, () -> {
            try { check.run(); }
            catch (RuntimeException | Error failure) { cleanup.run(); throw failure; }
        });
    }
}
