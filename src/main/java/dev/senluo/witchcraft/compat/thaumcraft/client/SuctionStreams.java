package dev.senluo.witchcraft.compat.thaumcraft.client;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.PriorityQueue;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Recent transfers renew emitters, not beams. Native TC particles own movement and rendering. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID, value = Dist.CLIENT)
public final class SuctionStreams {
    private record Route(Vec3 from, Vec3 to, int color) {}
    private static final class Stream {
        final Route route;
        final int maximumLifetime;
        long renewed;

        Stream(Route route, long now) {
            this.route = route;
            renewed = now;
            // TC4R 20740: base=floor(distance*30), life=base/2+random(base).
            // Conservative reservations bound our particles even when an obstacle delays arrival.
            int base = Math.max(1, (int) Math.ceil(route.from.distanceTo(route.to) * 30));
            maximumLifetime = base + base / 2 + 2;
        }
    }
    private static final LinkedHashMap<Route, Stream> STREAMS = new LinkedHashMap<>(64, .75F, true);
    private static final PriorityQueue<Long> PARTICLE_EXPIRIES = new PriorityQueue<>();
    private static final ArrayList<Stream> VISIBLE = new ArrayList<>(64);
    private static WeakReference<ClientLevel> world = new WeakReference<>(null);
    private static long lastTick = Long.MIN_VALUE;
    private static long emittedTick = Long.MIN_VALUE;
    private static final int LEASE = 32; // Bridges normal 20-tick transfers and the server's 10-tick visual gate.

    private SuctionStreams() {}

    private static boolean finite(Vec3 pos) {
        return pos != null && Double.isFinite(pos.x) && Double.isFinite(pos.y) && Double.isFinite(pos.z);
    }

    private static void clear() {
        STREAMS.clear();
        PARTICLE_EXPIRIES.clear();
        VISIBLE.clear();
        world.clear();
        lastTick = emittedTick = Long.MIN_VALUE;
    }

    private static void expire(ClientLevel level) {
        long now = level.getGameTime();
        if (world.get() != level || now < lastTick) {
            clear();
            world = new WeakReference<>(level);
        }
        lastTick = now;
        STREAMS.values().removeIf(stream -> now - stream.renewed >= LEASE);
        while (!PARTICLE_EXPIRIES.isEmpty() && PARTICLE_EXPIRIES.peek() <= now) PARTICLE_EXPIRIES.remove();
    }

    static void accept(ClientLevel level, Vec3 from, Vec3 to, int color) {
        if (level == null || level != Minecraft.getInstance().level || !finite(from) || !finite(to)
                || from.distanceToSqr(to) < 1.0e-8 || from.distanceToSqr(to) > 24 * 24) return;
        expire(level);
        Route route = new Route(from, to, color & 0xFFFFFF);
        Stream stream = STREAMS.get(route);
        if (stream != null) {
            stream.renewed = level.getGameTime();
            return;
        }
        if (STREAMS.size() >= 64) STREAMS.remove(STREAMS.keySet().iterator().next());
        STREAMS.put(route, new Stream(route, level.getGameTime()));
    }

    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) { clear(); return; }
        if (mc.isPaused()) return;
        expire(mc.level);
        long now = mc.level.getGameTime();
        if (now == emittedTick) return;
        emittedTick = now; // Emit once per simulated tick, never per rendered frame or missed tick.
        if (STREAMS.isEmpty()) return;

        int routeLimit = switch (mc.options.particles().get()) { case ALL -> 64; case DECREASED -> 32; case MINIMAL -> 8; };
        int particleLimit = switch (mc.options.particles().get()) { case ALL -> 4096; case DECREASED -> 2048; case MINIMAL -> 512; };
        double visibleDistance = mc.options.graphicsMode().get() == GraphicsStatus.FAST ? 32 : 64;
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        VISIBLE.clear();
        int lifetimeTotal = 0;
        // Most recently renewed routes first. Old unrenewed trails finish in the native particle engine.
        for (Stream stream : STREAMS.sequencedValues().reversed()) {
            Route route = stream.route;
            if (route.from.distanceToSqr(camera) > visibleDistance * visibleDistance
                    || !mc.level.hasChunkAt(BlockPos.containing(route.from))
                    || !mc.level.hasChunkAt(BlockPos.containing(route.to))) continue;
            VISIBLE.add(stream);
            lifetimeTotal += stream.maximumLifetime;
            if (VISIBLE.size() >= routeLimit) break;
        }
        // Keep a normal nearby stream at TC's one-mote-per-tick cadence. Thin only crowded scenes,
        // independently of actual essentia throughput, and retain a hard cosmetic occupancy budget.
        int interval = Math.max(1, (lifetimeTotal + particleLimit - 1) / particleLimit);
        for (Stream stream : VISIBLE) {
            if (PARTICLE_EXPIRIES.size() >= particleLimit) break;
            Route route = stream.route;
            if (Math.floorMod(now + route.hashCode(), interval) != 0) continue;
            long remaining = LEASE - (now - stream.renewed);
            float scale = remaining > 5 ? 1 : remaining * remaining / 25.0F;
            if (SuctionChannelEffects.emit(mc.level, route.from, route.to, (int) now, route.color, scale)) {
                PARTICLE_EXPIRIES.add(now + stream.maximumLifetime);
            }
        }
        VISIBLE.clear();
    }
}
