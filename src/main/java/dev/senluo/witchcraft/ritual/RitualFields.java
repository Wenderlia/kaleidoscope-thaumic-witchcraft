package dev.senluo.witchcraft.ritual;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/** A host-owned, dimension-local index of validated steam fields; it neither scans nor loads the world. */
public final class RitualFields {
    public record Field(UUID session, BlockPos center, int radius, int doublings, UUID owner) {
        public Field(UUID session, BlockPos center, int radius, int doublings) { this(session, center, radius, doublings, null); }
        public Field(UUID session, BlockPos center, int radius) { this(session, center, radius, 1); }
        public Field {
            java.util.Objects.requireNonNull(session);
            center = center.immutable();
            if (radius < 1 || radius > 32) throw new IllegalArgumentException("Invalid bound steam radius");
            if (doublings < 1) throw new IllegalArgumentException("A steam field must accelerate work");
        }
        boolean contains(Vec3 point) { return Vec3.atCenterOf(center).distanceToSqr(point) <= (long) radius * radius; }
    }
    /** Exact symbolic multiplication, with no silent cap, overflow or executor hidden in the calculation. */
    public record Rate(long doublings) {
        public Rate { if (doublings < 0) throw new IllegalArgumentException("Negative rate exponent"); }
        public OptionalLong asLong() { return doublings < 63 ? OptionalLong.of(1L << doublings) : OptionalLong.empty(); }
    }
    private final Map<UUID, Field> fields = new HashMap<>();
    private final Map<Long, Set<UUID>> chunks = new HashMap<>();
    private final java.util.function.LongConsumer changed;
    private final java.util.function.Predicate<UUID> permitted;
    private long revision;
    public RitualFields() { this(chunk -> {}); }
    public RitualFields(java.util.function.LongConsumer changed) { this(changed, id -> true); }
    public RitualFields(java.util.function.LongConsumer changed, java.util.function.Predicate<UUID> permitted) {
        this.changed = changed; this.permitted = permitted;
    }

    public void put(Field field) {
        if (field.equals(fields.get(field.session()))) return;
        revision++;
        Set<Long> affected = new HashSet<>();
        unlink(field.session(), affected);
        fields.put(field.session(), field);
        visitChunks(field, key -> {
            chunks.computeIfAbsent(key, ignored -> new HashSet<>()).add(field.session());
            affected.add(key);
        });
        affected.forEach(changed::accept);
    }
    public void remove(UUID session) {
        if (!fields.containsKey(session)) return;
        revision++;
        Set<Long> affected = new HashSet<>();
        unlink(session, affected);
        affected.forEach(changed::accept);
    }
    private void unlink(UUID session, Set<Long> affected) {
        Field field = fields.remove(session);
        if (field == null) return;
        visitChunks(field, key -> {
            affected.add(key);
            Set<UUID> bucket = chunks.get(key);
            if (bucket != null && bucket.remove(session) && bucket.isEmpty()) chunks.remove(key);
        });
    }
    public Rate at(Vec3 position) {
        if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z))
            throw new IllegalArgumentException("Non-finite field position");
        for (int attempt = 0; attempt < 2; attempt++) {
            long started = revision;
            Set<UUID> bucket = chunks.get(ChunkPos.asLong(BlockPos.containing(position)));
            long doublings = 0;
            if (bucket != null) for (UUID id : java.util.List.copyOf(bucket)) {
                Field field = fields.get(id);
                if (field != null && field.contains(position) && permitted.test(id) && fields.get(id) == field)
                    doublings = Math.addExact(doublings, field.doublings());
            }
            if (revision == started) return new Rate(doublings);
        }
        return new Rate(0);
    }
    public void clear() {
        revision++;
        var affected = Set.copyOf(chunks.keySet());
        fields.clear(); chunks.clear();
        affected.forEach(changed::accept);
    }
    public boolean near(BlockPos pos) { return chunks.containsKey(ChunkPos.asLong(pos)); }
    /** Candidate routing is geometry only; a pending authorization must not erase the wake-up route. */
    public boolean covers(Vec3 position) {
        Set<UUID> bucket = chunks.get(ChunkPos.asLong(BlockPos.containing(position)));
        if (bucket != null) for (UUID id : bucket) if (fields.get(id).contains(position)) return true;
        return false;
    }
    public Set<UUID> ownersAt(Vec3 position) {
        for (int attempt = 0; attempt < 2; attempt++) {
            long started = revision;
            Set<UUID> bucket = chunks.get(ChunkPos.asLong(BlockPos.containing(position)));
            if (bucket == null) return Set.of();
            Set<UUID> owners = new HashSet<>();
            for (UUID id : java.util.List.copyOf(bucket)) {
                Field field = fields.get(id);
                if (field != null && field.owner() != null && field.contains(position) && permitted.test(id) && fields.get(id) == field)
                    owners.add(field.owner());
            }
            if (revision == started) return owners;
        }
        return Set.of();
    }
    private static void visitChunks(Field field, java.util.function.LongConsumer consumer) {
        int x0 = (int) (((long) field.center().getX() - field.radius()) >> 4);
        int x1 = (int) (((long) field.center().getX() + field.radius()) >> 4);
        int z0 = (int) (((long) field.center().getZ() - field.radius()) >> 4);
        int z1 = (int) (((long) field.center().getZ() + field.radius()) >> 4);
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) consumer.accept(ChunkPos.asLong(x, z));
    }
}
