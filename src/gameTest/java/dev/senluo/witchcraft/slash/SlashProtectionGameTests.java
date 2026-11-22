package dev.senluo.witchcraft.slash;

import com.mojang.authlib.GameProfile;
import dev.senluo.witchcraft.accessory.AccessoryContent;
import dev.senluo.witchcraft.accessory.ProjectileBarrier;
import dev.senluo.witchcraft.accessory.TurtleField;
import dev.senluo.witchcraft.motion.BezierSpeed;
import dev.senluo.witchcraft.motion.TravelStep;
import dev.senluo.witchcraft.slash.drawing.RadialOutline;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkTicketLevelUpdatedEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import top.theillusivec4.curios.api.CuriosApi;

@PrefixGameTestTemplate(false)
public final class SlashProtectionGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=10)
    public static void slashTimingKeepsFormationAndSharedCutoff(GameTestHelper h) {
        var free = new TravelStep(Vec3.ZERO, new Vec3(1, 0, 0), 4, 4, 0, 0);
        var forming = new SlashCollision.Timing(free, 0, 0);
        h.assertTrue(forming.at(1) == 0 && forming.before(0, 1) == 0 && forming.before(.1, 1) == 1,
                "Formation contacts remain instantaneous, with the original cutoff priority");
        var field = TravelStep.quote(Vec3.ZERO, new Vec3(1, 0, 0), 2, 4, 2, 4);
        var normal = new SlashCollision.Timing(free, .25, 8);
        var slowed = new SlashCollision.Timing(field, .25, 8);
        h.assertTrue(normal.before(.375, 1) == .25 && Math.abs(slowed.before(.375, 1) - .5) < 1e-12,
                "Differently scaled fronts stop at the same event time through their own quotes");
        for (double time : new double[]{.25, Math.nextUp(.25), .333333333333, .375, .49999999999}) {
            h.assertTrue(normal.at(normal.before(time, 1)) <= time && slowed.at(slowed.before(time, 1)) <= time,
                    "Rounding cannot move either front past the shared event");
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=20)
    public static void coveragePreservesSplitOrderAndLimits(GameTestHelper h) {
        Vec3 start = new Vec3(.25, .25, .25);
        Vec3 blade = new Vec3(0, 0, 1);
        var small = new SlashGeometry.Sweep(start, start.add(255, 0, 0), blade, 0, 0, 0);
        h.assertTrue(new SlashQueries.Coverage(small).boxes().equals(List.of(small.bounds())), "A 256-cell sweep uses exactly its original bounds");

        Vec3 middle = start.add(128, 0, 0);
        Vec3 end = start.add(256, 0, 0);
        var longSweep = new SlashGeometry.Sweep(start, end, blade, 0, 0, 0);
        h.assertTrue(new SlashQueries.Coverage(longSweep).boxes().equals(List.of(new AABB(start, middle), new AABB(middle, end))),
                "Travel splits visit the start half before the end half");
        var wide = new SlashGeometry.Sweep(start, start, blade, 128, 128, 0);
        h.assertTrue(new SlashQueries.Coverage(wide).boxes().equals(List.of(new AABB(start, start.add(0, 0, 128)),
                new AABB(start.add(0, 0, -128), start))), "Width splits visit the positive blade half first");

        var oversized = new SlashGeometry.Sweep(start, start.add(1_000_000, 0, 0), blade, 0, 0, 0);
        var invalid = new SlashGeometry.Sweep(start, end, blade, 0, 0, Double.NaN);
        h.assertTrue(new SlashQueries.Coverage(oversized).boxes().isEmpty() && new SlashQueries.Coverage(invalid).boxes().isEmpty(),
                "Invalid and over-budget sweeps yield no partial cover");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=20)
    public static void coverBudgetMatchesEnumeratedCells(GameTestHelper h) {
        Vec3 start = new Vec3(.25, .25, .25);
        Vec3 blade = new Vec3(0, 0, 1);
        for (int length : new int[]{255, 256, 2039, 2040, 2041, 4096}) {
            var sweep = new SlashGeometry.Sweep(start, start.add(length, 0, 0), blade, 0, 0, 0);
            var coverage = new SlashQueries.Coverage(sweep);
            int visits = 0;
            for (AABB box : coverage.boxes()) for (var ignored : SlashQueries.cells(box)) visits++;
            for (int limit : new int[]{visits - 1, visits, visits + 1, SlashCollision.MAX_CELLS}) {
                h.assertTrue(coverage.exceedsCellLimit(limit) == (visits > limit), "Admission must agree with cell visits");
                int subtotal = coverage.countCellsUntilOver(limit);
                h.assertTrue(visits > limit ? subtotal > limit : subtotal == visits,
                        "Accepted sweeps must charge exactly for every visited cell, including overlaps");
            }
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=40)
    public static void coverageReadsCurrentLoadedChunks(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(BlockPos.ZERO);
        var near = new AABB(origin);
        int x = Math.floorDiv(origin.getX(), 16) - 512;
        int z = Math.floorDiv(origin.getZ(), 16);
        int edge = x * 16;
        var distant = new AABB(edge, origin.getY(), z * 16, Math.nextDown(edge + 16.0), origin.getY() + 1, z * 16 + 1);
        var separate = List.of(near, distant);
        h.assertTrue(SlashQueries.loaded(level, near) && !level.hasChunk(x, z), "Only the local test chunk is loaded");
        h.assertTrue(!SlashQueries.loaded(level, List.of(distant)) && !SlashQueries.loaded(level, separate)
                && !level.hasChunk(x, z), "Single and disjoint covers both wait without loading terrain");
        int full = ChunkLevel.byStatus(ChunkStatus.FULL);
        long target = ChunkPos.asLong(x, z);
        var admissions = new AtomicInteger();
        Consumer<ChunkTicketLevelUpdatedEvent> observe = event -> {
            if (event.getLevel() == level && event.getChunkPos() == target
                    && event.getOldTicketLevel() > full && event.getNewTicketLevel() <= full)
                admissions.incrementAndGet();
        };
        NeoForge.EVENT_BUS.addListener(observe);
        try {
            level.setChunkForced(x, z, true);
            level.getChunk(x, z);
            h.assertTrue(admissions.get() > 0, "The native FULL ticket transition signals the first admitted chunk");
            h.assertTrue(SlashQueries.loaded(level, List.of(distant)) && SlashQueries.loaded(level, separate)
                    && !level.hasChunk(x + 256, z), "Both paths resume with the target present, across an unloaded gap");
        } finally {
            NeoForge.EVENT_BUS.unregister(observe);
            level.setChunkForced(x, z, false);
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=30,batch="slash-field")
    public static void radialUsesTheSlowestTrackBesideAWearer(GameTestHelper h) {
        var level = h.getLevel();
        Vec3 center = h.absolutePos(new BlockPos(4, 32, 4)).getCenter();
        var caster = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "field-caster"));
        caster.setPos(center);
        var carrier = slash(h, caster);
        carrier.setPos(center);
        var wearer = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "field-wearer"));
        wearer.setPos(center.add(3.05, -.9, 19.4));
        level.addNewPlayer(wearer);
        try {
            var slots = CuriosApi.getCuriosInventory(wearer).orElseThrow();
            slots.reset();
            slots.setEquippedCurio("rune", 0, new ItemStack(AccessoryContent.TURTLE_RUNE.get()));
            TurtleField.invalidate(level);
            Vec3 left = new Vec3(-Math.sin(.12), 0, Math.cos(.12));
            Vec3 right = new Vec3(Math.sin(.12), 0, Math.cos(.12));
            var outline = new CompoundTag();
            outline.putInt("GeometryVersion", 1); outline.putDouble("InitialRadius", 14);
            var directions = new ListTag();
            for (Vec3 direction : List.of(left, right)) {
                var data = new CompoundTag();
                data.putDouble("X", direction.x); data.putDouble("Y", direction.y); data.putDouble("Z", direction.z);
                directions.add(data);
            }
            outline.put("Directions", directions);
            var saved = new RadialSlash(RadialOutline.load(outline)).save();
            saved.putLongArray("Radii", new long[]{Double.doubleToRawLongBits(14)});
            saved.putByteArray("Phases", new byte[]{1});
            var radial = RadialSlash.load(saved, SlashShape.DEFAULT.range());
            var space = ProjectileBarrier.space(carrier);
            double first = space.quotePoint(center.add(left.scale(14)), left, 1, 1).distance();
            double last = space.quotePoint(center.add(right.scale(14)), right, 1, 1).distance();
            h.assertTrue(space.hasFields() && last < first - .01 && last > 0,
                    "The outer track enters a slower band: fields=" + space.hasFields() + ", left=" + first + ", right=" + last);
            h.assertTrue(radial.tick(carrier, caster, SlashShape.DEFAULT)
                            && Math.abs(radial.maximumRadius() - (14 + last)) < 1e-7,
                    "The entire advancing front spends one tick at its slowest sampled track");
        } finally {
            carrier.discard(); wearer.discard(); TurtleField.invalidate(level);
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=20,batch="slash-protection")
    public static void radialFissionKeepsTinySurplusAfterSkeletonHorseHit(GameTestHelper h) {
        var player = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "radial-horse"));
        Vec3 center = h.absolutePos(new BlockPos(8, 4, 8)).getCenter();
        player.setPos(center.add(-4, 0, 0));
        var horse = EntityType.SKELETON_HORSE.create(h.getLevel());
        horse.setNoAi(true); horse.setPos(center.add(0, -.6, 2));
        horse.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20);
        horse.setHealth(Math.nextDown(20f)); h.getLevel().addFreshEntity(horse);
        var slash = slash(h, player); slash.setPos(center);
        var tag = new CompoundTag(); tag.putInt("GeometryVersion", 1); tag.putDouble("InitialRadius", 3);
        var points = new ListTag();
        for (Vec3 value : new Vec3[]{new Vec3(0, 0, 1), new Vec3(Math.sin(.1), 0, Math.cos(.1))}) {
            var point = new CompoundTag();
            point.putDouble("X", value.x); point.putDouble("Y", value.y); point.putDouble("Z", value.z);
            points.add(point);
        }
        tag.put("Directions", points); slash.radial(RadialOutline.load(tag));
        var uncut = slash.radial().snapshot();
        try {
            boolean alive = slash.radial().tick(slash, player, SlashShape.DEFAULT);
            h.assertTrue(!horse.isAlive() && alive && slash.potential().remaining() > 0
                            && slash.potential().remaining() < 1e-5 && slash.fragments() == null,
                    "Native lethal damage leaves the tiny radial surplus alive without left-click scattering");
            double half = 0;
            for (int i = 0; i < slash.radial().size(); i++) half += slash.radial().edge(i).half();
            h.assertTrue(half > 0 && half < 1e-6 && slash.radial().geometry().getInt("Topology") == 1,
                    "The actual contact-to-trim chain publishes one topology and keeps representable remainders");
            h.assertTrue(!slash.radial().matchesSnapshot(uncut), "A cut publishes the new topology even at the same radius");
            var saved = slash.radial().save();
            h.assertTrue(RadialSlash.load(saved, SlashShape.DEFAULT.range()).save().equals(saved),
                    "The committed cut and continued front progress survive native serialization");
        } finally { horse.discard(); slash.discard(); }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=20,batch="slash-protection")
    public static void sectionalHitKeepsSurplusAndHonorsNativeVeto(GameTestHelper h) {
        var player = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "section-damage"));
        var at = h.absolutePos(new BlockPos(8, 3, 8)); player.setPos(at.getCenter().add(4, 0, 0));
        var cow = EntityType.COW.create(h.getLevel()); cow.setNoAi(true); cow.setPos(at.getCenter());
        cow.setHealth(5); h.getLevel().addFreshEntity(cow);
        var blocked = slash(h, player);
        var sweep = new SlashGeometry.Sweep(cow.getBoundingBox().getCenter().add(0, 0, -1.5),
                cow.getBoundingBox().getCenter().add(0, 0, 1.5), new Vec3(1, 0, 0), .25, .25);
        var timing = new SlashCollision.Timing(new TravelStep(sweep.from(), new Vec3(0, 0, 1), 3, 3, 0, 0), 0, 3);
        Consumer<LivingIncomingDamageEvent> veto = event -> { if (event.getEntity() == cow) event.setCanceled(true); };
        NeoForge.EVENT_BUS.addListener(veto);
        try {
            var rejected = SlashCollision.resolveParts(blocked, player, List.of(new SlashQueries.Coverage(sweep)),
                    new SlashCollision.Budget(ProjectileBarrier.space(blocked)), List.of(timing));
            h.assertTrue(rejected.cut() == null && blocked.potential().remaining() == 4 && cow.getHealth() == 5,
                    "Rejected native damage cannot consume potential or cut geometry");
        } finally { NeoForge.EVENT_BUS.unregister(veto); }
        var allowed = slash(h, player);
        var result = SlashCollision.resolveParts(allowed, player, List.of(new SlashQueries.Coverage(sweep)),
                new SlashCollision.Budget(ProjectileBarrier.space(allowed)), List.of(timing));
        h.assertTrue(!cow.isAlive() && result.cut() != null && Math.abs(result.cut().proportion() - .25) < 1.0E-6,
                "Native 20 damage against 5 health commits a quarter-length section");
        h.assertTrue(Math.abs(allowed.potential().remaining() - 3) < 1.0E-6 && allowed.fragments() == null
                        && !result.stopped()[0] && result.fractions()[0] < 1,
                "The remaining budget survives a recollection boundary, without scattering or treating it as a wall");
        cow.discard(); blocked.discard(); allowed.discard(); h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=30, batch="slash-protection")
    public static void radialPublishesStopAndResumeOnce(GameTestHelper h) {
        var player = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "radial-frames"));
        player.setPos(h.absolutePos(new BlockPos(5, 12, 5)).getCenter());
        var slash = slash(h, player);
        var outline = new CompoundTag();
        outline.putInt("GeometryVersion", 1); outline.putDouble("InitialRadius", 2);
        var points = new ListTag();
        for (double angle : new double[]{-.1, 0, .1}) {
            var point = new CompoundTag();
            point.putDouble("X", Math.sin(angle)); point.putDouble("Y", 0); point.putDouble("Z", Math.cos(angle));
            points.add(point);
        }
        outline.put("Directions", points);
        slash.radial(RadialOutline.load(outline));
        try {
            var radial = slash.radial();
            var initial = radial.snapshot();
            h.assertTrue(!radial.matchesSnapshot(new CompoundTag()) && radial.matchesSnapshot(initial), "A new observer needs the initial frame");
            radial.tick(slash, player, SlashShape.DEFAULT);
            var moving = radial.snapshot();
            h.assertTrue(!radial.matchesSnapshot(initial) && moving.getByteArray("Moving")[1] == 1, "Both fronts started in the loaded area");
            radial.pause();
            h.assertTrue(!radial.matchesSnapshot(moving), "The first stopped frame must publish");
            var stopped = radial.snapshot();
            radial.pause();
            h.assertTrue(radial.matchesSnapshot(stopped), "Continued pause reuses the published frame");
            var partial = stopped.copy();
            partial.getByteArray("Moving")[1] = 1;
            h.assertTrue(!radial.matchesSnapshot(partial), "A difference confined to the last front still publishes");
            var client = RadialSlash.fromGeometry(radial.geometry(), null);
            client.acceptSnapshot(stopped, SlashShape.DEFAULT.range());
            client.clientTick();
            h.assertTrue(client.opacity(1, 1) == 0, "A late observer sees the stopped front");
            radial.tick(slash, player, SlashShape.DEFAULT);
            h.assertTrue(!radial.matchesSnapshot(stopped), "A resumed front publishes again");
            client.acceptSnapshot(radial.snapshot(), SlashShape.DEFAULT.range()); client.clientTick();
            h.assertTrue(client.opacity(1, 1) > 0, "The client displays resumed motion");
        } finally { slash.discard(); }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=40)
    public static void radialWaitResumesOnFirstEligibleTick(GameTestHelper h) {
        var level = h.getLevel();
        var caster = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "radial-wake"));
        var carrier = slash(h, caster);
        var home = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        var remote = new ChunkPos(home.x + 640, home.z + 640);
        var center = new Vec3(remote.getMiddleBlockX(), level.getMaxBuildHeight()-14, remote.getMiddleBlockZ());
        carrier.setPos(center);
        carrier.radial(testOutline(0, .05));
        var radial = carrier.radial();
        var old = radial.save();
        h.assertFalse(level.hasChunk(remote.x, remote.z), "The remote target starts in an unloaded chunk");
        try {
            for (int i = 0; i < 6; i++) {
                h.assertTrue(radial.tick(carrier, caster, SlashShape.DEFAULT) && old.equals(radial.save()),
                        "Waiting spends no motion, lifetime or work and survives save");
            }
            h.assertFalse(level.hasChunk(remote.x, remote.z), "Waiting did not request the chunk");
            level.setChunkForced(remote.x, remote.z, true);
            level.getChunk(remote.x, remote.z);
            var reloaded = RadialSlash.load(old, SlashShape.DEFAULT.range());
            h.assertTrue(radial.tick(carrier, caster, SlashShape.DEFAULT)
                    && reloaded.tick(carrier, caster, SlashShape.DEFAULT) && radial.maximumRadius() > 0
                    && radial.save().equals(reloaded.save()), "A live and a reloaded front resume on the same tick with no catch-up");
        } finally {
            radial.releaseWaits();
            carrier.discard();
            level.setChunkForced(remote.x, remote.z, false);
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60, batch="slash-field")
    public static void movingFieldRequotesAWaitingFront(GameTestHelper h) {
        var level = h.getLevel();
        var base = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        var anchor = new ChunkPos(base.x + 710, base.z + 710);
        var caster = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "radial-field"));
        var wearer = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "radial-rune"));
        wearer.setPos(h.absolutePos(BlockPos.ZERO).getCenter());
        level.addNewPlayer(wearer);
        var carrier = slash(h, caster);
        double angle = Math.asin(.65);
        var ray = new Vec3(Math.sin(angle), 0, Math.cos(angle));
        var state = new RadialSlash(testOutline(angle - .001, angle + .001)).save();
        state.putByteArray("Phases", new byte[]{1});
        state.putLongArray("Radii", new long[]{Double.doubleToRawLongBits(2)});
        var radial = RadialSlash.load(state, SlashShape.DEFAULT.range());
        try {
            level.setChunkForced(anchor.x, anchor.z, true);
            level.getChunk(anchor.x, anchor.z);
            int edge = anchor.x + 1;
            while (edge < anchor.x + 16 && level.hasChunk(edge, anchor.z)) edge++;
            var missing = new ChunkPos(edge, anchor.z);
            h.assertTrue(level.hasChunk(edge - 1, anchor.z) && !level.hasChunk(edge, anchor.z),
                    "The blade starts on the loaded side of a real chunk boundary");
            var front = new Vec3(missing.getMinBlockX() - .12, level.getMaxBuildHeight() - 20, missing.getMiddleBlockZ());
            var center = front.subtract(ray.scale(2));
            carrier.setPos(center);
            var slots = CuriosApi.getCuriosInventory(wearer).orElseThrow();
            slots.reset();
            TurtleField.invalidate(level);
            h.assertTrue(!ProjectileBarrier.space(carrier).hasFields()
                    && radial.tick(carrier, caster, SlashShape.DEFAULT) && radial.save().equals(state),
                    "Even the shortest free step crosses the unavailable boundary");
            slots.setEquippedCurio("rune", 0, new ItemStack(AccessoryContent.TURTLE_RUNE.get()));
            // Place the wearer in the loaded chunk too, with its sphere just ahead of the oblique ray.
            wearer.setPos(front.add(-.2, -wearer.getBbHeight() * .5, 4.12));
            TurtleField.invalidate(level);
            var fresh = RadialSlash.load(state, SlashShape.DEFAULT.range());
            try {
                var space = ProjectileBarrier.space(carrier);
                var quote = space.quotePoint(front, ray, 1, 1);
                h.assertTrue(space.hasFields() && quote.distance() > 0 && quote.distance() < .07,
                        "The new field must admit a step within the loaded side, quote=" + quote);
                boolean resumed = radial.tick(carrier, caster, SlashShape.DEFAULT);
                boolean fromSave = fresh.tick(carrier, caster, SlashShape.DEFAULT);
                h.assertTrue(resumed && fromSave && radial.maximumRadius() > 2 && radial.save().equals(fresh.save()),
                        "A waiting and restored front resume together. Waiting=" + radial.save() + ", restored="
                                + fresh.save() + ", field quote=" + quote);
            } finally { fresh.releaseWaits(); }
            var advanced = radial.save();
            wearer.setPos(front.add(-4, -wearer.getBbHeight() * .5, -4));
            var free = ProjectileBarrier.space(carrier).quotePoint(front, ray, 1, 1);
            boolean present = level.hasChunk(edge, anchor.z);
            boolean kept = radial.tick(carrier, caster, SlashShape.DEFAULT);
            h.assertTrue(free.distance() == 1 && !present && kept && radial.save().equals(advanced),
                    "Moving the field restores the blocked free path. Chunk available=" + present
                            + ", free quote=" + free + ", before=" + advanced + ", after=" + radial.save());
        } finally {
            radial.releaseWaits();
            carrier.discard();
            wearer.discard();
            TurtleField.invalidate(level);
            level.setChunkForced(anchor.x, anchor.z, false);
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=30)
    public static void statisticalRangeEndsOnlyTheOversizedFront(GameTestHelper h) {
        var level = h.getLevel();
        var caster = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "radial-size"));
        var carrier = slash(h, caster);
        carrier.setPos(h.absolutePos(new BlockPos(4, 14, 4)).getCenter());
        var shape = new SlashShape(24000, 1, 2);
        double radius = 20000;
        var old = new RadialSlash(testOutline(0, .3, .31)).save();
        old.putByteArray("Phases", new byte[]{1, 0});
        old.putLongArray("Radii", new long[]{Double.doubleToRawLongBits(radius),0});
        old.putLongArray("Work", new long[]{Double.doubleToRawLongBits(radius-2),0});
        var radial = RadialSlash.load(old, shape.range());
        try {
            h.assertTrue(radial.tick(carrier, caster, shape), "A precise sibling remains active");
            var after = radial.save();
            h.assertTrue(after.getByteArray("Phases")[0] == 2 && after.getByteArray("Phases")[1] == 1
                    && after.getLongArray("Radii")[0] == old.getLongArray("Radii")[0]
                    && after.getLongArray("Work")[0] == old.getLongArray("Work")[0],
                    "An oversized front ends without advancing or charging its siblings");
            h.assertTrue(carrier.hitBlocks.isEmpty(), "Statistical range publishes no terrain edits");
        } finally { radial.releaseWaits(); carrier.discard(); }
        h.succeed();
    }

    private static RadialOutline testOutline(double... angles) {
        var outline = new CompoundTag();
        outline.putInt("GeometryVersion",1); outline.putDouble("InitialRadius",2);
        var directions = new ListTag();
        for (double angle : angles) {
            var point = new CompoundTag();
            point.putDouble("X", Math.sin(angle)); point.putDouble("Y",0); point.putDouble("Z",Math.cos(angle));
            directions.add(point);
        }
        outline.put("Directions",directions);
        return RadialOutline.load(outline);
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty")
    public static void radialWaitRechecksOnlyItsOwnDependencies(GameTestHelper h) {
        var level = h.getLevel();
        var center = h.absolutePos(BlockPos.ZERO).getCenter();
        var here = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        long first = ChunkPos.asLong(here.x + 700, here.z), other = ChunkPos.asLong(here.x + 703, here.z);
        int full = ChunkLevel.byStatus(ChunkStatus.FULL);
        var wait = new RadialChunkWait(level, center, 2, false, first);
        wait.record(other); wait.register();
        try {
            RadialChunkWait.ticketChanged(new ChunkTicketLevelUpdatedEvent(level, first + 1, full + 1, full, null));
            h.assertTrue(wait.stillBlocked(level, center, 2, false), "Unrelated chunk notifications do not wake this front");
            h.assertFalse(wait.stillBlocked(level, center, 1, false) || wait.stillBlocked(level, center, 2, true)
                    || wait.stillBlocked(level, center.add(.01, 0, 0), 2, false), "Changed geometry or work needs a fresh quote");
            var border = level.getWorldBorder();
            double x = border.getCenterX(), z = border.getCenterZ();
            try {
                border.setCenter(x + 8, z);
                h.assertFalse(wait.stillBlocked(level, center, 2, false), "A shifted border invalidates the stored witness");
            } finally { border.setCenter(x, z); }
            var early = new ChunkTicketLevelUpdatedEvent(level, other, full + 1, full, null);
            RadialChunkWait.ticketChanged(early);
            RadialChunkWait.ticketChanged(early);
            h.assertFalse(wait.stillBlocked(level, center, 2, false) || level.hasChunk(ChunkPos.getX(other), ChunkPos.getZ(other)),
                    "Either witness can wake the entire retry, and an early or duplicate signal never loads or admits it");
        } finally { wait.release(); }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty")
    public static void shrinkingRadialQuotesKeepFirstTrackOnTies(GameTestHelper h) {
        var first = TravelStep.quote(Vec3.ZERO, new Vec3(1,0,0), 1, 1, Double.POSITIVE_INFINITY, 4);
        var slow = TravelStep.quote(Vec3.ZERO, new Vec3(0,0,1), 1, 1, .8, 4);
        for (double cap : new double[]{.125, slow.distance(), Math.nextUp(slow.distance())}) {
            var a = TravelStep.quote(first.from(), first.direction(), 1, cap, Double.POSITIVE_INFINITY, 4);
            var b = TravelStep.quote(slow.from(), slow.direction(), 1, cap, slow.gap(), slow.width());
            var expected = b.distance() < a.distance() ? b : a;
            h.assertTrue(RadialSlash.quoteAtLimit(first, slow, 1, cap).equals(expected),
                    "Match distance, direction, cost and the stored field at cap " + cap);
        }
        h.succeed();
    }

    private static SlashEntity slash(GameTestHelper h, FakePlayer player) {
        var direction = new Vec3(0,0,1);
        var result = new SlashEntity(SlashContent.SLASH.get(), h.getLevel());
        result.prepare(player, 4, 1, 0, ItemStack.EMPTY,
                new SlashFormation.Pose(direction,0,direction,0,0,h.getLevel().dimension(),false),
                SlashShape.DEFAULT, new BezierSpeed(1,1,1,1,24), player.getEyePosition());
        result.upgrades(false,false,true);
        result.arm();
        return result;
    }
    private static void pass(SlashEntity slash, FakePlayer player, Vec3 centre) {
        SlashCollision.resolve(slash,player,new SlashQueries.Coverage(new SlashGeometry.Sweep(centre.add(0,0,-1.5),
                centre.add(0,0,1.5),new Vec3(1,0,0),.25,.25)),
                new SlashCollision.Budget(ProjectileBarrier.space(slash)));
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=20, batch="slash-protection")
    public static void livingHitsKeepTheirOwnParticleAndAudience(GameTestHelper h) {
        var level = h.getLevel();
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "slash-witness"));
        var packets = new java.util.ArrayList<net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket>();
        new net.minecraft.server.network.ServerGamePacketListenerImpl(level.getServer(),
                new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND), player,
                net.minecraft.server.network.CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {
                if (packet instanceof net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket particles
                        && particles.getParticle() instanceof SlashHitParticle) packets.add(particles);
            }
        };
        Vec3 at = h.absolutePos(new BlockPos(8, 3, 8)).getCenter();
        player.setPos(at.add(4, 0, 0));
        Vec3 travel = new Vec3(0, 0, 1), tangent = new Vec3(0, 1, 0);
        var speed = new BezierSpeed(1, 1, 1, 1, 24);
        for (int run = 0; run < 3; run++) {
            boolean red = run == 1, hidden = run == 2;
            packets.clear();
            var targets = new java.util.ArrayList<net.minecraft.world.entity.animal.Cow>();
            var slash = new SlashEntity(SlashContent.SLASH.get(), level);
            try {
                for (int i = 0; i < 3; i++) {
                    var cow = EntityType.COW.create(level);
                    cow.setNoAi(true); cow.setPos(at.add(0, 0, i * 1.45 - 1.45));
                    cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(400); cow.setHealth(400);
                    cow.setInvulnerable(i == 1);
                    level.addFreshEntity(cow); targets.add(cow);
                }
                slash.prepare(player, red ? SlashCritical.potential(4) : 4, 1, 0, ItemStack.EMPTY,
                        new SlashFormation.Pose(travel, 0, travel, 0, 0, level.dimension(), false),
                        SlashShape.DEFAULT, speed, player.getEyePosition());
                if (red) slash.critical(4, speed);
                slash.visibility(hidden ? SlashVisibility.HIDDEN : SlashVisibility.CASTER_ONLY);
                slash.arm();
                var start = at.add(0, .5, -2.6);
                var sweep = new SlashGeometry.Sweep(start, start.add(0, 0, 5.2), tangent, .25, .25);
                SlashCollision.resolve(slash, player, new SlashQueries.Coverage(sweep),
                        new SlashCollision.Budget(ProjectileBarrier.space(slash)));
                h.assertTrue(targets.getFirst().getHealth() < 400 && targets.get(1).getHealth() == 400
                        && targets.getLast().getHealth() < 400, "Two actual hits and an immune target in one sweep");
                h.assertTrue(packets.size() == (hidden ? 0 : 2), "Each visible committed hit gets one burst");
                for (int i = 0; i < packets.size(); i++) {
                    var packet = packets.get(i);
                    var hit = (SlashHitParticle)packet.getParticle();
                    Vec3 expected = sweep.contact(targets.get(i * 2).getBoundingBox()).point();
                    h.assertTrue(hit.blackRed() == red && packet.getCount() == 0 && packet.getYDist() == 1
                            && hit.travel().z == 1 && expected.distanceToSqr(new Vec3(packet.getX(), packet.getY(), packet.getZ())) < 1e-12,
                            "Visual carries the cast style, the actual sweep tangent and its own contact point");
                }
            } finally {
                for (var cow : targets) cow.discard();
                slash.discard();
            }
        }
        h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=20, batch="slash-protection")
    public static void blackFlashContactsPrioritizeOneSpatialSound(GameTestHelper h) {
        var level = h.getLevel();
        Vec3 at = h.absolutePos(new BlockPos(8, 3, 8)).getCenter();
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "flash-audio"));
        player.setPos(at.add(-4, 0, 0));
        var cow = EntityType.COW.create(level);
        cow.setNoAi(true);
        cow.setPos(at);
        level.addFreshEntity(cow);
        var ordinary = slash(h, player);
        var flash = new SlashEntity(SlashContent.SLASH.get(), level);
        Vec3 forward = new Vec3(0, 0, 1);
        var speed = new BezierSpeed(1, 1, 1, 1, 24);
        flash.prepare(player, SlashCritical.potential(4), 1, 0, ItemStack.EMPTY,
                new SlashFormation.Pose(forward, 0, forward, 0, 0, level.dimension(), false),
                SlashShape.DEFAULT, speed, player.getEyePosition());
        flash.critical(4, speed);
        flash.visibility(SlashVisibility.HIDDEN);
        flash.arm();
        Vec3 center = cow.getBoundingBox().getCenter();
        var sweep = new SlashGeometry.Sweep(center.add(0, 0, -1.5), center.add(0, 0, 1.5), new Vec3(1, 0, 0), .25, .25);
        Vec3 actual = sweep.contact(cow.getBoundingBox()).point();
        Vec3 next = actual.add(.75, .1, 0);
        record Heard(net.minecraft.sounds.SoundEvent sound, Vec3 point, long tick) {}
        var heard = new java.util.ArrayList<Heard>();
        Consumer<net.neoforged.neoforge.event.PlayLevelSoundEvent.AtPosition> listener = event -> {
            if (event.getLevel() != level || event.getSound() == null || event.getPosition().distanceToSqr(at) > 4) return;
            var sound = event.getSound().value();
            if (sound == SlashContent.CONTACT.get() || sound == SlashContent.BLACK_FLASH_CONTACT.get()) {
                heard.add(new Heard(sound, event.getPosition(), level.getServer().getTickCount()));
            }
        };
        NeoForge.EVENT_BUS.addListener(listener);
        SlashSounds.livingContact(ordinary, player, next);
        pass(flash, player, center);
        SlashSounds.livingContact(flash, player, next);
        h.runAfterDelay(1, () -> {
            SlashSounds.livingContact(ordinary, player, actual);
            SlashSounds.livingContact(flash, player, next);
        });
        h.runAfterDelay(4, () -> SlashSounds.livingContact(ordinary, player, next));
        h.runAfterDelay(7, () -> SlashSounds.livingContact(ordinary, player, next));
        h.runAfterDelay(9, () -> {
            NeoForge.EVENT_BUS.unregister(listener);
            cow.discard();
            ordinary.discard();
            flash.discard();
            h.assertTrue(heard.size() == 3, "A sweep and an immediate follow-up have one voice per contact window: " + heard);
            h.assertTrue(heard.get(0).sound() == SlashContent.BLACK_FLASH_CONTACT.get() && heard.get(0).point().distanceToSqr(actual) < 1e-10,
                    "A hidden confirmed black flash replaces the ordinary voice at its actual contact");
            h.assertTrue(heard.get(1).sound() == SlashContent.BLACK_FLASH_CONTACT.get() && heard.get(1).point().distanceToSqr(next) < 1e-10
                            && heard.get(1).tick() - heard.get(0).tick() == 3, "Queued flash uses the next free contact tick");
            h.assertTrue(heard.get(2).sound() == SlashContent.CONTACT.get() && heard.get(2).tick() - heard.get(1).tick() >= 3,
                    "Ordinary contact returns after the same caster's cooldown");
            h.succeed();
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=20, batch="slash-protection")
    public static void slashRespectsDataDrivenWardedBlocks(GameTestHelper h) {
        var level = h.getLevel();
        var at = h.absolutePos(new BlockPos(8, 3, 8));
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "warded-claim"));
        player.setPos(at.getCenter().add(4, 0, 0));
        // The test pack adds a soft block so the slash must pass hardness before reaching TC protection.
        var state = Blocks.CHISELED_SANDSTONE.defaultBlockState();
        level.setBlock(at, state, 3);
        var strike = slash(h, player);
        try {
            h.assertTrue(state.is(dev.tc4port.thaumcraft.api.ThaumcraftTags.WARDED_BLOCKS)
                            && strike.cuts(state.getDestroySpeed(level, at), false),
                    "A tagged harvestable block reaches the native break decision");
            pass(strike, player, at.getCenter());
            h.assertTrue(level.getBlockState(at) == state && strike.hitBlocks.isEmpty()
                            && strike.potential().remaining() == 4,
                    "TC ward protection preserves the block and the spell budget");
        } finally { strike.discard(); }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=20,batch="slash-protection")
    public static void slashHonorsBlockProtectionBeforeDestruction(GameTestHelper h) {
        var player=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"claim-break"));
        var at=h.absolutePos(new BlockPos(8,3,8));player.setPos(at.getCenter().add(4,0,0));
        h.getLevel().setBlock(at,Blocks.STONE.defaultBlockState(),3);
        var stopped=slash(h,player);var calls=new AtomicInteger();
        Consumer<BlockEvent.BreakEvent> veto=event->{if(event.getPlayer()==player&&event.getPos().equals(at)){
            calls.incrementAndGet();event.setCanceled(true);
        }};
        NeoForge.EVENT_BUS.addListener(veto);
        try { pass(stopped,player,at.getCenter()); } finally { NeoForge.EVENT_BUS.unregister(veto); }
        h.assertTrue(calls.get()==1&&h.getLevel().getBlockState(at).is(Blocks.STONE),"Claim veto sees the real caster and preserves the target");
        h.assertTrue(stopped.hitBlocks.isEmpty()&&stopped.potential().remaining()==4,"Canceled break commits no harvest or fission debit");
        var allowed=slash(h,player);pass(allowed,player,at.getCenter());
        h.assertTrue(h.getLevel().getBlockState(at).isAir()&&allowed.hitBlocks.contains(at.asLong()),"The same collision harvests normally after permission is restored");
        stopped.discard();allowed.discard();h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=20,batch="slash-protection")
    public static void slashHonorsImpactAndDamageProtection(GameTestHelper h) {
        var player=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"claim-damage"));
        var at=h.absolutePos(new BlockPos(8,3,8));player.setPos(at.getCenter().add(4,0,0));
        var cow=EntityType.COW.create(h.getLevel());cow.setNoAi(true);cow.setPos(at.getCenter());
        cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);cow.setHealth(200);h.getLevel().addFreshEntity(cow);
        var impactStopped=slash(h,player);var damageStopped=slash(h,player);var calls=new AtomicInteger();
        Consumer<ProjectileImpactEvent> impact=event->{if(event.getEntity()==impactStopped)event.setCanceled(true);};
        NeoForge.EVENT_BUS.addListener(impact);
        try { pass(impactStopped,player,cow.getBoundingBox().getCenter()); } finally { NeoForge.EVENT_BUS.unregister(impact); }
        h.assertTrue(cow.getHealth()==200&&impactStopped.potential().remaining()==4,"Canceled projectile impact never reaches damage or fission");
        Consumer<LivingIncomingDamageEvent> veto=event->{if(event.getEntity()==cow){
            h.assertTrue(event.getSource().getEntity()==player,"Damage retains the caster for claim/PvP decisions");
            calls.incrementAndGet();event.setCanceled(true);
        }};
        NeoForge.EVENT_BUS.addListener(veto);
        try { pass(damageStopped,player,cow.getBoundingBox().getCenter()); } finally { NeoForge.EVENT_BUS.unregister(veto); }
        h.assertTrue(calls.get()==1&&cow.getHealth()==200&&damageStopped.potential().remaining()==4,
                "Canceled damage leaves health and potential intact and does not start fission");
        var allowed=slash(h,player);pass(allowed,player,cow.getBoundingBox().getCenter());
        h.assertTrue(cow.getHealth()<200,"The unprotected control receives native damage");
        cow.discard();allowed.discard();impactStopped.discard();damageStopped.discard();h.succeed();
    }
}
