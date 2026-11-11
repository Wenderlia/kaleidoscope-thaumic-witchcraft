package dev.senluo.witchcraft.compat.thaumcraft;

import dev.tc4port.thaumcraft.api.aspect.AspectId;
import dev.tc4port.thaumcraft.api.essentia.*;
import dev.tc4port.thaumcraft.api.aspect.AspectAmounts;
import dev.tc4port.thaumcraft.api.machine.CrucibleView;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/** Inspection is separate from transfer permission. No insertion probe, remote search or retained capability. */
public final class EssentiaStores {
    public static EssentiaStorageView at(ServerLevel level, BlockPos pos, Direction face) {
        return EssentiaApi.storage(level, pos, face);
    }
    public static BlockEntity pot(BlockEntity candidate) {
        return candidate != null && !candidate.isRemoved()
                && candidate.getType() == dev.tc4port.thaumcraft.api.ThaumcraftContent.blockEntity("crucible") ? candidate : null;
    }
    public static Optional<CrucibleView> crucible(BlockEntity pot) {
        if (!(pot.getLevel() instanceof ServerLevel level) || pot.isRemoved()) return Optional.empty();
        return at(level, pot.getBlockPos(), null) instanceof CrucibleView view ? Optional.of(view) : Optional.empty();
    }
    public static int total(AspectAmounts contents) {
        return (int)Math.min(Integer.MAX_VALUE, contents.amounts().values().stream().mapToLong(Integer::longValue).sum());
    }
    static boolean hasWater(BlockEntity pot) {
        if (!(pot.getLevel() instanceof ServerLevel level) || pot.isRemoved()) return false;
        var tanks = level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK,
                pot.getBlockPos(), pot.getBlockState(), pot, null);
        if (tanks == null) return false;
        for (int i = 0; i < tanks.getTanks(); i++) {
            var fluid = tanks.getFluidInTank(i);
            if (!fluid.isEmpty() && fluid.getFluid().is(net.minecraft.tags.FluidTags.WATER)) return true;
        }
        return false;
    }
    public static boolean stocked(EssentiaStorageView store, Direction face, AspectId aspect) {
        return store != null && store.essentiaContents(face).amounts().getOrDefault(aspect, 0) > 0;
    }
    public static int limit(ServerLevel level, BlockPos pos, EssentiaStorageView store, Direction face, AspectId aspect, int request) {
        if (store == null) return request;
        var filter = store.essentiaFilter();
        if (filter != null && !filter.equals(aspect)) return 0;
        int capacity = store.essentiaCapacity();
        if (capacity <= 0) return request; // Unknown/unbounded is NOT empty or full.
        var contents = store.essentiaContents(face).amounts();
        long used = store.sharedEssentiaCapacity() || level.getBlockState(pos).is(dev.tc4port.thaumcraft.api.ThaumcraftTags.ESSENTIA_JARS)
                ? contents.values().stream().filter(n -> n > 0).mapToLong(Integer::longValue).sum()
                : Math.max(0, contents.getOrDefault(aspect, 0));
        return (int)Math.min(request, Math.max(0L, capacity - used));
    }
    private EssentiaStores() {}
}
