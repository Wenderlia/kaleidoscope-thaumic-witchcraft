package dev.senluo.witchcraft.ritual;

import java.math.BigInteger;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/** Dimension-owned cubic fields. Coarse buckets grow with a field: even very large radii use at most four entries. */
public final class NodePromotionFields {
    public record Field(UUID session, BlockPos center, int radius, int multiplier, UUID owner) {
        public Field {
            Objects.requireNonNull(session); center = center.immutable();
            if (radius < 1 || multiplier < 2) throw new IllegalArgumentException("Invalid node promotion field");
        }
        boolean contains(BlockPos pos) {
            return Math.abs((long)pos.getX() - center.getX()) <= radius
                    && Math.abs((long)pos.getY() - center.getY()) <= radius
                    && Math.abs((long)pos.getZ() - center.getZ()) <= radius;
        }
        int shift() { return Math.max(4, 64 - Long.numberOfLeadingZeros(2L * radius)); }
    }
    public record Influence(BigInteger multiplier, Set<UUID> owners) {}
    private final Map<UUID, Field> fields = new HashMap<>();
    private final Map<Integer, Map<Long, Set<UUID>>> scales = new HashMap<>();
    private final java.util.function.Predicate<UUID> permitted;
    private long revision;
    public NodePromotionFields() { this(id -> true); }
    public NodePromotionFields(java.util.function.Predicate<UUID> permitted) { this.permitted = permitted; }
    public static int steps(int amount) {
        long n = Math.max(0, amount);
        int k = (int)((Math.sqrt(8.0 * n + 1) - 1) / 2);
        while ((long)(k + 1) * (k + 2) / 2 <= n) k++;
        while ((long)k * (k + 1) / 2 > n) k--;
        return k;
    }
    public void put(Field field) {
        if (field.equals(fields.get(field.session()))) return;
        revision++;
        remove(field.session()); fields.put(field.session(), field);
        var scale = scales.computeIfAbsent(field.shift(), unused -> new HashMap<>());
        visit(field, key -> scale.computeIfAbsent(key, unused -> new HashSet<>()).add(field.session()));
    }
    public void remove(UUID id) {
        var field = fields.remove(id);
        if (field == null) return;
        revision++;
        var scale = scales.get(field.shift());
        visit(field, key -> {
            var bucket = scale.get(key);
            if (bucket != null && bucket.remove(id) && bucket.isEmpty()) scale.remove(key);
        });
        if (scale.isEmpty()) scales.remove(field.shift());
    }
    public Influence at(BlockPos position) {
        for (int attempt = 0; attempt < 2; attempt++) {
            long started = revision;
            BigInteger rate = BigInteger.ONE;
            Set<UUID> owners = new HashSet<>();
            for (var scale : List.copyOf(scales.entrySet())) {
                int shift = scale.getKey();
                var bucket = scale.getValue().get(ChunkPos.asLong(position.getX() >> shift, position.getZ() >> shift));
                if (bucket == null) continue;
                for (var id : List.copyOf(bucket)) {
                    var field = fields.get(id);
                    if (field == null || !field.contains(position) || !permitted.test(id) || fields.get(id) != field) continue;
                    rate = rate.multiply(BigInteger.valueOf(field.multiplier()));
                    if (field.owner() != null) owners.add(field.owner());
                }
            }
            if (revision == started) return new Influence(rate, Set.copyOf(owners));
        }
        return new Influence(BigInteger.ONE, Set.of());
    }
    private static void visit(Field f, java.util.function.LongConsumer consumer) {
        int shift = f.shift();
        int x0 = (int)(((long)f.center().getX() - f.radius()) >> shift);
        int x1 = (int)(((long)f.center().getX() + f.radius()) >> shift);
        int z0 = (int)(((long)f.center().getZ() - f.radius()) >> shift);
        int z1 = (int)(((long)f.center().getZ() + f.radius()) >> shift);
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) consumer.accept(ChunkPos.asLong(x,z));
    }
}
