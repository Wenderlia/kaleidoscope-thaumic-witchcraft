package dev.senluo.witchcraft.compat.thaumcraft.client;

import dev.tc4port.thaumcraft.api.client.ThaumcraftClientEffectsApi;
import dev.tc4port.thaumcraft.api.essentia.EssentiaApi;
import dev.tc4port.thaumcraft.api.machine.CrucibleView;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.Vec3;

/** Observe only a boiling crucible's own client update. Core ticking and ordinary bubbles remain native. */
public final class CrucibleRim {
    private static final ResourceLocation CRUCIBLE = ResourceLocation.fromNamespaceAndPath("thaumcraft", "crucible");
    private static final ThreadLocal<CrucibleRim> ACTIVE = new ThreadLocal<>();
    private final BlockPos pos;
    private final boolean overflow;
    private Class<?> surfaceParticle;
    private boolean emittedRim;

    private CrucibleRim(BlockPos pos, CrucibleView view) {
        this.pos = pos;
        long amount = view.essentiaContents(null).amounts().values().stream().mapToLong(Integer::longValue).sum();
        overflow = amount > view.stableCapacity();
    }

    public static <T extends BlockEntity> BlockEntityTicker<T> observe(BlockEntityTicker<T> ticker, BlockEntityType<T> type) {
        if (ticker == null || !CRUCIBLE.equals(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type))) return ticker;
        return (level, pos, state, entity) -> {
            if (!(level instanceof ClientLevel client)
                    || !(EssentiaApi.storage(client, pos, null) instanceof CrucibleView view) || !view.boiling()) {
                ticker.tick(level, pos, state, entity);
                return;
            }
            var previous = ACTIVE.get();
            var rim = new CrucibleRim(pos, view);
            ACTIVE.set(rim);
            try {
                ticker.tick(level, pos, state, entity);
            } finally {
                if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
            }
            if (rim.overflow && rim.surfaceParticle != null && !rim.emittedRim && !entity.isRemoved())
                rim.supplement(client);
        };
    }

    public static boolean suppress(Particle particle) {
        var rim = ACTIVE.get();
        if (rim == null) return false;
        Vec3 at = particle.getPos();
        if (rim.surfaceParticle == null) {
            // Core emits surface froth before rim and coloured bubbles. Its native coordinates
            // use float arithmetic, which may round an inset to a block edge far from spawn.
            if (inSurfaceSpan(at.x, rim.pos.getX()) && inSurfaceSpan(at.z, rim.pos.getZ())
                    && at.y >= rim.pos.getY() && at.y <= rim.pos.getY() + 1.01)
                rim.surfaceParticle = particle.getClass();
            return false;
        }
        if (particle.getClass() != rim.surfaceParticle || at.y != rim.pos.getY()+1) return false;
        int x = rim.pos.getX(), z = rim.pos.getZ();
        boolean edge = (at.x == x || at.x == x + 1) && inEdgeSpan(at.z, z)
                || (at.z == z || at.z == z + 1) && inEdgeSpan(at.x, x);
        if (!edge) return false;
        rim.emittedRim = true;
        return !rim.overflow;
    }

    private static boolean inSurfaceSpan(double coordinate, int start) {
        float minimum = start + 0.2F;
        return coordinate >= minimum && coordinate <= minimum + 0.6F;
    }

    private static boolean inEdgeSpan(double coordinate, int start) {
        return coordinate >= (float)start && coordinate <= (float)start + 1F;
    }

    private void supplement(ClientLevel level) {
        double x = pos.getX(), y = pos.getY()+1, z = pos.getZ();
        for (int i = 0; i < 2; i++) {
            ThaumcraftClientEffectsApi.spawnFroth(level, new Vec3(x, y, z+level.random.nextFloat()), true);
            ThaumcraftClientEffectsApi.spawnFroth(level, new Vec3(x+1, y, z+level.random.nextFloat()), true);
            ThaumcraftClientEffectsApi.spawnFroth(level, new Vec3(x+level.random.nextFloat(), y, z), true);
            ThaumcraftClientEffectsApi.spawnFroth(level, new Vec3(x+level.random.nextFloat(), y, z+1), true);
        }
    }
}
