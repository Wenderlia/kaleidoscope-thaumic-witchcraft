package dev.senluo.witchcraft.time;

import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Server-thread provenance, not machine ownership. Leases never survive an entity replacement. */
public final class AccelerationSources {
    private static final ThreadLocal<Set<UUID>> CURRENT = new ThreadLocal<>();
    private static final Map<BlockEntity, Map<UUID, Long>> LEASES = new WeakHashMap<>();
    private static final Map<BlockEntity, Long> OBSERVED = new WeakHashMap<>();
    private static final List<java.util.function.BiConsumer<ServerLevel, BlockEntity>> OBSERVERS = new ArrayList<>();
    private AccelerationSources() {}

    /** Wrap the actual additional work; a missing source owner never matches another missing owner. */
    public static void run(UUID owner, Runnable work) { run(owner == null ? Set.of() : Set.of(owner), work); }
    public static void run(Set<UUID> owners, Runnable work) {
        Set<UUID> previous = CURRENT.get();
        CURRENT.set(Set.copyOf(owners));
        try { work.run(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
    public static boolean currentOwner(UUID owner) {
        Set<UUID> current = CURRENT.get();
        return owner != null && current != null && current.contains(owner);
    }
    private static final ThreadLocal<LivingEntity> PRODUCER = new ThreadLocal<>();
    public static void runProduction(LivingEntity source, Runnable work) {
        LivingEntity previous = PRODUCER.get();
        PRODUCER.set(source);
        try { run(source.owner(), work); }
        finally { if (previous == null) PRODUCER.remove(); else PRODUCER.set(previous); }
    }
    static boolean producedBy(LivingEntity source) { return source == PRODUCER.get(); }
    /** Explicit adapters may renew before the natural ticker. Duration is in simulation ticks. */
    public static void renew(ServerLevel level, BlockEntity target, UUID owner, int ticks) {
        if (owner == null || target == null || target.isRemoved() || target.getLevel() != level) return;
        if (ticks < 1 || ticks > 20) throw new IllegalArgumentException("Acceleration lease must be 1..20 ticks");
        long now = level.getGameTime();
        Map<UUID, Long> owners = LEASES.computeIfAbsent(target, unused -> new HashMap<>());
        Long existing = owners.get(owner);
        long expiry = now + ticks - 1L;
        if (existing != null && existing >= expiry) return;
        owners.values().removeIf(until -> until < now);
        owners.put(owner, expiry);
    }
    static void observe(ServerLevel level, BlockEntity target) {
        Set<UUID> current = CURRENT.get();
        if (current != null) for (UUID owner : current) renew(level, target, owner, 2);
        long now = level.getGameTime();
        Long previous = OBSERVED.get(target);
        if (previous != null && previous == now) return;
        OBSERVED.put(target, now);
        for (var observer : OBSERVERS) observer.accept(level, target);
    }
    /** Register once during setup. Observers report active accelerators; they must not run work. */
    public static void registerObserver(java.util.function.BiConsumer<ServerLevel, BlockEntity> observer) {
        OBSERVERS.add(Objects.requireNonNull(observer));
    }
    public static boolean protects(ServerLevel level, BlockEntity target, UUID owner) {
        if (owner == null || target == null || target.isRemoved() || target.getLevel() != level) return false;
        if (currentOwner(owner)) return true;
        Map<UUID, Long> owners = LEASES.get(target);
        if (owners == null) return false;
        long now = level.getGameTime();
        Long until = owners.get(owner);
        if (until == null) return false;
        if (until >= now) return true;
        owners.remove(owner);
        if (owners.isEmpty()) LEASES.remove(target);
        return false;
    }
    public static void unload(ServerLevel level) {
        LEASES.keySet().removeIf(entity -> entity.getLevel() == level);
        OBSERVED.keySet().removeIf(entity -> entity.getLevel() == level);
    }
}
