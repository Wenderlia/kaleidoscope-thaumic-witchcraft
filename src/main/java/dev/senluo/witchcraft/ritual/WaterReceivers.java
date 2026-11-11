package dev.senluo.witchcraft.ritual;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** Opt-in water semantics; a generic tank accepting water is not automatically a ritual recipient. */
public final class WaterReceivers {
    public static final TagKey<Block> RECEIVERS = tag("ritual_water_receivers");
    public static final TagKey<Block> WHOLE_BUCKET = tag("ritual_whole_bucket_water_receivers");
    private static final List<Adapter> ADAPTERS = new ArrayList<>();
    private WaterReceivers() {}
    private static TagKey<Block> tag(String path) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft", path));
    }
    public enum Result { FILLED, UNCHANGED, UNCERTAIN }
    public interface Fill {
        int cost();
        /** Non-fluid recipients return the unchanged fractional credit. */
        int nextThirds();
        boolean ready();
        Result commit();
    }
    public interface Adapter {
        boolean matches(BlockState state);
        Optional<Fill> quote(ServerLevel level, BlockPos position, int thirds);
    }
    /** Register once during common setup, before levels load. The caller owns native sync and atomic filling. */
    public static synchronized void register(Adapter adapter) { ADAPTERS.add(java.util.Objects.requireNonNull(adapter)); }
    public static boolean candidate(BlockState state) {
        return state.is(Blocks.CAULDRON) || state.is(Blocks.WATER_CAULDRON)
                || state.is(RECEIVERS) || ADAPTERS.stream().anyMatch(a -> a.matches(state));
    }
    public static Optional<Fill> quote(ServerLevel level, BlockPos pos, int thirds) {
        if (!level.hasChunkAt(pos) || !level.shouldTickBlocksAt(pos)) return Optional.empty();
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.CAULDRON) || state.is(Blocks.WATER_CAULDRON)) {
            int height = state.is(Blocks.CAULDRON) ? 0 : state.getValue(LayeredCauldronBlock.LEVEL);
            if (height >= 3) return Optional.empty();
            return Optional.of(new Fill() {
                public int cost() { return 1; }
                public int nextThirds() { return thirds; }
                public boolean ready() { return level.hasChunkAt(pos) && level.getBlockState(pos).equals(state); }
                public Result commit() {
                    if (!ready()) return Result.UNCHANGED;
                    return level.setBlock(pos, Blocks.WATER_CAULDRON.defaultBlockState()
                            .setValue(LayeredCauldronBlock.LEVEL, height + 1), Block.UPDATE_ALL) ? Result.FILLED : Result.UNCHANGED;
                }
            });
        }
        for (Adapter adapter : ADAPTERS) if (adapter.matches(state)) {
            var quote = adapter.quote(level, pos, thirds);
            if (quote.isPresent()) return quote;
        }
        if (!state.is(RECEIVERS)) return Optional.empty();
        // Null-face and sided-only providers both work. Do not cache live capability objects.
        var faces = new ArrayList<Direction>(); faces.add(null); faces.addAll(List.of(Direction.values()));
        for (Direction face : faces) {
            IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, face);
            if (handler == null) continue;
            for (int units : state.is(WHOLE_BUCKET) ? new int[]{3} : new int[]{1, 3}) {
                var amount = RitualWater.quote(units, thirds);
                if (!canFill(handler, amount.millibuckets())) continue;
                var entity = level.getBlockEntity(pos);
                return Optional.of(new Fill() {
                    public int cost() { return amount.essentia(); }
                    public int nextThirds() { return amount.nextThirds(); }
                    public boolean ready() {
                        if (!level.hasChunkAt(pos) || !level.getBlockState(pos).equals(state)
                                || level.getBlockEntity(pos) != entity) return false;
                        var current = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, face);
                        return current != null && canFill(current, amount.millibuckets());
                    }
                    public Result commit() {
                        if (!ready()) return Result.UNCHANGED;
                        var current = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, face);
                        if (current == null) return Result.UNCHANGED;
                        int filled = current.fill(new FluidStack(Fluids.WATER, amount.millibuckets()), IFluidHandler.FluidAction.EXECUTE);
                        return filled == amount.millibuckets() ? Result.FILLED : filled == 0 ? Result.UNCHANGED : Result.UNCERTAIN;
                    }
                });
            }
        }
        return Optional.empty();
    }
    private static boolean canFill(IFluidHandler handler, int amount) {
        long room = 0;
        var water = new FluidStack(Fluids.WATER, amount);
        int tanks = handler.getTanks();
        if (tanks < 1 || tanks > 256) return false;
        for (int i = 0; i < tanks; i++) {
            FluidStack fluid = handler.getFluidInTank(i);
            if ((fluid.isEmpty() || fluid.getFluid().isSame(Fluids.WATER)) && handler.isFluidValid(i, water))
                room += Math.max(0L, (long)handler.getTankCapacity(i) - fluid.getAmount());
        }
        return room >= amount && handler.fill(water, IFluidHandler.FluidAction.SIMULATE) == amount;
    }
}
