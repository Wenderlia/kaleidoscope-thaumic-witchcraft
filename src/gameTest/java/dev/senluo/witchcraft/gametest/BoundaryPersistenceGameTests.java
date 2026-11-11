package dev.senluo.witchcraft.gametest;

import com.mojang.authlib.GameProfile;
import dev.senluo.witchcraft.accessory.AccessoryContent;
import dev.senluo.witchcraft.accessory.MotionConstraints;
import dev.senluo.witchcraft.accessory.ReachBoundary;
import dev.senluo.witchcraft.accessory.TurtleField;
import dev.senluo.witchcraft.mixin.accessory.MobReachAccess;
import dev.senluo.witchcraft.ritual.*;
import dev.senluo.witchcraft.time.EntityTime;
import dev.senluo.witchcraft.time.TemporalWorld;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.SwellGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import top.theillusivec4.curios.api.CuriosApi;

@PrefixGameTestTemplate(false)
public final class BoundaryPersistenceGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void turtleStopsPushingFinalPositionsAndReleases(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var wearer = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "boundary-wearer"));
        wearer.moveTo(Vec3.atCenterOf(h.absolutePos(new BlockPos(3, 3, 3))));
        level.addNewPlayer(wearer);
        var inventory = CuriosApi.getCuriosInventory(wearer).orElseThrow();
        inventory.reset();
        inventory.setEquippedCurio("rune", 0, new ItemStack(AccessoryContent.TURTLE_RUNE.get()));
        TurtleField.invalidate(level);
        var mob = new ReachCow(level);
        mob.moveTo(wearer.position().add(6, 0, 0));
        mob.setNoAi(true);
        level.addFreshEntity(mob);
        try {
            Vec3 before = mob.position();
            mob.move(MoverType.SELF, new Vec3(-30, 0, 0));
            h.assertTrue(!mob.position().equals(before), "A distant creature can still approach the field");
            h.assertTrue(!mob.isWithinMeleeAttackRange(wearer), "A long step cannot tunnel into native melee reach");
            for (int i = 0; i < 50; i++) {
                mob.push(-8, 0, 0);
                mob.move(MoverType.SELF, mob.getDeltaMovement());
                h.assertTrue(!mob.isWithinMeleeAttackRange(wearer), "Repeated pushes cannot accumulate inward penetration");
            }
            mob.extraReach = 4;
            h.assertTrue(TurtleField.hardMotion(mob, new Vec3(0, 0, 1)) == Vec3.ZERO,
                    "An overridden native attack volume expands the boundary immediately");
            mob.moveTo(wearer.position().add(1, 0, 0));
            Vec3 locked = mob.position();
            mob.push(12, 1, 0);
            h.assertTrue(mob.getDeltaMovement().equals(Vec3.ZERO), "Inner collision impulses cannot build up");
            mob.move(MoverType.PISTON, new Vec3(-1, 0, 0));
            mob.setPos(locked.add(-2, 0, 0));
            h.assertTrue(mob.position().equals(locked), "Pistons and direct ordinary position commits preserve the inner anchor");
            wearer.moveTo(wearer.position().add(.5, 0, 0));
            mob.move(MoverType.SELF, new Vec3(0, 0, 1));
            h.assertTrue(mob.position().equals(locked), "Moving the wearer does not drag a locked creature");
            inventory.setEquippedCurio("rune", 0, ItemStack.EMPTY);
            TurtleField.invalidate(level);
            mob.push(1, 0, 0);
            h.assertTrue(mob.getDeltaMovement().x > 0, "Unequipping releases ordinary motion immediately");

            var boundaries = List.of(new MotionConstraints.Boundary(Vec3.ZERO, 2));
            Vec3 start = new Vec3(3, 0, 0), tangent = new Vec3(-2, 0, 4);
            h.assertTrue(MotionConstraints.apply(start, tangent, boundaries, false).equals(tangent),
                    "A segment that passes outside the sphere is allowed");
            Vec3 slide = MotionConstraints.apply(start, new Vec3(-2, 0, 0), boundaries, false);
            h.assertTrue(slide.x > -1, "After a wall removes tangent motion, the remaining slide is clipped again");
        } finally {
            mob.discard(); wearer.discard(); TurtleField.invalidate(level);
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=30)
    public static void turtleReachKeepsNativeShapeWithoutHeightInflation(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var wearer = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "reach-shape"));
        wearer.moveTo(Vec3.atCenterOf(h.absolutePos(new BlockPos(3, 3, 3))));
        // Geometry fixtures stay out of the live level so equipment hooks cannot reposition them.
        for (Mob mob : List.of(new Zombie(EntityType.ZOMBIE, level), new Spider(EntityType.SPIDER, level),
                new Warden(EntityType.WARDEN, level))) {
            mob.moveTo(wearer.position().add(3, 0, 0));
            var boundaries = reach(mob, wearer);
            Vec3 start = mob.getBoundingBox().getCenter();
            Vec3 mapped = MotionConstraints.apply(start, new Vec3(-.2, 0, 0), boundaries, true);
            h.assertTrue(mapped.x < -.07, "Native mob at three horizontal blocks still advances: " + mob.getType());
            Vec3 clipped = MotionConstraints.apply(start, new Vec3(-30, 0, 0), boundaries, false);
            double horizontal = 3 + clipped.x;
            h.assertTrue(horizontal < 2.15 && horizontal > 1.85,
                    "Height and box diagonals cannot become a roughly three-block stop: " + mob.getType());
            AABB nativeAttack = ((MobReachAccess) mob).witchcraft$attackBounds();
            h.assertTrue(!nativeAttack.move(clipped).intersects(wearer.getHitbox()),
                    "A closer boundary must still stay outside the actual native attack volume");
            mob.moveTo(wearer.position().add(4, 0, 4));
            clipped = MotionConstraints.apply(mob.getBoundingBox().getCenter(), new Vec3(-30, 0, -30), reach(mob, wearer), false);
            h.assertTrue(!((MobReachAccess) mob).witchcraft$attackBounds().move(clipped).intersects(wearer.getHitbox()),
                    "Diagonal and long-step protection uses the full volume, not a smaller replacement sphere");
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=30)
    public static void turtleInteractionReachUsesEyeDistanceAndLiveModifiers(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var wearer = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "reach-attribute"));
        wearer.moveTo(Vec3.atCenterOf(h.absolutePos(new BlockPos(3, 3, 3))));
        var mob = new ReachCow(level);
        mob.moveTo(wearer.position().add(8, 0, 0));
        mob.interactionRange = new AttributeInstance(Attributes.ENTITY_INTERACTION_RANGE, ignored -> {});
        mob.interactionRange.setBaseValue(3);
        Vec3 request = new Vec3(-20, 0, 0), start = mob.getBoundingBox().getCenter();
        Vec3 clipped = MotionConstraints.apply(start, request, reach(mob, wearer), false);
        h.assertTrue(Math.abs(8 + clipped.x - 3.55) < 1.0E-4,
                "Eye height must not be added to horizontal interaction reach");
        double eyeGap = Math.sqrt(wearer.getHitbox().distanceToSqr(mob.getEyePosition().add(clipped)));
        h.assertTrue(eyeGap > 3.25 && eyeGap < 3.2501, "The native eye-to-hitbox range keeps its safety margin");
        mob.interactionRange.addTransientModifier(new AttributeModifier(
                ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft", "test_reach"), 3,
                AttributeModifier.Operation.ADD_VALUE));
        Vec3 expanded = MotionConstraints.apply(start, request, reach(mob, wearer), false);
        h.assertTrue(Math.abs(expanded.x - clipped.x - 3) < 1.0E-4,
                "The next move reads the final modified range without a stale cache");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=30)
    public static void turtleRoundedReachProtectsCornersAndPreservesTangents(GameTestHelper h) {
        AABB box = new AABB(-1, -8, -1, 1, 8, 1);
        var boundary = new MotionConstraints.Boundary(box, .25, 2);
        var boundaries = List.of(boundary);
        Vec3 start = new Vec3(3, 0, 3), request = new Vec3(-10, 0, -10);
        Vec3 allowed = MotionConstraints.apply(start, request, boundaries, false);
        double gap = Math.sqrt(box.distanceToSqr(start.add(allowed)));
        h.assertTrue(gap >= .25 && gap < .2501, "A diagonal step stops at the rounded corner, not an oversized sphere");
        Vec3 tangent = new Vec3(0, .2, 0);
        h.assertTrue(MotionConstraints.apply(new Vec3(1.2, 0, 1.2), tangent, boundaries, true).equals(tangent),
                "Being outside a rounded corner must not lock safe parallel motion");
        Vec3 onPlane = new Vec3(1, 0, 3), crossing = new Vec3(.3, 0, -3);
        Vec3 planeAllowed = MotionConstraints.apply(onPlane, crossing, boundaries, false);
        double planeGap = Math.sqrt(box.distanceToSqr(onPlane.add(planeAllowed)));
        h.assertTrue(planeGap >= .25 && planeGap < .2501,
                "A segment starting on a box face plane still counts motion away from that plane");
        Vec3 far = new Vec3(-5_807_899.5, -54.45, -160_789.5);
        Vec3 farAllowed = MotionConstraints.apply(start.add(far), request,
                List.of(new MotionConstraints.Boundary(box.move(far), .25, 2)), false);
        h.assertTrue(farAllowed.distanceToSqr(allowed) < 1.0E-14,
                "World translation does not change rounded boundary intersection");
        var shortBox = List.of(new MotionConstraints.Boundary(new AABB(-1, -1, -1, 1, 1, 1), .25, 2));
        Vec3 side = new Vec3(3, 0, 0), step = new Vec3(-.2, 0, 0);
        h.assertTrue(MotionConstraints.apply(side, step, boundaries, true)
                        .distanceToSqr(MotionConstraints.apply(side, step, shortBox, true)) < 1.0E-20,
                "Extending a box vertically cannot change approach mapping at the same height");
        h.succeed();
    }

    private static List<MotionConstraints.Boundary> reach(Mob mob, FakePlayer wearer) {
        var boundaries = new ArrayList<MotionConstraints.Boundary>();
        ReachBoundary.append(boundaries, mob, wearer);
        return boundaries;
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void turtleStopsCreeperBeforeIgnitionButKeepsNativeFuse(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var wearer = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "creeper-boundary"));
        wearer.moveTo(Vec3.atCenterOf(h.absolutePos(new BlockPos(3, 3, 3))));
        level.addNewPlayer(wearer);
        var inventory = CuriosApi.getCuriosInventory(wearer).orElseThrow();
        inventory.reset();
        inventory.setEquippedCurio("rune", 0, new ItemStack(AccessoryContent.TURTLE_RUNE.get()));
        TurtleField.invalidate(level);
        var creeper = new Creeper(EntityType.CREEPER, level);
        creeper.moveTo(wearer.position().add(6, 0, 0));
        creeper.setNoAi(true); creeper.setTarget(wearer);
        level.addFreshEntity(creeper);
        var swell = new SwellGoal(creeper);
        try {
            for (int i = 0; i < 100; i++) {
                creeper.push(-8, 0, 0);
                creeper.move(MoverType.SELF, creeper.getDeltaMovement());
                h.assertTrue(creeper.distanceToSqr(wearer) > 9 && !swell.canUse(),
                        "Approaching and pushing cannot start the native proximity fuse");
            }
            creeper.setSwellDir(1);
            h.assertTrue(swell.canUse(), "An already primed creeper keeps the native fuse goal");
            creeper.setSwellDir(-1);
            wearer.moveTo(creeper.position().add(-1, 0, 0));
            h.assertTrue(swell.canUse(), "The wearer can still actively approach and trigger ignition");
            Vec3 anchor = creeper.position();
            creeper.move(MoverType.SELF, new Vec3(-1, 0, 0));
            h.assertTrue(creeper.position().equals(anchor), "Proximity ignition does not release the inner motion lock");
            creeper.ignite();
            creeper.tick();
            h.assertTrue(creeper.isIgnited() && creeper.getSwelling(1) > 0,
                    "Manual ignition and native fuse progression remain unchanged");
        } finally {
            creeper.discard(); wearer.discard(); TurtleField.invalidate(level);
        }
        h.succeed();
    }
    private static final class ReachCow extends Cow {
        double extraReach;
        AttributeInstance interactionRange;
        ReachCow(ServerLevel level) { super(EntityType.COW, level); }
        @Override public AttributeInstance getAttribute(Holder<Attribute> attribute) {
            return attribute.equals(Attributes.ENTITY_INTERACTION_RANGE) ? interactionRange : super.getAttribute(attribute);
        }
        @Override protected AABB getAttackBoundingBox() {
            return super.getAttackBoundingBox().inflate(extraReach, 0, extraReach);
        }
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void sustainedRitualSurvivesSerializedReloadAndStopsWithCore(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var core = TreeGameTests.plant(h, true);
        core.tissue.giant = true;
        var owner = EventMigrationGameTests.researchedOwner(h, core);
        core.tissue.bond = TreeBond.of(owner.getUUID(), "owner");
        TemporalWorld.get(level).invalidate();
        Cow subject = h.spawn(EntityType.COW, new BlockPos(3, 3, 5));
        subject.setNoAi(true); subject.setYRot(37);
        var sample = TaglockSample.from(subject);
        var vial = new ItemStack(RitualContent.TAGLOCK.get());
        vial.set(RitualContent.TAGLOCK_SAMPLE.get(), sample);
        owner.setItemInHand(InteractionHand.MAIN_HAND, vial);
        EventMigrationGameTests.prepareSustainedOfferings(h, core);
        var tick = new ServerTickEvent.Post(() -> true, level.getServer());
        UUID id = subject.getUUID();
        try {
            SustainedRitual.start(owner, InteractionHand.MAIN_HAND, core, sample);
            for (int i = 0; i < 85; i++) SustainedRitual.tick(tick);
            h.assertTrue(OwnershipRitual.frozen(subject), "Fixture must finish preparation and accrue 25 active ticks");
            SustainedRitual.contact(subject, owner, SustainedRitual.Contact.TRADE);
            var store = SustainedRitualStore.get(level);
            CompoundTag before = store.snapshot(id);
            long balance = core.tissue.stored(), capacity = core.tissue.coreCapacity();
            SustainedRitual.coreUnloaded(core);
            h.assertTrue(OwnershipRitual.frozen(subject), "Core unloading pauses rather than ends an active session");
            SustainedRitual.stopping(new ServerStoppingEvent(level.getServer()));
            CompoundTag disk = store.save(new CompoundTag(), level.registryAccess());
            var loaded = SustainedRitualStore.load(disk, level.registryAccess());
            level.getDataStorage().set(SustainedRitualStore.NAME, loaded);
            h.assertTrue(OwnershipRitual.frozen(subject), "Serialized sessions resume after runtime state is cleared");
            h.assertTrue(before.equals(loaded.snapshot(id)), "Pose, partial second and unsettled ledger survive exactly");
            EntityTime.tick(subject, () -> { throw new AssertionError("Restored frozen subject ran its native tick"); });
            h.assertTrue(core.tissue.stored() == balance && core.tissue.coreCapacity() == capacity,
                    "Restoration does not mint offline income or capacity");

            CompoundTag entityTag = new CompoundTag();
            subject.save(entityTag);
            subject.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            h.assertTrue(loaded.contains(id), "Entity unload retains the persistent session");
            subject = (Cow) EntityType.loadEntityRecursive(entityTag, level, entity -> entity);
            h.assertTrue(subject != null && level.addFreshEntity(subject), "The subject reloads from its own entity NBT");
            h.assertTrue(OwnershipRitual.frozen(subject), "Entity join restores its freeze before the first native tick");
            for (int i = 0; i < 15; i++) SustainedRitual.tick(tick);
            h.assertTrue(loaded.snapshot(id).getInt("Elapsed") == 40, "Only 15 newly executed ticks advance the saved clock");
            SustainedRitual.coreUnavailable(core);
            h.assertTrue(!OwnershipRitual.frozen(subject) && !loaded.contains(id), "Actual core destruction ends runtime and saved state");

            // A paused target has no runtime Session; removing its original core must still delete its save.
            level.getDataStorage().set(SustainedRitualStore.NAME,
                    SustainedRitualStore.load(disk, level.registryAccess()));
            subject.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            SustainedRitual.coreUnavailable(core);
            h.assertTrue(!SustainedRitualStore.get(level).contains(id), "Core destruction also invalidates an unloaded subject");
        } finally {
            SustainedRitual.coreUnavailable(core);
            if (subject != null) subject.discard();
        }
        h.succeed();
    }
}
