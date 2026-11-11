package dev.senluo.witchcraft.ritual;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.brew.AutomaticAttack;
import dev.senluo.witchcraft.brew.BodyTime;
import dev.senluo.witchcraft.brew.BrewContent;
import dev.senluo.witchcraft.mixin.ritual.HurtSoundAccess;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Persistent active sessions; only loaded, validated core/subject pairs advance their clocks. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID)
public final class SustainedRitual {
    public enum Contact {
        ATTACK(5120, 10), TRADE(2560, 20), COLLISION(1280, 20), VEHICLE(5120, 20);
        final double price;
        final int interval;
        Contact(double price, int interval) { this.price = price; this.interval = interval; }
    }
    private record Attempt(UUID source, Contact kind) {}
    private static final Optional<RitualResearch.Requirement> RESEARCH = RitualResearch.thaumcraft("TIMEWOOD_OWNERSHIP");
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static Entity payingHealth;
    private SustainedRitual() {}

    public static boolean active(Entity entity) {
        if (entity.level().isClientSide) return false;
        Session session = find(entity);
        return session != null && session.active;
    }
    static boolean occupied(Entity entity) { return find(entity) != null; }
    static boolean hasSession(UUID id) { return SESSIONS.values().stream().anyMatch(s -> s.id.equals(id)); }
    public static boolean protectedFromDamage(Entity entity) {
        Session session = find(entity);
        return session != null && session.active && !session.escaping && payingHealth != entity;
    }
    public static boolean payingHealth(Entity entity) { return payingHealth == entity; }

    public static void start(ServerPlayer initiator, InteractionHand hand, LivingEntity tree, TaglockSample sample) {
        // Validate at the execution boundary as well as the item's dispatch path.
        if (!tree.tissue.supported() || !tree.tree() || !tree.tissue.giant || !tree.hasOwner()
                || tree.getLevel() != initiator.serverLevel() || initiator.isSpectator()
                || !initiator.mayBuild() || OwnershipRitual.frozen(initiator)
                || !initiator.level().mayInteract(initiator, tree.getBlockPos())) return;
        if (!TreeOwnership.mayManage(initiator, tree.tissue)) { TreeOwnership.denied(initiator); return; }
        if (sample == null || !sample.supported()) { tell(initiator, "sample_required"); return; }
        var held = initiator.getItemInHand(hand);
        if (!held.is(RitualContent.TAGLOCK.get()) || RitualVial.reserved(held)
                || !sample.equals(held.get(RitualContent.TAGLOCK_SAMPLE.get()))) return;
        ServerLevel level = initiator.serverLevel();
        // The operator starts this ritual; its sampled donor can be a non-player entity.
        if (RitualResearch.check(level, initiator.getUUID(), RESEARCH) != RitualResearch.Result.ALLOWED) {
            reject(initiator); return;
        }
        if (!SustainedRitualStore.get(level).supported()) { reject(initiator); return; }
        Entity target = level.getEntity(sample.subject());
        if (target == null || !target.isAlive() || target.isSpectator()
                || !sample.entityType().equals(BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()))
                || tree.getLevel() != level || !level.shouldTickBlocksAt(target.blockPosition())
                || !TemporalWorld.get(level).covers(tree, target.blockPosition())) {
            tell(initiator, "target_outside"); return;
        }
        if (OwnershipRitual.occupied(target)) { tell(initiator, "busy"); return; }
        if (!RitualVial.canReserve(initiator, hand)) { reject(initiator); return; }
        OwnershipCircle circle = OwnershipCircle.find(level, tree);
        if (circle == null) { reject(initiator); return; }
        OwnershipOfferings offerings = OwnershipOfferings.reserve(level, tree.getBlockPos(), circle,
                OwnershipOfferings.Recipe.SUSTAINED);
        if (offerings == null) { reject(initiator); return; }
        var current = initiator.getItemInHand(hand);
        // Item-spawn callbacks may alter inventory or start another ritual before reservation completes.
        if (!current.is(RitualContent.TAGLOCK.get()) || RitualVial.reserved(current)
                || !sample.equals(current.get(RitualContent.TAGLOCK_SAMPLE.get()))
                || RitualResearch.check(level, initiator.getUUID(), RESEARCH) != RitualResearch.Result.ALLOWED
                || OwnershipRitual.occupied(target) || !RitualVial.canReserve(initiator, hand)) {
            offerings.release(); return;
        }
        Session session = new Session(target, tree, initiator, sample, circle, offerings);
        SESSIONS.put(target.getUUID(), session);
        if (!RitualVial.reserve(initiator, hand, session.id)) {
            SESSIONS.remove(target.getUUID(), session); offerings.release();
        }
    }

    private static void reject(ServerPlayer player) {
        player.playNotifySound(SoundEvents.BEACON_DEACTIVATE, net.minecraft.sounds.SoundSource.BLOCKS, .25F, .8F);
    }

    private static void activate(Entity target, Session session, ServerPlayer initiator) {
        if (RitualResearch.check(session.level, session.initiator, RESEARCH) != RitualResearch.Result.ALLOWED) {
            end(session, "interrupted"); return;
        }
        target.stopRiding();
        target.ejectPassengers();
        if (target instanceof net.minecraft.world.entity.LivingEntity living) living.stopUsingItem();
        if (target instanceof ServerPlayer player) player.closeContainer();
        var vial = RitualVial.find(initiator, session.id, session.sample);
        LivingEntity tree = session.tree();
        if (SESSIONS.get(session.subject) != session) return;
        if (vial.isEmpty() || tree == null || !target.isAlive() || target.level() != session.level
                || !TemporalWorld.get(session.level).covers(tree, target.blockPosition())
                || !session.circle.valid(session.level) || !session.offerings.valid()) {
            end(session, "interrupted"); return;
        }
        // Only this reserved bottle is settled; all other stacked samples remain unchanged.
        vial.remove(RitualContent.TAGLOCK_SAMPLE.get());
        RitualVial.release(vial);
        initiator.getInventory().setChanged();
        OwnershipRitual.syncInventory(initiator);
        session.offerings.release();
        session.anchor = target.position(); session.velocity = target.getDeltaMovement();
        session.pose = RitualPose.capture(target);
        session.lastInput = session.level.getServer().getTickCount();
        session.active = true;
        session.preparingPlayer = null;
        SustainedRitualStore.get(session.level).bind(session.subject, session::save);
        session.level.playSound(null, session.core, SoundEvents.BEACON_ACTIVATE,
                net.minecraft.sounds.SoundSource.BLOCKS, .5F, .5F);
        target.setDeltaMovement(Vec3.ZERO);
        if (target instanceof ServerPlayer player) {
            player.connection.teleport(session.anchor.x, session.anchor.y, session.anchor.z, target.getYRot(), target.getXRot());
            OwnershipRitual.syncInventory(player);
        }
        send(target, session, true);
        tell(initiator, "sustained_start");
        if (target instanceof ServerPlayer player) tell(player, "sustained_subject");
    }

    /** Called only after the relevant server-side interaction entry validated the attempt. */
    public static void contact(Entity target, Entity source, Contact kind) {
        contact(target, source, kind, null);
    }

    public static void attack(Entity target, DamageSource damage) {
        contact(target, damage.getDirectEntity(), Contact.ATTACK, damage);
    }

    private static void contact(Entity target, Entity source, Contact kind, DamageSource damage) {
        if (target.level().isClientSide || source == null || source == target || !source.isAlive()
                || source.isSpectator() || source.level() != target.level() || OwnershipRitual.frozen(source)) return;
        Session session = find(target);
        if (session == null || !session.active || payingHealth == target || !session.coreReady()
                || !session.level.shouldTickBlocksAt(target.blockPosition()) || session.tree() == null) return;
        int now = session.level.getServer().getTickCount();
        if (kind == Contact.ATTACK && source instanceof Player player && AutomaticAttack.active(player)) {
            // The automatic clock already limits attempts and charges each invocation.
            // Its scoped claim deduplicates callbacks without losing accelerated hits.
            if (!AutomaticAttack.claimRitualContact(player, target)) return;
        } else {
            Attempt attempt = new Attempt(source.getUUID(), kind);
            Integer previous = session.attempts.get(attempt);
            if (previous != null && now - previous < kind.interval) return;
            session.attempts.put(attempt, now);
        }
        session.ledger.add(kind.price);
        SustainedRitualStore.get(session.level).setDirty();
        if (kind == Contact.ATTACK && protectedFromDamage(target)
                && now - session.lastAttackSound >= 5) {
            session.lastAttackSound = now;
            if (target instanceof HurtSoundAccess sound) {
                DamageSource cause = damage != null ? damage : source instanceof Player player
                        ? target.damageSources().playerAttack(player)
                        : source instanceof net.minecraft.world.entity.LivingEntity living
                            ? target.damageSources().mobAttack(living) : target.damageSources().generic();
                sound.witchcraft$playHurtSound(cause);
            } else target.playSound(SoundEvents.PLAYER_ATTACK_STRONG, 1.0F, 1.0F);
        }
    }

    public static boolean input(ServerPlayer player, UUID id, boolean escaping) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !session.active) return false;
        if (session.id.equals(id)) {
            session.escaping = escaping;
            session.lastInput = player.server.getTickCount();
        }
        return true;
    }

    public static void coreUnavailable(LivingEntity tree) {
        if (!(tree.getLevel() instanceof ServerLevel level)) return;
        coreRemoved(level, tree.getBlockPos(), tree.tissue.identity);
    }

    public static void coreRemoved(ServerLevel level, BlockPos core, UUID identity) {
        for (Session session : List.copyOf(SESSIONS.values()))
            if (session.level == level && session.core.equals(core)
                    && session.identity.equals(identity)) end(session, "interrupted");
        var store = SustainedRitualStore.get(level);
        for (UUID subject : store.at(core, identity)) removeSaved(level, subject);
    }

    /** BlockEntity removal is also called during chunk unloading; it is not proof of destruction. */
    public static void coreUnloaded(LivingEntity tree) {
        for (Session session : List.copyOf(SESSIONS.values()))
            if (!session.active && session.level == tree.getLevel()
                    && session.core.equals(tree.getBlockPos())) end(session, "interrupted");
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        int now = event.getServer().getTickCount();
        for (Session session : List.copyOf(SESSIONS.values())) {
            Entity target = session.level.getEntity(session.subject);
            if (session.active && target == null) { suspend(session); continue; }
            if (session.active && (!target.isAlive() || target.isSpectator()
                    || !session.sample.entityType().equals(BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()))
                    || target.position().distanceToSqr(session.anchor) > .01)) {
                end(session, "interrupted"); continue;
            }
            if (session.active && !session.coreReady()) continue;
            LivingEntity tree = session.tree();
            if (target == null || !target.isAlive() || target.isSpectator() || tree == null
                    || !session.sample.entityType().equals(BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()))
                    || session.active && target.position().distanceToSqr(session.anchor) > 0.01
                    || !TemporalWorld.get(session.level).covers(tree, target.blockPosition())) {
                end(session, "interrupted"); continue;
            }
            if (!session.level.shouldTickBlocksAt(target.blockPosition())) {
                if (!session.active) end(session, "interrupted");
                continue;
            }
            if (!session.active) {
                ServerPlayer initiator = session.preparingPlayer;
                if (initiator == null || initiator.isRemoved() || !initiator.isAlive() || initiator.isSpectator()
                        || initiator.serverLevel() != session.level || !initiator.mayBuild()
                        || OwnershipRitual.frozen(initiator) || !TreeOwnership.mayManage(initiator, tree.tissue)
                        || !session.level.mayInteract(initiator, session.core)
                        || RitualVial.find(initiator, session.id, session.sample).isEmpty()
                        || !session.offerings.valid() || now % 20 == 0 && !session.circle.valid(session.level)) {
                    end(session, "interrupted"); continue;
                }
                if (!event.getServer().tickRateManager().runsNormally()) continue;
                int next = session.preparation + 1;
                if (next <= OwnershipOfferings.Recipe.SUSTAINED.consumables * 20 && next % 20 == 0
                        && RitualResearch.check(session.level, session.initiator, RESEARCH) != RitualResearch.Result.ALLOWED) {
                    end(session, "interrupted"); continue;
                }
                session.offerings.advance(++session.preparation);
                if (session.preparation >= OwnershipOfferings.Recipe.SUSTAINED.preparationTicks()) {
                    if (!session.circle.valid(session.level)) { end(session, "interrupted"); continue; }
                    activate(target, session, initiator);
                }
                continue;
            }
            if (!event.getServer().tickRateManager().runsNormally()) continue;
            SustainedRitualStore.get(session.level).setDirty();
            if (now - session.lastInput > 30) session.escaping = false;
            target.setDeltaMovement(Vec3.ZERO);
            session.pose.apply(target);
            if (target instanceof ServerPlayer player) player.resetLastActionTime();
            session.escapeTicks = session.escaping ? session.escapeTicks + 1 : 0;
            if (session.escaping && target instanceof net.minecraft.world.entity.LivingEntity living) {
                living.invulnerableTime = Math.max(0, living.invulnerableTime - 1);
                living.hurtTime = Math.max(0, living.hurtTime - 1);
            }
            if (session.escapeTicks >= OwnershipRitual.ESCAPE) { end(session, "interrupted"); continue; }
            // Escape attempts do not restart grace or stop economic time.
            session.elapsed = Math.min(SustainedLedger.GRACE_SECONDS * 20, session.elapsed + 1);
            if (++session.cycle == 20) {
                session.cycle = 0;
                settle(session, target, tree);
                session.attempts.entrySet().removeIf(entry -> now - entry.getValue() >= 20);
                if (!target.isAlive()) { end(session, "interrupted"); continue; }
                if (target instanceof ServerPlayer player) {
                    OwnershipRitual.syncDisplay(player);
                    show(player, session);
                }
            }
            // Grayscale only needs five updates/second while escaping, one otherwise.
            if (session.escaping != session.wasEscaping || session.cycle == 0
                    || session.escaping && session.escapeTicks % 4 == 0) send(target, session, true);
            session.wasEscaping = session.escaping;
        }
    }

    private static void settle(Session session, Entity target, LivingEntity tree) {
        var box = target.getBoundingBox();
        double volume = Math.max(0, box.getXsize() * box.getYsize() * box.getZsize());
        if (!Double.isFinite(volume)) volume = 0;
        double baseline = session.ledger.baseline(volume);
        BodyTime body = target.hasData(BrewContent.BODY_TIME) ? target.getData(BrewContent.BODY_TIME) : null;
        SustainedLedger.Payment payment = session.ledger.settle(baseline, body);
        session.incomeRemainder = Math.min(Tissue.MAX_VALUE, session.incomeRemainder + payment.plantTu() * Tissue.TU);
        long amount = (long) Math.min(Tissue.MAX_VALUE, Math.floor(session.incomeRemainder));
        session.incomeRemainder -= amount;
        session.capacityRemainder += amount * .05;
        long growth = (long) Math.floor(session.capacityRemainder);
        session.capacityRemainder -= growth;
        tree.tissue.ritualCapacity += Math.min(growth, Math.max(0, Tissue.MAX_VALUE - tree.tissue.coreCapacity()));
        var group = TemporalWorld.get(session.level).colony(tree);
        // Grow capacity first. Surplus beyond physical storage is discarded, never an IOU.
        group.creditAvailable(amount, tree);
        tree.setChanged();
        if (payment.healthLoss() <= 0) return;
        payingHealth = target;
        try {
            if (target instanceof net.minecraft.world.entity.LivingEntity living) {
                living.setHealth(Math.max(0, living.getHealth() - payment.healthLoss()));
                if (living.getHealth() <= 0) living.die(target.damageSources().genericKill());
            } else {
                // Non-living entities use their native durability/destruction implementation.
                session.damageRemainder += payment.healthLoss();
                int damage = (int) Math.floor(session.damageRemainder);
                if (damage > 0) {
                    session.damageRemainder -= damage;
                    if (!target.hurt(target.damageSources().genericKill(), damage) && target.isAlive()) target.kill();
                }
            }
        } finally { payingHealth = null; }
    }

    private static void end(Session session, String message) {
        if (!SESSIONS.remove(session.subject, session)) return;
        if (session.active) SustainedRitualStore.get(session.level).remove(session.subject);
        if (session.offerings != null) session.offerings.release();
        RitualVial.releaseAll(session.preparingPlayer, session.id);
        session.preparingPlayer = null;
        // Former trackers also keep a mirror. They must hear the end even after walking away.
        if (session.active) PacketDistributor.sendToAllPlayers(session.packet(false));
        Entity target = session.level.getEntity(session.subject);
        if (target != null) {
            if (session.active && target.isAlive() && target.position().distanceToSqr(session.anchor) <= .01)
                target.setDeltaMovement(session.velocity);
            if (target instanceof ServerPlayer player) {
                if (session.active) { OwnershipRitual.syncDisplay(player); OwnershipRitual.syncInventory(player); }
                tell(player, message);
            }
        }
    }
    private static void send(Entity target, Session session, boolean active) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(target, session.packet(active));
    }
    private static void show(ServerPlayer player, Session session) {
        String key = session.escaping ? "escape_remaining" : session.elapsed < SustainedLedger.GRACE_SECONDS * 20
                ? "sustained_grace" : "sustained_active";
        int ticks = session.escaping ? OwnershipRitual.ESCAPE - session.escapeTicks
                : SustainedLedger.GRACE_SECONDS * 20 - session.elapsed;
        player.displayClientMessage(TemporalText.color(Component.translatable(
                "text.kaleidoscope_witchcraft.ownership." + key, TemporalText.duration(ticks)), TemporalText.BODY), true);
    }
    private static void tell(ServerPlayer player, String key) {
        player.displayClientMessage(TemporalText.color(Component.translatable(
                "text.kaleidoscope_witchcraft.ownership." + key), TemporalText.PENDING), true);
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        Session own = SESSIONS.get(event.getEntity().getUUID());
        if (own != null && own.active) suspend(own);
        for (Session session : List.copyOf(SESSIONS.values()))
            if (!session.active && (session.subject.equals(event.getEntity().getUUID())
                    || session.initiator.equals(event.getEntity().getUUID()))) end(session, "interrupted");
    }
    @SubscribeEvent public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) { leave(event.getEntity()); }
    private static void leave(Entity target) {
        for (Session session : List.copyOf(SESSIONS.values()))
            if (session.subject.equals(target.getUUID()) || !session.active && session.initiator.equals(target.getUUID()))
                end(session, "interrupted");
        if (target.getServer() != null)
            for (ServerLevel level : target.getServer().getAllLevels()) removeSaved(level, target.getUUID());
    }
    @SubscribeEvent public static void tracking(PlayerEvent.StartTracking event) {
        Session session = find(event.getTarget());
        if (session != null && session.active && event.getEntity() instanceof ServerPlayer player)
            PacketDistributor.sendToPlayer(player, session.packet(true));
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        for (Session session : List.copyOf(SESSIONS.values())) {
            if (session.active) suspend(session); else end(session, "interrupted");
        }
        payingHealth = null;
    }

    @SubscribeEvent public static void joined(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide) find(event.getEntity());
    }

    @SubscribeEvent public static void departed(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity target = event.getEntity();
        Session session = SESSIONS.get(target.getUUID());
        Entity.RemovalReason reason = target.getRemovalReason();
        boolean destroyed = reason != null && (reason.shouldDestroy() || reason == Entity.RemovalReason.CHANGED_DIMENSION);
        if (session != null && session.level == level) {
            if (destroyed || !session.active) end(session, "interrupted"); else suspend(session);
        }
        if (destroyed) removeSaved(level, target.getUUID());
    }

    @SubscribeEvent public static void unloaded(LevelEvent.Unload event) {
        for (Session session : List.copyOf(SESSIONS.values()))
            if (session.level == event.getLevel()) {
                if (session.active) suspend(session); else end(session, "interrupted");
            }
    }

    private static void suspend(Session session) {
        if (!SESSIONS.remove(session.subject, session)) return;
        // A key held before unloading is not an input after reconnecting.
        session.escaping = session.wasEscaping = false; session.escapeTicks = 0;
        SustainedRitualStore.get(session.level).suspend(session.subject);
    }

    private static void removeSaved(ServerLevel level, UUID subject) {
        var store = SustainedRitualStore.get(level);
        CompoundTag saved = store.snapshot(subject);
        if (saved == null) return;
        store.remove(subject);
        // A former tracker can still hold a gray/frozen mirror while the subject is unloaded.
        PacketDistributor.sendToAllPlayers(new Session(level, saved).packet(false));
    }

    private static Session find(Entity target) {
        if (!(target.level() instanceof ServerLevel level) || target.isRemoved()) return null;
        Session current = SESSIONS.get(target.getUUID());
        if (current != null) return current.level == level ? current : null;
        var store = SustainedRitualStore.get(level);
        CompoundTag saved = store.snapshot(target.getUUID());
        if (saved == null) return null;
        Session restored = new Session(level, saved);
        if (!target.isAlive() || target.isSpectator()
                || !restored.sample.entityType().equals(BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()))
                || target.position().distanceToSqr(restored.anchor) > .01
                || restored.coreReady() && (restored.tree() == null
                    || !TemporalWorld.get(level).covers(restored.tree(), target.blockPosition()))) {
            removeSaved(level, target.getUUID());
            return null;
        }
        SESSIONS.put(restored.subject, restored);
        store.bind(restored.subject, restored::save);
        target.setDeltaMovement(Vec3.ZERO);
        restored.pose.apply(target);
        return restored;
    }

    static boolean validSnapshot(CompoundTag tag) {
        try { new Session(null, tag); return true; }
        catch (IllegalArgumentException | IllegalStateException exception) { return false; }
    }

    private static final class Session {
        final UUID id, subject, identity, owner, initiator;
        final TaglockSample sample;
        final OwnershipCircle circle;
        final OwnershipOfferings offerings;
        // Held only during the four-second preparation; logout/dimension events abort before release.
        ServerPlayer preparingPlayer;
        final ServerLevel level;
        final BlockPos core;
        Vec3 anchor, velocity;
        RitualPose pose;
        final SustainedLedger ledger;
        final Map<Attempt, Integer> attempts = new HashMap<>();
        int elapsed, cycle, escapeTicks, lastInput, preparation;
        long lastAttackSound = -5;
        boolean active, escaping, wasEscaping;
        double incomeRemainder, capacityRemainder, damageRemainder;
        Session(Entity target, LivingEntity tree, ServerPlayer initiator, TaglockSample sample,
                OwnershipCircle circle, OwnershipOfferings offerings) {
            id = UUID.randomUUID(); ledger = new SustainedLedger();
            this.initiator = initiator.getUUID(); this.sample = sample; this.circle = circle; this.offerings = offerings;
            preparingPlayer = initiator;
            subject = target.getUUID(); level = (ServerLevel) target.level();
            core = tree.getBlockPos().immutable(); identity = tree.tissue.identity; owner = tree.owner();
            anchor = target.position(); velocity = target.getDeltaMovement();
            pose = RitualPose.capture(target);
            lastInput = level.getServer().getTickCount();
        }
        Session(ServerLevel level, CompoundTag tag) {
            if (tag.getInt("Version") != 1 || !tag.contains("Core", Tag.TAG_LONG)
                    || !tag.contains("Sample", Tag.TAG_COMPOUND))
                throw new IllegalArgumentException("Invalid sustained ritual version");
            id = tag.getUUID("Id"); subject = tag.getUUID("Subject"); identity = tag.getUUID("Identity");
            owner = tag.getUUID("Owner"); initiator = tag.getUUID("Initiator");
            core = BlockPos.of(tag.getLong("Core")); this.level = level;
            sample = TaglockSample.CODEC.parse(NbtOps.INSTANCE, tag.get("Sample")).getOrThrow();
            if (!sample.supported() || !sample.subject().equals(subject))
                throw new IllegalArgumentException("Invalid ritual subject");
            anchor = vector(tag.getCompound("Anchor")); velocity = vector(tag.getCompound("Velocity"));
            CompoundTag angles = tag.getCompound("Pose");
            for (String key : List.of("Yaw", "Pitch", "Head", "Body"))
                if (!angles.contains(key, Tag.TAG_FLOAT)) throw new IllegalArgumentException("Missing ritual pose");
            pose = new RitualPose(angles.getFloat("Yaw"), angles.getFloat("Pitch"),
                    angles.getFloat("Head"), angles.getFloat("Body"));
            elapsed = boundedInt(tag, "Elapsed", SustainedLedger.GRACE_SECONDS * 20);
            cycle = boundedInt(tag, "Cycle", 19);
            incomeRemainder = fraction(tag, "IncomeRemainder");
            capacityRemainder = fraction(tag, "CapacityRemainder");
            damageRemainder = fraction(tag, "DamageRemainder");
            ledger = SustainedLedger.load(tag.getCompound("Ledger"));
            if (elapsed != Math.min(SustainedLedger.GRACE_SECONDS * 20, ledger.save().getInt("Seconds") * 20 + cycle))
                throw new IllegalArgumentException("Inconsistent ritual clocks");
            circle = null; offerings = null; active = true;
            lastInput = level == null ? 0 : level.getServer().getTickCount();
        }
        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("Version", 1); tag.putUUID("Id", id); tag.putUUID("Subject", subject);
            tag.putUUID("Identity", identity); tag.putUUID("Owner", owner); tag.putUUID("Initiator", initiator);
            tag.putLong("Core", core.asLong());
            tag.put("Sample", TaglockSample.CODEC.encodeStart(NbtOps.INSTANCE, sample).getOrThrow());
            tag.put("Anchor", vector(anchor)); tag.put("Velocity", vector(velocity));
            CompoundTag angles = new CompoundTag();
            angles.putFloat("Yaw", pose.yaw()); angles.putFloat("Pitch", pose.pitch());
            angles.putFloat("Head", pose.head()); angles.putFloat("Body", pose.body()); tag.put("Pose", angles);
            tag.putInt("Elapsed", elapsed); tag.putInt("Cycle", cycle); tag.put("Ledger", ledger.save());
            tag.putDouble("IncomeRemainder", incomeRemainder); tag.putDouble("CapacityRemainder", capacityRemainder);
            tag.putDouble("DamageRemainder", damageRemainder);
            return tag;
        }
        boolean coreReady() {
            return level.hasChunkAt(core) && level.shouldTickBlocksAt(core);
        }
        LivingEntity tree() {
            if (!coreReady()) return null;
            return level.getBlockEntity(core) instanceof LivingEntity tree && tree.tissue.supported() && tree.tree() && tree.tissue.giant
                    && tree.hasOwner() && owner.equals(tree.owner()) && identity.equals(tree.tissue.identity) ? tree : null;
        }
        RitualPackets.State packet(boolean active) {
            return new RitualPackets.State(subject, id, level.dimension().location(), anchor, elapsed, escapeTicks, active, pose);
        }
    }

    private static int boundedInt(CompoundTag tag, String key, int max) {
        int value = tag.getInt(key);
        if (!tag.contains(key, Tag.TAG_INT) || value < 0 || value > max)
            throw new IllegalArgumentException("Invalid ritual counter");
        return value;
    }
    private static double fraction(CompoundTag tag, String key) {
        double value = tag.getDouble(key);
        if (!tag.contains(key, Tag.TAG_DOUBLE) || !Double.isFinite(value) || value < 0 || value >= 1)
            throw new IllegalArgumentException("Invalid ritual remainder");
        return value;
    }
    private static Vec3 vector(CompoundTag tag) {
        for (String axis : List.of("X", "Y", "Z"))
            if (!tag.contains(axis, Tag.TAG_DOUBLE) || !Double.isFinite(tag.getDouble(axis))
                    || Math.abs(tag.getDouble(axis)) > 60_000_000)
                throw new IllegalArgumentException("Invalid ritual position");
        return new Vec3(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"));
    }
    private static CompoundTag vector(Vec3 vector) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("X", vector.x); tag.putDouble("Y", vector.y); tag.putDouble("Z", vector.z);
        return tag;
    }
}
