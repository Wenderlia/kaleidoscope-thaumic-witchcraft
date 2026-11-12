package dev.senluo.witchcraft.ritual;

import dev.senluo.witchcraft.time.WorkSources;
import dev.senluo.witchcraft.time.WorkTicks;
import dev.senluo.witchcraft.time.ExtraTicks;
import dev.senluo.witchcraft.time.AccelerationSources;
import java.math.BigInteger;
import java.util.Map;
import java.util.IdentityHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.phys.Vec3;

/** Spatial lookup and provenance only; native tickers remain responsible for recipes and fuel. */
public final class SteamWork {
    private static final Map<ServerLevel, RitualFields> FIELDS = new IdentityHashMap<>();
    private SteamWork() {}
    public static RitualFields fields(ServerLevel level) {
        return FIELDS.computeIfAbsent(level, unused -> new RitualFields(chunk -> WorkTicks.invalidateChunk(level, chunk),
                id -> StructuralRitualStore.get(level).running(level, id)));
    }
    /** Cache fixed support and geometry; idle supported machines remain eligible to resume work. */
    public static boolean affected(ServerLevel level, BlockEntity entity) {
        var fields = FIELDS.get(level);
        return fields != null && WorkSources.supportsSteam(entity) && fields.near(entity.getBlockPos())
                && fields.covers(Vec3.atCenterOf(entity.getBlockPos()));
    }
    public static void unload(ServerLevel level) { FIELDS.remove(level); SteamFire.unload(level); RitualWorkBudget.unload(level); }
    public static boolean extra() { return ExtraTicks.active(); }
    public static java.util.Set<java.util.UUID> owners(ServerLevel level, BlockPos pos) {
        RitualFields fields = FIELDS.get(level);
        return fields == null ? java.util.Set.of() : fields.ownersAt(Vec3.atCenterOf(pos));
    }
    public static void observeSources(ServerLevel level, BlockEntity entity) {
        RitualFields fields = FIELDS.get(level);
        if (fields == null || !fields.near(entity.getBlockPos()) || !WorkSources.steamWork(level, entity)) return;
        for (var owner : owners(level, entity.getBlockPos())) AccelerationSources.renew(level, entity, owner, 1);
    }
    public static long doublings(ServerLevel level, Vec3 position) {
        RitualFields fields = FIELDS.get(level);
        return fields == null ? 0 : fields.at(position).doublings();
    }
    /** Demand only: capacity+1 also records saturation, without allocating an exponential integer. */
    public static BigInteger requestSteps(long doublings) {
        long excess = (long) RitualConfig.EXTRA_UPDATES.get() + 1;
        return BigInteger.valueOf(doublings >= 31 ? excess : Math.min(excess, (1L << doublings) - 1));
    }
    /** The identity was captured before the granted native callback, not rediscovered after it. */
    public static void afterNative(ServerLevel level, TickingBlockEntity ticker, BlockEntity identity, Runnable nativeTick) {
        if (extra() || identity == null) return;
        BlockPos pos = identity.getBlockPos();
        RitualFields fields = FIELDS.get(level);
        if (fields == null || !fields.near(pos) || !WorkTicks.isScheduledTarget(level, ticker, identity)) return;
        long n = fields.at(Vec3.atCenterOf(pos)).doublings();
        if (n == 0) return;
        if (!WorkSources.steamWork(level, identity)) return;
        var owners = owners(level, pos);
        AccelerationSources.run(owners, () -> ExtraTicks.run(() -> {
            int work = RitualWorkBudget.claim(level, RitualWorkBudget.Kind.MACHINE, pos, requestSteps(n));
            // A native callback may revoke one of several overlapping sources between extra steps.
            WorkTicks.advance(level, ticker, identity, work, () -> WorkSources.steamWork(level, identity)
                    && doublings(level, Vec3.atCenterOf(pos)) >= n && owners(level, pos).containsAll(owners), nativeTick);
        }));
    }
}
