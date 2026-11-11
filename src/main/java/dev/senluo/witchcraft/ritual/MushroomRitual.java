package dev.senluo.witchcraft.ritual;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.vishroom.VishroomContent;
import dev.senluo.witchcraft.vishroom.VishroomEntity;
import dev.senluo.witchcraft.world.BlockPlacement;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Cultivation is a short offering transaction, not a permanent structural-ritual slot. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID)
public final class MushroomRitual {
    private static final Optional<RitualResearch.Requirement> RESEARCH = RitualResearch.thaumcraft("WITCHERY_MEDIUM");
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Set<UUID> STARTING = new HashSet<>();
    private MushroomRitual() {}
    static boolean hasSession(UUID id) { return SESSIONS.containsKey(id); }

    static void start(ServerPlayer player, InteractionHand hand, BlockPos pos) {
        if (!STARTING.add(player.getUUID())) return;
        try {
            ServerLevel level = player.serverLevel();
            BlockState seed = level.getBlockState(pos);
            if (!seed.is(VishroomContent.SEED) || !level.shouldTickBlocksAt(pos) || !player.mayBuild()
                    || player.isSpectator() || OwnershipRitual.frozen(player) || !level.mayInteract(player, pos)
                    || SESSIONS.values().stream().anyMatch(s -> s.level == level && s.pos.equals(pos))) return;
            var held = player.getItemInHand(hand); RitualVial.cleanup(held);
            TaglockSample sample = held.get(RitualContent.TAGLOCK_SAMPLE.get());
            if (sample == null || !sample.supported() || !sample.isPlayer() || RitualVial.reserved(held)
                    || !RitualVial.canReserve(player, hand)) return;
            // Cultivation grants the sample owner a plant; research follows that same identity.
            if (RitualResearch.check(level, sample.subject(), RESEARCH) != RitualResearch.Result.ALLOWED) return;
            RitualPlane.invalidate(level, pos);
            var plane = RitualPlane.find(level, pos);
            if (plane.isEmpty() || !VishroomEntity.clearForCreation(level, pos)) return;
            var offerings = OwnershipOfferings.reserve(level, pos, new OwnershipCircle(plane.get().anchor()), OwnershipOfferings.Recipe.VISHROOM);
            if (offerings == null) return;
            var current = player.getItemInHand(hand);
            if (level.getBlockState(pos) != seed || !current.is(RitualContent.TAGLOCK.get())
                    || RitualResearch.check(level, sample.subject(), RESEARCH) != RitualResearch.Result.ALLOWED
                    || !sample.equals(current.get(RitualContent.TAGLOCK_SAMPLE.get())) || RitualVial.reserved(current)) {
                offerings.release(); return;
            }
            var session = new Session(level, pos.immutable(), plane.get().anchor(), seed, player.getUUID(), sample, offerings);
            SESSIONS.put(session.id, session);
            if (!RitualVial.reserve(player, hand, session.id)) { SESSIONS.remove(session.id); offerings.release(); }
        } finally { STARTING.remove(player.getUUID()); }
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        for (Session s : List.copyOf(SESSIONS.values())) {
            if (SESSIONS.get(s.id) != s) continue;
            var player = event.getServer().getPlayerList().getPlayer(s.initiator);
            var vial = RitualVial.find(player, s.id, s.sample);
            if (player == null || player.serverLevel() != s.level || vial.isEmpty()
                    || !player.mayBuild() || !s.level.hasChunkAt(s.pos) || !s.level.shouldTickBlocksAt(s.pos)
                    || s.level.getBlockState(s.pos) != s.seed || !s.level.mayInteract(player, s.pos)
                    || !s.offerings.valid()) { end(s, player); continue; }
            // ServerTickEvent.Post still fires while vanilla simulation is frozen.
            if (!event.getServer().tickRateManager().runsNormally()) continue;
            // Reuse the plane/cache and inspect geometry only once per second, plus the commit boundary.
            if (s.age % 20 == 0 && RitualPlane.at(s.level, s.plane).isEmpty()) { end(s, player); continue; }
            if (++s.age < OwnershipOfferings.Recipe.VISHROOM.preparationTicks()) {
                // Only absorption milestones need a fresh knowledge read; the final one commits below.
                if (s.age % 20 == 0
                        && RitualResearch.check(s.level, s.sample.subject(), RESEARCH) != RitualResearch.Result.ALLOWED) {
                    end(s, player); continue;
                }
                s.offerings.advance(s.age);
                continue;
            }
            RitualPlane.invalidate(s.level, s.pos);
            boolean ready = RitualResearch.check(s.level, s.sample.subject(), RESEARCH) == RitualResearch.Result.ALLOWED
                    && RitualPlane.at(s.level, s.plane).isPresent() && VishroomEntity.clearForCreation(s.level, s.pos);
            // Detach the old session before callbacks from placement can start another ritual.
            SESSIONS.remove(s.id);
            if (ready && BlockPlacement.commit(player, s.pos, s.seed, VishroomContent.CORE.get().defaultBlockState())
                    && s.level.getBlockEntity(s.pos) instanceof VishroomEntity core) {
                s.offerings.advance(s.age);
                core.bind(s.sample.subject(), s.sample.literalName() ? s.sample.name() : "");
                vial.remove(RitualContent.TAGLOCK_SAMPLE.get());
                core.form();
            }
            s.offerings.release(); RitualVial.releaseAll(player, s.id);
        }
    }
    private static void end(Session s, ServerPlayer player) {
        SESSIONS.remove(s.id); s.offerings.release(); RitualVial.releaseAll(player, s.id);
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        for (Session s : List.copyOf(SESSIONS.values())) end(s, event.getServer().getPlayerList().getPlayer(s.initiator));
        STARTING.clear();
    }
    private static final class Session {
        final UUID id = UUID.randomUUID();
        final ServerLevel level; final BlockPos pos, plane; final BlockState seed;
        final UUID initiator; final TaglockSample sample; final OwnershipOfferings offerings;
        int age;
        Session(ServerLevel level, BlockPos pos, BlockPos plane, BlockState seed, UUID initiator,
                TaglockSample sample, OwnershipOfferings offerings) {
            this.level = level; this.pos = pos; this.plane = plane; this.seed = seed;
            this.initiator = initiator; this.sample = sample; this.offerings = offerings;
        }
    }
}
