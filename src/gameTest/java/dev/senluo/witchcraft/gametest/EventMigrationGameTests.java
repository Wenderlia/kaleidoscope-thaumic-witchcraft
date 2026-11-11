package dev.senluo.witchcraft.gametest;

import com.mojang.authlib.GameProfile;
import dev.senluo.witchcraft.ritual.*;
import dev.senluo.witchcraft.time.TemporalWorld;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class EventMigrationGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void migratedFreezeGuardsPreserveEffectsAndHealth(GameTestHelper h) {
        var core = TreeGameTests.plant(h, true);
        core.tissue.giant = true;
        var owner = researchedOwner(h, core);
        core.tissue.bond = TreeBond.of(owner.getUUID(), "owner");
        TemporalWorld.get(h.getLevel()).invalidate();
        var subject = h.spawn(EntityType.COW, new BlockPos(3,3,5));
        subject.setNoAi(true);
        subject.setHealth(5);
        subject.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 600));
        var sample = TaglockSample.from(subject);
        var taglock = new ItemStack(RitualContent.TAGLOCK.get());
        taglock.set(RitualContent.TAGLOCK_SAMPLE.get(), sample);
        owner.setItemInHand(InteractionHand.MAIN_HAND, taglock);
        prepareSustainedOfferings(h, core);
        try {
            SustainedRitual.start(owner, InteractionHand.MAIN_HAND, core, sample);
            h.assertTrue(OwnershipRitual.occupied(subject) && !OwnershipRitual.frozen(subject), "Preparation reserves without freezing");
            long before = core.tissue.stored();
            var tick = new net.neoforged.neoforge.event.tick.ServerTickEvent.Post(() -> true, h.getLevel().getServer());
            for (int i = 1; i <= 59; i++) {
                SustainedRitual.tick(tick);
                if (i == 20) {
                    h.assertTrue(offeringCount(h, core, net.minecraft.world.item.Items.COBWEB) == 0
                            && offeringCount(h, core, net.minecraft.world.item.Items.GOLD_INGOT) == 1,
                            "The first offering disappears before the second");
                    subject.setPos(subject.position().add(.25, 0, 0));
                }
                if (i == 50) h.assertTrue(offeringCount(h, core, net.minecraft.world.item.Items.CRYING_OBSIDIAN) == 1,
                        "The reusable catalyst survives its resonance before the final pause");
            }
            h.assertTrue(!OwnershipRitual.frozen(subject) && core.tissue.stored() == before
                    && owner.getMainHandItem().has(RitualContent.TAGLOCK_SAMPLE.get()),
                    "Preparation neither freezes, generates ritual income nor consumes the sample");
            SustainedRitual.tick(tick);
            h.assertTrue(OwnershipRitual.frozen(subject), "Real ritual must start before testing its public guards");
            h.assertTrue(!owner.getMainHandItem().has(RitualContent.TAGLOCK_SAMPLE.get()), "Activation settles the reserved sample");
            h.assertTrue(offeringCount(h, core, net.minecraft.world.item.Items.CRYING_OBSIDIAN) == 1,
                    "Activation releases the reusable crying obsidian");
            subject.heal(2);
            h.assertTrue(subject.getHealth() == 5, "Healing is rejected while frozen");
            h.assertTrue(!subject.addEffect(new MobEffectInstance(MobEffects.POISON, 200)), "Ordinary addition returns false");
            subject.forceAddEffect(new MobEffectInstance(MobEffects.POISON, 200), owner);
            h.assertTrue(!subject.hasEffect(MobEffects.POISON), "Forced addition also passes the applicability event");
            h.assertTrue(!subject.removeEffect(MobEffects.MOVEMENT_SPEED), "Single removal returns false");
            h.assertTrue(!subject.removeAllEffects() && subject.hasEffect(MobEffects.MOVEMENT_SPEED), "Bulk removal preserves its result and existing effects");
            h.assertTrue(subject.isInvulnerableTo(h.getLevel().damageSources().generic()), "Invulnerability query uses the event guard");
        } finally { SustainedRitual.coreUnavailable(core); }
        h.assertTrue(!OwnershipRitual.frozen(subject), "Cleanup releases the subject");
        subject.heal(2);
        h.assertTrue(subject.getHealth() == 7 && subject.addEffect(new MobEffectInstance(MobEffects.POISON, 200)), "Normal health and effects resume after release");
        h.succeed();
    }

    static void prepareSustainedOfferings(GameTestHelper h, dev.senluo.witchcraft.time.LivingEntity core) {
        String[] ring = {"..###..", ".#...#.", "#.....#", "#.....#", "#.....#", ".#...#.", "..###.."};
        BlockPos center = core.getBlockPos().south().below();
        for (int z = 0; z < 7; z++) for (int x = 0; x < 7; x++) if (ring[z].charAt(x) == '#') {
            BlockPos p = center.offset(x - 3, 0, z - 3);
            h.getLevel().setBlock(p, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
            h.getLevel().setBlock(p.above(), RitualContent.CHALK_SURFACE.get().defaultBlockState(), 3);
        }
        Vec3 p = Vec3.atCenterOf(center.above());
        for (var item : new net.minecraft.world.item.Item[]{net.minecraft.world.item.Items.COBWEB,
                net.minecraft.world.item.Items.GOLD_INGOT, net.minecraft.world.item.Items.CRYING_OBSIDIAN})
            h.getLevel().addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(h.getLevel(), p.x, p.y, p.z, new ItemStack(item)));
    }

    static net.neoforged.neoforge.common.util.FakePlayer researchedOwner(
            GameTestHelper h, dev.senluo.witchcraft.time.LivingEntity core) {
        var owner = SteamRitualGameTests.researchedPlayer(h, "sustained-owner", "TIMEWOOD_OWNERSHIP");
        owner.setPos(Vec3.atCenterOf(core.getBlockPos()).add(0, 0, -2));
        return owner;
    }

    private static int offeringCount(GameTestHelper h, dev.senluo.witchcraft.time.LivingEntity core, net.minecraft.world.item.Item item) {
        return h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(core.getBlockPos()).inflate(6), entity -> entity.isAlive() && entity.getItem().is(item))
                .stream().mapToInt(entity -> entity.getItem().getCount()).sum();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void interruptedSustainedPreparationKeepsSampleAndUnspentOfferings(GameTestHelper h) {
        var core = TreeGameTests.plant(h, true); core.tissue.giant = true;
        var owner = researchedOwner(h, core);
        core.tissue.bond = TreeBond.of(owner.getUUID(), "owner");
        TemporalWorld.get(h.getLevel()).invalidate();
        var subject = h.spawn(EntityType.COW, new BlockPos(3,3,5));
        var sample = TaglockSample.from(subject);
        var stack = new ItemStack(RitualContent.TAGLOCK.get(), 3);
        stack.set(RitualContent.TAGLOCK_SAMPLE.get(), sample);
        owner.setItemInHand(InteractionHand.MAIN_HAND, stack);
        prepareSustainedOfferings(h, core);
        try {
            SustainedRitual.start(owner, InteractionHand.MAIN_HAND, core, sample);
            var tick = new net.neoforged.neoforge.event.tick.ServerTickEvent.Post(() -> true, h.getLevel().getServer());
            for (int i = 0; i < 20; i++) SustainedRitual.tick(tick);
            h.assertTrue(OwnershipRitual.occupied(subject), "Fixture must reserve the subject");
            SustainedRitual.coreUnavailable(core);
            int samples = 0;
            for (int i = 0; i < owner.getInventory().getContainerSize(); i++) {
                var item = owner.getInventory().getItem(i);
                if (sample.equals(item.get(RitualContent.TAGLOCK_SAMPLE.get()))) samples += item.getCount();
            }
            h.assertTrue(samples == 3 && !OwnershipRitual.occupied(subject), "Abort releases reservation and preserves all stacked samples");
            h.assertTrue(offeringCount(h, core, net.minecraft.world.item.Items.COBWEB) == 0
                    && offeringCount(h, core, net.minecraft.world.item.Items.GOLD_INGOT) == 1
                    && offeringCount(h, core, net.minecraft.world.item.Items.CRYING_OBSIDIAN) == 1,
                    "Only the already absorbed offering is spent");
        } finally { SustainedRitual.coreUnavailable(core); }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void unresearchedSustainedStartKeepsSampleAndOfferings(GameTestHelper h) {
        var core = TreeGameTests.plant(h, true); core.tissue.giant = true;
        var owner = DevelopmentGameTests.player(h, core);
        core.tissue.bond = TreeBond.of(owner.getUUID(), "owner");
        TemporalWorld.get(h.getLevel()).invalidate();
        var subject = h.spawn(EntityType.COW, new BlockPos(3, 3, 5));
        subject.setNoAi(true);
        var sample = TaglockSample.from(subject);
        var stack = new ItemStack(RitualContent.TAGLOCK.get(), 3);
        stack.set(RitualContent.TAGLOCK_SAMPLE.get(), sample);
        owner.setItemInHand(InteractionHand.MAIN_HAND, stack);
        prepareSustainedOfferings(h, core);
        var bounds = new net.minecraft.world.phys.AABB(core.getBlockPos()).inflate(6);
        var offerings = h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, bounds);
        long before = core.tissue.stored();
        try {
            SustainedRitual.start(owner, InteractionHand.MAIN_HAND, core, sample);
            var tick = new net.neoforged.neoforge.event.tick.ServerTickEvent.Post(() -> true, h.getLevel().getServer());
            for (int i = 0; i < 60; i++) SustainedRitual.tick(tick);
            h.assertTrue(!OwnershipRitual.occupied(subject) && !OwnershipRitual.frozen(subject)
                    && !SustainedRitualStore.get(h.getLevel()).contains(subject.getUUID()),
                    "Missing completed research cannot reserve or activate the real ritual");
            h.assertTrue(owner.getMainHandItem() == stack && stack.getCount() == 3
                    && sample.equals(stack.get(RitualContent.TAGLOCK_SAMPLE.get())) && core.tissue.stored() == before,
                    "Refusal neither splits the sample stack nor pays or credits time");
            h.assertTrue(offerings.size() == 3 && offerings.stream().allMatch(item -> item.isAlive()
                    && item.getItem().getCount() == 1 && !OwnershipOfferings.held(item)),
                    "Original offering entities stay present and unreserved");
        } finally { SustainedRitual.coreUnavailable(core); }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void migratedImmunityUsesNativeTickAndHonorsCancellation(GameTestHelper h) {
        var core = TreeGameTests.plant(h, true);
        core.tissue.giant = true;
        core.tissue.bond = TreeBond.of(UUID.randomUUID(), "other-owner");
        TemporalWorld.get(h.getLevel()).invalidate();
        var player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(),
                new GameProfile(UUID.randomUUID(), "immunity-event"), ClientInformation.createDefault());
        // An in-memory packet sink, not a network connection or server listener.
        player.connection = new ServerGamePacketListenerImpl(h.getLevel().getServer(), new Connection(PacketFlow.SERVERBOUND),
                player, CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override public void send(Packet<?> packet) {}
        };
        player.setPos(Vec3.atCenterOf(core.getBlockPos()).add(2,0,0));
        player.invulnerableTime = 20;
        long before = core.tissue.stored();
        h.getLevel().tickNonPassenger(player);
        h.assertTrue(player.invulnerableTime == 18 && core.tissue.stored() > before, "Native ServerPlayer.tick and event pair shorten and credit exactly one extra tick");
        long stored = core.tissue.stored();
        Consumer<EntityTickEvent.Pre> cancel = event -> { if (event.getEntity() == player) event.setCanceled(true); };
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, cancel);
        try { h.getLevel().tickNonPassenger(player); }
        finally { NeoForge.EVENT_BUS.unregister(cancel); }
        h.assertTrue(player.invulnerableTime == 18 && core.tissue.stored() == stored, "Canceled native update produces no decrement or income");
        core.tissue.bond = TreeBond.of(player.getUUID(), "self");
        h.getLevel().tickNonPassenger(player);
        h.assertTrue(player.invulnerableTime == 17 && core.tissue.stored() == stored, "Owner gets only the ordinary decrement after cancellation clears");
        h.succeed();
    }
}
