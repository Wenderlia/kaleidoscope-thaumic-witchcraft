package dev.senluo.witchcraft.time;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;

/** Structure-owned crop reach. Discovery and membership consume the same geometry. */
final class CropSupplyRange {
    static final CropSupplyRange ROOT = new CropSupplyRange(vertical(1, 3));
    static final CropSupplyRange BODY = new CropSupplyRange(vertical(0, 2));
    static final CropSupplyRange WOOD = new CropSupplyRange(wood());

    private final List<BlockPos> offsets;
    private final int minX, maxX, minZ, maxZ;

    private CropSupplyRange(Set<BlockPos> offsets) {
        this.offsets = List.copyOf(offsets);
        minX = offsets.stream().mapToInt(BlockPos::getX).min().orElseThrow();
        maxX = offsets.stream().mapToInt(BlockPos::getX).max().orElseThrow();
        minZ = offsets.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        maxZ = offsets.stream().mapToInt(BlockPos::getZ).max().orElseThrow();
    }

    boolean covers(Set<BlockPos> sources, BlockPos target) {
        if (sources.isEmpty()) return false;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (BlockPos offset : offsets)
            if (sources.contains(probe.set(target.getX() - offset.getX(),
                    target.getY() - offset.getY(), target.getZ() - offset.getZ()))) return true;
        return false;
    }

    void watchChunks(BlockPos source, LongConsumer watch) {
        for (int x = (source.getX() + minX) >> 4; x <= (source.getX() + maxX) >> 4; x++)
            for (int z = (source.getZ() + minZ) >> 4; z <= (source.getZ() + maxZ) >> 4; z++)
                watch.accept(ChunkPos.asLong(x, z));
    }

    private static Set<BlockPos> vertical(int bottom, int top) {
        Set<BlockPos> offsets = new LinkedHashSet<>();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            for (int y = bottom; y <= top; y++) offsets.add(new BlockPos(x, y, z));
        return offsets;
    }

    private static Set<BlockPos> wood() {
        Set<BlockPos> offsets = new LinkedHashSet<>();
        for (Direction direction : Direction.values())
            for (int distance = 1; distance <= 3; distance++)
                for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
                    BlockPos center = BlockPos.ZERO.relative(direction, distance);
                    offsets.add(switch (direction.getAxis()) {
                        case X -> center.offset(0, a, b);
                        case Y -> center.offset(a, 0, b);
                        case Z -> center.offset(a, b, 0);
                    });
                }
        return offsets;
    }
}
