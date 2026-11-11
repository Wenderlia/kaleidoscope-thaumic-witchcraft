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

    public void put(Field field) {
        if (field.equals(fields.get(field.session()))) return;
        remove(field.session());
        fields.put(field.session(), field);
        visitChunks(field, key -> chunks.computeIfAbsent(key, ignored -> new HashSet<>()).add(field.session()));
    }
    public void remove(UUID session) {
        Field field = fields.remove(session);
        if (field == null) return;
        visitChunks(field, key -> {
            Set<UUID> bucket = chunks.get(key);
            if (bucket != null && bucket.remove(session) && bucket.isEmpty()) chunks.remove(key);
        });
    }
    public Rate at(Vec3 position) {
        if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z))
            throw new IllegalArgumentException("Non-finite field position");
        Set<UUID> bucket = chunks.get(ChunkPos.asLong(BlockPos.containing(position)));
        long doublings = 0;
        if (bucket != null) for (UUID id : bucket) {
            Field field = fields.get(id);
            if (field.contains(position)) doublings = Math.addExact(doublings, field.doublings());
        }
        return new Rate(doublings);
    }
    public void clear() { fields.clear(); chunks.clear(); }
    public boolean near(BlockPos pos) { return chunks.containsKey(ChunkPos.asLong(pos)); }
    public Set<UUID> ownersAt(Vec3 position) {
        Set<UUID> bucket = chunks.get(ChunkPos.asLong(BlockPos.containing(position)));
        if (bucket == null) return Set.of();
        Set<UUID> owners = new HashSet<>();
        for (UUID id : bucket) {
            Field field = fields.get(id);
            if (field.owner() != null && field.contains(position)) owners.add(field.owner());
        }
        return owners;
    }
    private static void visitChunks(Field field, java.util.function.LongConsumer consumer) {
        int x0 = (int) (((long) field.center().getX() - field.radius()) >> 4);
        int x1 = (int) (((long) field.center().getX() + field.radius()) >> 4);
        int z0 = (int) (((long) field.center().getZ() - field.radius()) >> 4);
        int z1 = (int) (((long) field.center().getZ() + field.radius()) >> 4);
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) consumer.accept(ChunkPos.asLong(x, z));
    }
}
