package dev.senluo.witchcraft.client;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.accessory.BarrierVisuals;
import dev.senluo.witchcraft.accessory.FlightPath;
import dev.senluo.witchcraft.accessory.ProjectileBarrier;
import dev.senluo.witchcraft.accessory.TurtleField;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Bounded presentation of already committed contacts and authoritative projectile paths. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID, value = Dist.CLIENT)
public final class BarrierEffects {
    private static final int MOB = BarrierVisuals.MOB, SONIC = BarrierVisuals.SONIC,
            PROPAGATION = BarrierVisuals.PROPAGATION, PROJECTILE = 3;
    private static final int MAX_EFFECTS = 64, MAX_VISIBLE = 32, MAX_PER_SOURCE = 8;
    private static final int MAX_FLIGHTS = 256, FLIGHT_TOKEN_TTL = 1200;
    private static final double MAX_DISTANCE_SQR = 64 * 64;
    private static final Map<Key, Effect> EFFECTS = new LinkedHashMap<>();
    private static final Map<UUID, FlightToken> FLIGHTS = new LinkedHashMap<>(64, .75F, true);
    private static final List<Effect> VISIBLE = new ArrayList<>(MAX_EFFECTS);
    private static final Int2ObjectOpenHashMap<Effect> CAPTURES = new Int2ObjectOpenHashMap<>();
    private static final Comparator<Effect> VISIBILITY = Comparator.comparingDouble(effect -> effect.priority);
    private static ClientLevel level;
    private static long captureFrame;
    private static int captureVertices;
    private static boolean capturing;

    private record Key(UUID target, int sourceId, int kind) {}

    private static final class FlightToken {
        final WeakReference<Entity> entity;
        final Key key;
        long lastSeen;

        FlightToken(Entity entity, Key key, long now) {
            this.entity = new WeakReference<>(entity);
            this.key = key;
            lastSeen = now;
        }
    }

    static final class Effect {
        final Key key;
        final int targetId;
        final UUID source;
        final long born;
        final int lifetime;
        final BarrierModelCapture model;
        long refreshed;
        Vec3 point, normal, across, up;
        float strength, radius, aspect = 1;
        FlightPath path;
        Vec3 centerOffset;
        double initialGap;
        float opacity, compression, flow;
        double priority;
        Vec3 bodyCenter, halfBody;
        BarrierOutline outline;

        Effect(Key key, int targetId, UUID source, long now, int lifetime) {
            this.key = key;
            this.targetId = targetId;
            this.source = source;
            born = refreshed = now;
            this.lifetime = lifetime;
            model = key.kind == MOB ? new BarrierModelCapture() : null;
        }

        void orient(Vec3 direction) {
            normal = direction.normalize();
            Vec3 reference = Math.abs(normal.y) < .9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
            across = normal.cross(reference).normalize();
            up = normal.cross(across);
        }

        boolean flowing() { return key.kind == MOB; }

        void prepare(double now, Vec3 camera, ClientLevel current, DeltaTracker partialTick) {
            double age = Math.max(0, now - born), remaining = refreshed + lifetime - now;
            double fade = Math.clamp(remaining / (key.kind == MOB ? 4 : 10), 0, 1);
            fade *= fade * (3 - 2 * fade);
            opacity = (float)(Math.min(1, age / 2) * fade * (.65 + .35 * Math.sqrt(strength)));
            flow = (float)((age % 160) / 160);
            if (path != null) {
                // limit is the entity's base position, not its captured body centre.
                point = path.limit().add(centerOffset);
                // The actual remaining gap controls the membrane's pressure, without simulating another path.
                compression = (float)Math.sqrt(Math.clamp(path.at(now).distanceTo(path.limit()) / initialGap, 0, 1));
                // Keep a quiet boundary visible while the projectile remains
                // constrained; snapshots refresh liveness, never replay arrival.
                opacity *= (float)(.46 + .54 * Math.exp(-age / 35));
            } else {
                compression = (float)Math.exp(-age * Math.log(2) / 7);
            }
            if (flowing()) {
                Entity target = current.getEntity(targetId);
                if (target != null) {
                    // Match LevelRenderer's interpolated body, so a live contact cannot drift inside it between packets.
                    float partial = partialTick.getGameTimeDeltaPartialTick(!current.tickRateManager().isEntityFrozen(target));
                    AABB bounds = target.getBoundingBox();
                    Vec3 center = bounds.getCenter().add(
                            Mth.lerp(partial, target.xOld, target.getX()) - target.getX(),
                            Mth.lerp(partial, target.yOld, target.getY()) - target.getY(),
                            Mth.lerp(partial, target.zOld, target.getZ()) - target.getZ());
                    bodyCenter = center;
                    halfBody = new Vec3(bounds.getXsize() * .5, bounds.getYsize() * .5, bounds.getZsize() * .5);
                    outline = model.shape(this);
                    if (outline != null) {
                        point = center.add(normal.scale(outline.nearDepth()));
                        radius = outline.maxRadius();
                        aspect = 1;
                    }
                }
            }
            priority = opacity / (1 + point.distanceToSqr(camera));
        }

        boolean valid(ClientLevel current) {
            Entity target = current.getEntity(targetId);
            // A sonic emitter may be outside tracking range while its local contact is visible.
            if (key.kind != SONIC && key.kind != PROPAGATION && (target == null || target.isRemoved() || !target.isAlive()
                    || !key.target.equals(target.getUUID()))) return false;
            if (key.sourceId < 0) return true;
            return current.getEntity(key.sourceId) instanceof Player player
                    && player.getUUID().equals(source) && TurtleField.isSourceActive(player);
        }
    }

    @EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Registration {
        @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> BarrierVisuals.install(BarrierEffects::contact, BarrierEffects::flight));
            event.enqueueWork(() -> {
                if (net.neoforged.fml.ModList.get().isLoaded("thaumcraft")) {
                    dev.senluo.witchcraft.compat.thaumcraft.client.FocusBarrierVisuals.register();
                    dev.senluo.witchcraft.compat.thaumcraft.client.RitualHerbaSheen.register();
                }
            });
        }

        @SubscribeEvent public static void reload(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((ResourceManagerReloadListener) resources -> {
                captureFrame++;
                BarrierEffectRenderer.release();
            });
        }

        private Registration() {}
    }

    /** Local presentation only; uses the same bounded effects and source liveness as packet contacts. */
    public static void beam(Entity emitter, dev.senluo.witchcraft.accessory.PropagationField.Hit hit) {
        if (emitter.level() != Minecraft.getInstance().level) return;
        contact(new BarrierVisuals.Contact(emitter.level().dimension().location(), emitter.getId(), hit.sourceId(),
                PROPAGATION, hit.point(), hit.direction(), .75F, .45F));
    }

    private static void contact(BarrierVisuals.Contact contact) {
        ClientLevel current = Minecraft.getInstance().level;
        changeLevel(current);
        if (current == null || !current.dimension().location().equals(contact.dimension())
                || (contact.kind() != MOB && contact.kind() != SONIC && contact.kind() != PROPAGATION)
                || !finite(contact.point()) || !finite(contact.normal()) || contact.normal().lengthSqr() < 1.0E-8
                || !(contact.strength() > 0) || !Float.isFinite(contact.strength()) || !Float.isFinite(contact.radius())) return;
        Entity target = current.getEntity(contact.entityId());
        if (contact.kind() == MOB && (target == null || !target.isAlive() || target.isRemoved())) return;
        UUID source = null;
        if (contact.sourceId() >= 0) {
            if (!(current.getEntity(contact.sourceId()) instanceof Player player) || !TurtleField.isSourceActive(player)) return;
            source = player.getUUID();
        }
        long now = current.getGameTime();
        UUID targetKey = target == null ? new UUID(0, Integer.toUnsignedLong(contact.entityId())) : target.getUUID();
        Key key = new Key(targetKey, contact.sourceId(), contact.kind());
        Effect effect = EFFECTS.get(key);
        if (effect == null || !java.util.Objects.equals(effect.source, source)) {
            EFFECTS.remove(key);
            effect = new Effect(key, contact.entityId(), source, now, contact.kind() == MOB ? 18 : 24);
            insert(effect);
        }
        effect.point = contact.point();
        // Keep the committed incoming axis; mob rendering puts its light on the open side of the body.
        effect.orient(contact.normal().scale(-1));
        effect.radius = Math.clamp(contact.radius(), .2F, .9F);
        effect.strength = Math.clamp(contact.strength(), 0, 1);
        effect.refreshed = now;
    }

    private static void flight(Entity entity, FlightPath path) {
        ClientLevel current = Minecraft.getInstance().level;
        if (current == null || entity.level() != current) return;
        changeLevel(current);
        UUID id = entity.getUUID();
        FlightToken token = FLIGHTS.get(id);
        if (!path.active()) {
            if (token != null) { FLIGHTS.remove(id); EFFECTS.remove(token.key); }
            return;
        }
        if (entity.isRemoved() || !finite(path.start()) || !finite(path.limit()) || !finite(path.input())) return;
        long now = current.getGameTime();
        if (token != null && token.entity.get() == entity) {
            token.lastSeen = now;
            Effect existing = EFFECTS.get(token.key);
            if (existing != null) { existing.path = path; existing.refreshed = now; }
            // A clock retime or corrected snapshot updates the path without restarting its pulse.
            return;
        }
        Vec3 direction = path.start().subtract(path.limit());
        if (direction.lengthSqr() < 1.0E-8) direction = path.input().scale(-1);
        if (direction.lengthSqr() < 1.0E-8) return;
        Key key = new Key(id, -1, PROJECTILE);
        // Admission drops excess visuals instead of evicting live tokens and replaying their arrivals.
        if (FLIGHTS.size() >= MAX_FLIGHTS) return;
        FLIGHTS.put(id, new FlightToken(entity, key, now));
        Effect effect = new Effect(key, entity.getId(), null, now, 80);
        effect.path = path;
        effect.centerOffset = entity.getBoundingBox().getCenter().subtract(entity.position());
        effect.initialGap = Math.max(1.0E-6, path.start().distanceTo(path.limit()));
        effect.point = path.limit().add(effect.centerOffset);
        effect.orient(direction);
        effect.strength = 1;
        effect.radius = Math.clamp(entity.getBbWidth() * .5F + .26F, .32F, .65F);
        insert(effect);
    }

    private static void insert(Effect effect) {
        if (effect.key.sourceId >= 0) {
            int count = 0;
            Effect victim = null;
            for (Effect existing : EFFECTS.values()) {
                if (existing.key.sourceId != effect.key.sourceId) continue;
                count++;
                if (olderOrWeaker(existing, victim)) victim = existing;
            }
            if (count >= MAX_PER_SOURCE && victim != null) EFFECTS.remove(victim.key);
        }
        if (EFFECTS.size() >= MAX_EFFECTS) {
            Effect victim = null;
            for (Effect existing : EFFECTS.values()) if (olderOrWeaker(existing, victim)) victim = existing;
            if (victim != null) EFFECTS.remove(victim.key);
        }
        EFFECTS.put(effect.key, effect);
    }

    private static boolean olderOrWeaker(Effect candidate, Effect previous) {
        return previous == null || candidate.strength < previous.strength
                || (candidate.strength == previous.strength && candidate.refreshed < previous.refreshed);
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        ClientLevel current = Minecraft.getInstance().level;
        changeLevel(current);
        if (current == null) return;
        long now = current.getGameTime();
        EFFECTS.values().removeIf(effect -> now - effect.refreshed >= effect.lifetime || !effect.valid(current));
        FLIGHTS.values().removeIf(token -> {
            Entity entity = token.entity.get();
            boolean expired = entity == null || entity.isRemoved() || entity.level() != current;
            if (!expired && now - token.lastSeen > FLIGHT_TOKEN_TTL) {
                // A suspended entity may not tick for a long time; retaining its active token prevents a false new arrival.
                expired = !ProjectileBarrier.path(entity).active();
                if (!expired) token.lastSeen = now;
            }
            if (expired) EFFECTS.remove(token.key);
            return expired;
        });
    }

    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) {
            beginCaptures(event);
            return;
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) capturing = false;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        capturing = false;
        ClientLevel current = Minecraft.getInstance().level;
        changeLevel(current);
        if (current == null || EFFECTS.isEmpty()) return;
        double now = current.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        VISIBLE.clear();
        for (Effect effect : EFFECTS.values()) {
            if (!effect.valid(current)) continue;
            effect.prepare(now, camera, current, event.getPartialTick());
            if (effect.opacity <= .001F || effect.point.distanceToSqr(camera) > MAX_DISTANCE_SQR) continue;
            // Includes the actual projected silhouette and its shallow pressure dent.
            double bound = effect.radius * Math.max(1, effect.aspect) * 1.22 + 1.5;
            if (!event.getFrustum().isVisible(new AABB(effect.point, effect.point).inflate(bound))) continue;
            VISIBLE.add(effect);
        }
        if (VISIBLE.isEmpty()) return;
        VISIBLE.sort(VISIBILITY.reversed());
        BarrierEffectRenderer.render(event, VISIBLE, Math.min(MAX_VISIBLE, VISIBLE.size()));
        VISIBLE.clear();
    }

    private static void beginCaptures(RenderLevelStageEvent event) {
        ClientLevel current = Minecraft.getInstance().level;
        changeLevel(current);
        captureFrame++;
        captureVertices = 8192;
        CAPTURES.clear();
        capturing = current != null;
        if (!capturing) return;
        Vec3 camera = event.getCamera().getPosition();
        double now = current.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        VISIBLE.clear();
        for (Effect effect : EFFECTS.values()) {
            if (!effect.flowing() || !effect.valid(current)) continue;
            effect.prepare(now, camera, current, event.getPartialTick());
            if (effect.opacity <= .001F || effect.point.distanceToSqr(camera) > MAX_DISTANCE_SQR) continue;
            VISIBLE.add(effect);
        }
        VISIBLE.sort(VISIBILITY.reversed());
        for (Effect effect : VISIBLE) {
            if (CAPTURES.size() == MAX_VISIBLE) break;
            CAPTURES.putIfAbsent(effect.targetId, effect);
        }
        VISIBLE.clear();
    }

    static Effect captureTarget(Entity entity) {
        if (!capturing || entity.level() != level || captureVertices <= 0) return null;
        Effect effect = CAPTURES.get(entity.getId());
        return effect != null && effect.key.target.equals(entity.getUUID()) && effect.model.frame() != captureFrame ? effect : null;
    }

    static boolean captureVertex() { return captureVertices-- > 0; }
    static long captureFrame() { return captureFrame; }

    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { changeLevel(null); }

    private static void changeLevel(ClientLevel current) {
        if (level == current) return;
        EFFECTS.clear();
        FLIGHTS.clear();
        VISIBLE.clear();
        CAPTURES.clear();
        capturing = false;
        captureFrame++;
        BarrierEffectRenderer.release();
        level = current;
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private BarrierEffects() {}
}
