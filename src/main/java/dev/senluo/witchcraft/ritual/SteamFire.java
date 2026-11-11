package dev.senluo.witchcraft.ritual;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/** Native fire opportunities, with fractional scheduling carried forward rather than truncated. */
public final class SteamFire {
    private record Clock(long doublings, BigInteger credit) {}
    private static final Map<ServerLevel, Map<Long, Map<BlockPos, Clock>>> CLOCKS = new WeakHashMap<>();
    private static final ThreadLocal<Call> CALL = new ThreadLocal<>();
    private static final class Call {
        final ServerLevel level; final BlockPos pos; final Block block;
        int delay;
        Call(ServerLevel level, BlockPos pos, Block block) { this.level=level; this.pos=pos; this.block=block; }
    }
    private SteamFire() {}
    public static void unload(ServerLevel level) { CLOCKS.remove(level); }
    public static void unloadChunk(ServerLevel level, ChunkPos chunk) {
        var buckets = CLOCKS.get(level); if (buckets != null) buckets.remove(chunk.toLong());
    }
    public static void replaced(ServerLevel level, BlockPos pos) {
        var buckets = CLOCKS.get(level);
        if (buckets == null) return;
        long key = ChunkPos.asLong(pos); var clocks = buckets.get(key);
        if (clocks != null) { clocks.remove(pos); if (clocks.isEmpty()) buckets.remove(key); }
    }
    public static boolean capture(ServerLevel level, BlockPos pos, Block block, int delay) {
        Call call = CALL.get();
        if (call == null || call.level != level || !call.pos.equals(pos) || call.block != block) return false;
        call.delay = delay; return true;
    }
    public static void tick(ServerLevel level, BlockPos pos, Block block, Runnable nativeTick) {
        long n = SteamWork.doublings(level, Vec3.atCenterOf(pos));
        if (CALL.get() != null || n == 0 || !level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK)) {
            replaced(level, pos);
            nativeTick.run(); return;
        }
        var clocks = CLOCKS.computeIfAbsent(level, unused -> new HashMap<>())
                .computeIfAbsent(ChunkPos.asLong(pos), unused -> new HashMap<>());
        Clock previous = clocks.remove(pos);
        BigInteger credit = previous != null && previous.doublings == n ? previous.credit : BigInteger.ZERO;
        // 2^49 exceeds the largest int delay times the maximum 65,536-update budget.
        // Larger logical rates cannot be fulfilled; do not allocate their huge binary expansion.
        BigInteger rate = BigInteger.ONE.shiftLeft((int) Math.min(n, 49)), delay = credit.negate();
        Call call = new Call(level, pos, block);
        boolean limited = false;
        CALL.set(call);
        try {
            do {
                call.delay = 0;
                nativeTick.run();
                if (!level.hasChunkAt(pos) || !level.getBlockState(pos).is(block)) return;
                if (call.delay <= 0) return; // A third-party ticker intentionally stopped scheduling.
                delay = delay.add(BigInteger.valueOf(call.delay));
                if (delay.signum() <= 0 && level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK)
                        && RitualWorkBudget.claim(level, RitualWorkBudget.Kind.FIRE, pos, BigInteger.ONE) == 0) {
                    limited = true;
                    break;
                }
            } while (delay.signum() <= 0 && level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK));
        } finally { CALL.remove(); }
        int next = limited ? 1 : delay.add(rate).subtract(BigInteger.ONE).divide(rate).max(BigInteger.ONE).intValueExact();
        // Keep normal fractional phase, but never retain denied work as catch-up debt.
        clocks.put(pos.immutable(), new Clock(n, limited ? BigInteger.ZERO : rate.multiply(BigInteger.valueOf(next)).subtract(delay)));
        level.scheduleTick(pos, block, next);
    }
    /** Only fire duration and the native periodic fire damage are advanced; invulnerability remains native. */
    public static void afterFireTime(Entity entity, int before, int after) {
        if (!(entity.level() instanceof ServerLevel level) || before <= 0 || after <= 0
                || entity.getRemainingFireTicks() != after) return;
        int unit = before - after;
        if (unit != 1 && unit != 4) return;
        long n = SteamWork.doublings(level, entity.position());
        if (n == 0) return;
        BigInteger requested = SteamWork.requestSteps(n).min(BigInteger.valueOf(((long) after + unit - 1) / unit));
        int steps = RitualWorkBudget.claimBurn(level, entity.getUUID(), requested);
        int extra = (int) Math.min(after, (long) steps * unit);
        int end = after - extra;
        if (!entity.fireImmune() && !entity.isInLava()) {
            for (int period = after / 20; period > end / 20 && entity.isAlive(); period--) {
                entity.hurt(entity.damageSources().onFire(), 1);
                // Respect callbacks which extinguish, replace or remove the burning entity.
                if (entity.level() != level || entity.getRemainingFireTicks() != after) return;
            }
        }
        if (entity.isAlive()) entity.setRemainingFireTicks(end);
    }
}
