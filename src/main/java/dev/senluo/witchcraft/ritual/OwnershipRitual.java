package dev.senluo.witchcraft.ritual;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.brew.BodyTime;
import dev.senluo.witchcraft.brew.BrewContent;
import dev.senluo.witchcraft.brew.ExtraTime;
import dev.senluo.witchcraft.time.LivingEntity;
import dev.senluo.witchcraft.time.TemporalText;
import dev.senluo.witchcraft.time.TemporalWorld;
import dev.senluo.witchcraft.time.Tissue;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-owned sessions. No frozen flag is persisted on players, so abort/reload cannot strand them. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID)
public final class OwnershipRitual {
    public static final int DURATION = 60 * 20, ESCAPE = 3 * 20;
    private static final int PREPARATION = OwnershipOfferings.Recipe.OWNERSHIP.preparationTicks();
    private static final Optional<RitualResearch.Requirement> RESEARCH = RitualResearch.thaumcraft("TIMEWOOD_OWNERSHIP");
    private static final double STEP_QUOTE = 1; // One base tick; the ledger applies the grade discount exactly once.
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static boolean syncingDisplay;
    private OwnershipRitual() {}

    public static boolean frozen(Entity entity) {
        if (entity.level().isClientSide) return RitualView.get(entity) != null;
        Session session = SESSIONS.get(entity.getUUID());
        return session != null && session.active || SustainedRitual.active(entity);
    }

    /** Preparation reserves the subject without freezing or protecting them. */
    public static boolean occupied(Entity entity) {
        return entity.level().isClientSide ? frozen(entity)
                : SESSIONS.containsKey(entity.getUUID()) || SustainedRitual.occupied(entity);
    }
    public static boolean protectedFromDamage(Entity entity) {
        if (entity.level().isClientSide) {
            var view = RitualView.get(entity);
            return view != null && view.escaping() == 0;
        }
        Session session = SESSIONS.get(entity.getUUID());
        return session != null && session.active && !session.escaping || SustainedRitual.protectedFromDamage(entity);
    }
    public static boolean syncingDisplay() { return syncingDisplay; }
    static boolean hasSession(UUID id) {
        return SESSIONS.values().stream().anyMatch(s -> s.id.equals(id)) || SustainedRitual.hasSession(id);
    }


    public static void start(ServerPlayer initiator, InteractionHand hand, LivingEntity tree) {
        if (!tree.tissue.supported()) { dev.senluo.witchcraft.time.LivingBlock.tell(initiator, "data_unavailable"); return; }
        if (frozen(initiator) || !initiator.mayBuild() || !initiator.level().mayInteract(initiator, tree.getBlockPos())) return;
        ItemStack held = initiator.getItemInHand(hand);
        if (!held.is(RitualContent.TAGLOCK.get()) || tree.getLevel() != initiator.serverLevel()) return;
        RitualVial.cleanup(held);
        if (RitualVial.reserved(held)) { tell(initiator, "busy"); return; }
        TaglockSample sample = held.get(RitualContent.TAGLOCK_SAMPLE.get());
        if (!tree.tree()) { rejectOffering(initiator, tree); return; }
        if (tree.hasOwner()) {
            if (!tree.tissue.giant) { tell(initiator, "giant_only"); return; }
            SustainedRitual.start(initiator, hand, tree, sample);
            return;
        }
        if (sample == null || !sample.supported() || !sample.isPlayer()) { tell(initiator, "player_sample"); return; }
        // The prospective owner supplies the research, even when another player holds the sample.
        if (RitualResearch.check(initiator.serverLevel(), sample.subject(), RESEARCH) != RitualResearch.Result.ALLOWED) {
            rejectOffering(initiator, tree); return;
        }
        if (tree.tissue.bond != null) { tell(initiator, "already_bound"); return; }
        ServerPlayer target = initiator.server.getPlayerList().getPlayer(sample.subject());
        if (target == null || !target.isAlive() || target.isSpectator() || target.serverLevel() != tree.getLevel()
                || !TemporalWorld.get(target.serverLevel()).covers(tree, target.blockPosition())) {
            tell(initiator, "target_outside"); return;
        }
        if (occupied(target) || SESSIONS.values().stream().anyMatch(s -> s.level == tree.getLevel() && s.core.equals(tree.getBlockPos()))) {
            tell(initiator, "busy"); return;
        }
        TreeBond incoming = TreeBond.of(target.getUUID(), target.getGameProfile().getName());
        if (!tree.tissue.acceptsBond(incoming)) { tell(initiator, "foreign_roots"); return; }
        if (!RitualVial.canReserve(initiator, hand)) { rejectOffering(initiator, tree); return; }
        ServerLevel level = target.serverLevel();
        OwnershipCircle circle = OwnershipCircle.find(level, tree);
        if (circle == null) { rejectOffering(initiator, tree); return; }
        OwnershipOfferings offerings = OwnershipOfferings.reserve(level, tree.getBlockPos(), circle);
        if (offerings == null) { rejectOffering(initiator, tree); return; }
        // Third-party item-spawn callbacks must not swap the sample while offerings are reserved.
        if (!initiator.getItemInHand(hand).is(RitualContent.TAGLOCK.get())
                || !sample.equals(initiator.getItemInHand(hand).get(RitualContent.TAGLOCK_SAMPLE.get()))
                || RitualResearch.check(level, sample.subject(), RESEARCH) != RitualResearch.Result.ALLOWED
                || !RitualVial.canReserve(initiator, hand)) {
            offerings.release();
            return;
        }
        Session session = new Session(target, tree, initiator, sample, circle, offerings);
        SESSIONS.put(target.getUUID(), session);
        if (!RitualVial.reserve(initiator, hand, session.id)) {
            SESSIONS.remove(target.getUUID(), session);
            offerings.release();
            return;
        }
        // Every game mode observes the offering sequence. Effects begin only after preparation.
    }

    private static void activate(ServerPlayer target, Session session) {
        if (RitualResearch.check(session.level, session.player, RESEARCH) != RitualResearch.Result.ALLOWED) {
            end(target.server, session, "interrupted"); return;
        }
        target.closeContainer();
        target.setShiftKeyDown(false);
        target.setSprinting(false);
        target.stopUsingItem();
        target.stopRiding();
        // The subject could move or turn during preparation; freeze their current pose.
        session.anchor = target.position();
        session.velocity = target.getDeltaMovement();
        session.pose = RitualPose.capture(target);
        session.lastInput = target.server.getTickCount();
        session.active = true;
        BodyTime time = ledger(target);
        boolean enough = time != null && time.canSpend(STEP_QUOTE * DURATION);
        target.setDeltaMovement(Vec3.ZERO);
        target.connection.teleport(session.anchor.x, session.anchor.y, session.anchor.z, target.getYRot(), target.getXRot());
        syncInventory(target);
        send(target, session, true);
        tell(target, enough ? "start_enough" : "start_short");
        ServerPlayer initiator = target.server.getPlayerList().getPlayer(session.initiator);
        if (initiator != null && target != initiator) tell(initiator, enough ? "start_enough" : "start_short");
    }

    public static void input(ServerPlayer player, UUID id, boolean escaping) {
        if (SustainedRitual.input(player, id, escaping)) return;
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !session.active || !session.id.equals(id)) return;
        session.escaping = escaping;
        session.lastInput = player.server.getTickCount();
    }

    public static void coreUnavailable(LivingEntity tree) {
        SustainedRitual.coreUnloaded(tree);
        if (!(tree.getLevel() instanceof ServerLevel level)) return;
        for (Session session : List.copyOf(SESSIONS.values()))
            if (session.level == level && session.core.equals(tree.getBlockPos()) && session.identity.equals(tree.tissue.identity))
                end(level.getServer(), session, "interrupted");
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        for (Session session : List.copyOf(SESSIONS.values())) {
            ServerPlayer player = server.getPlayerList().getPlayer(session.player);
            LivingEntity tree = session.tree();
            if (player == null || !player.isAlive() || player.isSpectator() || player.serverLevel() != session.level || tree == null
                    || tree.tissue.bond != null || session.active && player.position().distanceToSqr(session.anchor) > 0.01
                    || !TemporalWorld.get(session.level).covers(tree, player.blockPosition())) {
                end(server, session, "interrupted"); continue;
            }
            ServerPlayer initiator = server.getPlayerList().getPlayer(session.initiator);
            if (initiator == null || !initiator.isAlive() || initiator.serverLevel() != session.level
                    || RitualVial.find(initiator, session.id, session.sample).isEmpty() || !session.offerings.valid()
                    || server.getTickCount() % 20 == 0 && !session.circle.valid(session.level)) {
                end(server, session, "interrupted"); continue;
            }
            if (!server.tickRateManager().runsNormally()) continue;
            if (!session.active) {
                // Knowledge can live on disk: check when an offering is spent, not on animation ticks.
                int next = session.preparation + 1;
                if (next <= OwnershipOfferings.Recipe.OWNERSHIP.consumables * 20 && next % 20 == 0
                        && RitualResearch.check(session.level, session.player, RESEARCH) != RitualResearch.Result.ALLOWED) {
                    end(server, session, "interrupted"); continue;
                }
                session.offerings.advance(++session.preparation);
                if (session.preparation < PREPARATION) continue;
                // Recheck at the actual effect boundary, even between periodic circle checks.
                if (!session.circle.valid(session.level)) { end(server, session, "interrupted"); continue; }
                session.level.playSound(null, session.core, net.minecraft.sounds.SoundEvents.BEACON_ACTIVATE,
                        net.minecraft.sounds.SoundSource.BLOCKS, .5F, .65F);
                if (player.isCreative()) {
                    end(server, session, complete(server, session, tree, player) ? "complete" : "interrupted");
                } else activate(player, session);
                continue;
            }
            if (player.isCreative()) {
                end(server, session, complete(server, session, tree, player) ? "complete" : "interrupted");
                continue;
            }
            if (server.getTickCount() - session.lastInput > 30) session.escaping = false;
            player.setDeltaMovement(Vec3.ZERO);
            session.pose.apply(player);
            player.resetLastActionTime();
            if (session.escaping) {
                session.escapeTicks++;
                // Damage vulnerability must not accidentally retain a ten-second vanilla hurt cooldown.
                if (player.invulnerableTime > 0) player.invulnerableTime--;
                if (player.hurtTime > 0) player.hurtTime--;
            } else session.escapeTicks = 0;
            // Escaping pauses progress, not absorption; its charged time is additional to the sixty seconds.
            absorb(player, tree, session);
            if (SESSIONS.get(session.player) != session) continue;
            if (session.escapeTicks >= ESCAPE) { end(server, session, "interrupted"); continue; }
            if (!session.escaping && ++session.progress >= DURATION) {
                boolean bound = complete(server, session, tree, player);
                end(server, session, bound ? "complete" : "interrupted");
                continue;
            }
            if (session.escaping || session.wasEscaping || session.progress % 20 == 0) send(player, session, true);
            // Leave the initial affordability notice visible briefly; mode changes update immediately.
            if ((session.chargedTicks >= 40 && session.chargedTicks % 20 == 0)
                    || session.escaping != session.wasEscaping) showRemaining(player, session);
            session.wasEscaping = session.escaping;
            if (session.chargedTicks % 20 == 0) syncDisplay(player);
        }
    }

    private static void rejectOffering(ServerPlayer player, LivingEntity tree) {
        // No new game text: a quiet failure cue leaves every item and sample untouched.
        player.playNotifySound(net.minecraft.sounds.SoundEvents.BEACON_DEACTIVATE,
                net.minecraft.sounds.SoundSource.BLOCKS, .25F, .8F);
    }

    private static boolean complete(MinecraftServer server, Session session, LivingEntity tree, ServerPlayer target) {
        ServerPlayer initiator = server.getPlayerList().getPlayer(session.initiator);
        ItemStack vial = RitualVial.find(initiator, session.id, session.sample);
        if (vial.isEmpty() || !session.circle.valid(session.level) || !session.offerings.valid()
                || RitualResearch.check(session.level, session.player, RESEARCH) != RitualResearch.Result.ALLOWED
                || !TreeOwnership.bind(tree, target)) return false;
        // Validation, binding and sample settlement run synchronously on the server thread.
        vial.remove(RitualContent.TAGLOCK_SAMPLE.get());
        RitualVial.release(vial);
        initiator.getInventory().setChanged();
        syncInventory(initiator);
        if (initiator != target) tell(initiator, "complete");
        return true;
    }

    private static void absorb(ServerPlayer player, LivingEntity tree, Session session) {
        BodyTime time = ledger(player);
        double income;
        if (time == null || !time.active()) income = 1.0 / 20;
        else {
            double price = time.price(STEP_QUOTE);
            double paid = time.drain(STEP_QUOTE);
            // One actual base-use second becomes one TU. An exempt tier pays and contributes zero.
            income = paid / 20;
            if (price > 0 && paid < price) income += (1 - paid / price) / 20;
        }
        session.incomeRemainder += income * Tissue.TU;
        long amount = (long) Math.floor(session.incomeRemainder);
        session.incomeRemainder -= amount;
        tree.tissue.receiveRitual(amount);
        tree.setChanged();
        session.chargedTicks++;
    }

    private static BodyTime ledger(ServerPlayer player) {
        return player.hasData(BrewContent.BODY_TIME) ? player.getData(BrewContent.BODY_TIME) : null;
    }
    public static void syncInventory(ServerPlayer player) {
        player.containerMenu.sendAllDataToRemote();
        player.connection.send(new ClientboundSetCarriedItemPacket(player.getInventory().selected));
    }
    static void syncDisplay(ServerPlayer player) {
        BodyTime time = ledger(player);
        if (time == null) return;
        syncingDisplay = true;
        try { ExtraTime.sync(player, time); }
        finally { syncingDisplay = false; }
    }
    private static void end(MinecraftServer server, Session session, String message) {
        if (!SESSIONS.remove(session.player, session)) return;
        session.offerings.release();
        RitualVial.releaseAll(server.getPlayerList().getPlayer(session.initiator), session.id);
        if (session.active) PacketDistributor.sendToAllPlayers(session.packet(false));
        ServerPlayer player = server.getPlayerList().getPlayer(session.player);
        if (player != null) {
            if (session.active && player.isAlive() && player.serverLevel() == session.level && player.position().distanceToSqr(session.anchor) <= 0.01)
                player.setDeltaMovement(session.velocity);
            if (session.active) { player.setShiftKeyDown(false); player.setSprinting(false); syncDisplay(player); syncInventory(player); }
            player.playNotifySound(message.equals("complete") ? net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME
                    : net.minecraft.sounds.SoundEvents.BEACON_DEACTIVATE,
                    net.minecraft.sounds.SoundSource.PLAYERS, .3F, .8F);
            tell(player, message);
        }
    }
    private static void send(ServerPlayer player, Session session, boolean active) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, session.packet(active));
    }
    private static void tell(ServerPlayer player, String key) {
        player.displayClientMessage(TemporalText.color(Component.translatable(
                "text.kaleidoscope_witchcraft.ownership." + key),
                key.equals("complete") || key.equals("start_enough") ? TemporalText.READY : TemporalText.PENDING), true);
    }
    private static void showRemaining(ServerPlayer player, Session session) {
        int ticks = session.escaping ? ESCAPE - session.escapeTicks : DURATION - session.progress;
        player.displayClientMessage(TemporalText.color(Component.translatable(
                "text.kaleidoscope_witchcraft.ownership." + (session.escaping ? "escape_remaining" : "remaining"),
                TemporalText.duration(ticks)), session.escaping ? TemporalText.PENDING : TemporalText.BODY), true);
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            for (Session session : List.copyOf(SESSIONS.values()))
                if (session.player.equals(player.getUUID()) || session.initiator.equals(player.getUUID()))
                    end(player.server, session, "interrupted");
        }
    }
    @SubscribeEvent public static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            for (Session session : List.copyOf(SESSIONS.values()))
                if (session.player.equals(player.getUUID()) || session.initiator.equals(player.getUUID()))
                    end(player.server, session, "interrupted");
        }
    }
    @SubscribeEvent public static void tracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer viewer) {
            Session session = SESSIONS.get(event.getTarget().getUUID());
            if (session != null && session.active) PacketDistributor.sendToPlayer(viewer, session.packet(true));
        }
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        for (Session session : List.copyOf(SESSIONS.values())) end(event.getServer(), session, "interrupted");
    }

    private static final class Session {
        final UUID id = UUID.randomUUID(), player, identity, initiator;
        final TaglockSample sample;
        final OwnershipCircle circle;
        final OwnershipOfferings offerings;
        final ServerLevel level;
        final BlockPos core;
        Vec3 anchor, velocity;
        RitualPose pose;
        int progress, escapeTicks, chargedTicks, lastInput, preparation;
        boolean active, escaping, wasEscaping;
        double incomeRemainder;
        Session(ServerPlayer player, LivingEntity tree, ServerPlayer initiator, TaglockSample sample,
                OwnershipCircle circle, OwnershipOfferings offerings) {
            this.initiator = initiator.getUUID(); this.sample = sample; this.circle = circle; this.offerings = offerings;
            this.player = player.getUUID(); level = player.serverLevel(); core = tree.getBlockPos().immutable();
            identity = tree.tissue.identity; anchor = player.position(); velocity = player.getDeltaMovement();
            pose = RitualPose.capture(player);
            lastInput = player.server.getTickCount();
        }
        LivingEntity tree() {
            if (!level.hasChunkAt(core) || !level.shouldTickBlocksAt(core)) return null;
            return level.getBlockEntity(core) instanceof LivingEntity tree && TreeOwnership.canBind(tree)
                    && tree.tissue.identity.equals(identity) ? tree : null;
        }
        RitualPackets.State packet(boolean active) {
            return new RitualPackets.State(player, id, level.dimension().location(), anchor, progress, escapeTicks, active, pose);
        }
    }
}
