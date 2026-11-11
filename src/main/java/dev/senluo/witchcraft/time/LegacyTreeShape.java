package dev.senluo.witchcraft.time;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import dev.senluo.witchcraft.ritual.ChalkMarks;

/** One-way v6 input adapter. It has no live shape, edit operations or serializer. */
final class LegacyTreeShape {
    private static final int MAX_ENTRIES = TreeShapeSpace.MAX_ENTRIES;
    private static final int MAX_PRUNED = TreeShapeSpace.MAX_PRUNED;
    record Snapshot(Map<Long,BlockState> cells, Set<Long> excluded,
                    Map<Long,ChalkMarks.Mark> carvings, boolean initialized) {}
    private LegacyTreeShape() {}

    static Snapshot read(CompoundTag tag, Tissue tissue) {
        Map<Long,BlockState> cells = new LinkedHashMap<>();
        Set<Long> excluded = new HashSet<>();
        Map<Long,ChalkMarks.Mark> carvings = new LinkedHashMap<>();
        for (long p : tag.getLongArray("Pruned"))
            if (TreeShapeSpace.contains(tissue,BlockPos.of(p))) excluded.add(p);
        long[] positions = tag.getLongArray("Positions");
        byte[] axes = tag.getByteArray("Axes");
        var materials = tag.getList("Materials",Tag.TAG_STRING);
        for (int i=0; i<positions.length; i++) {
            long p = positions[i];
            if (excluded.contains(p) || !TreeShapeSpace.contains(tissue,BlockPos.of(p))) continue;
            var id = net.minecraft.resources.ResourceLocation.parse(materials.getString(i));
            BlockState state = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id).defaultBlockState();
            cells.put(p,FruitWoods.regrowth(state).setValue(RotatedPillarBlock.AXIS,axis(axes[i])));
        }
        positions = tag.getLongArray("LeafPositions");
        byte[] states = tag.getByteArray("LeafStates");
        for (int i=0; i<positions.length; i++) {
            long p = positions[i];
            if (excluded.contains(p) || !TreeShapeSpace.contains(tissue,BlockPos.of(p))) continue;
            cells.put(p,TemporalContent.TIME_LEAVES.get().naturalState()
                    .setValue(RotatedPillarBlock.AXIS,axis(states[i]/2))
                    .setValue(LeavesBlock.PERSISTENT,(states[i]&1)!=0)
                    .setValue(TreeLeavesBlock.GREAT_TREE,tissue.giant));
        }
        for (var value : tag.getList("Carvings",Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag)value; long p = e.getLong("Position");
            if (!excluded.contains(p) && (p==BlockPos.ZERO.asLong() || TreeShapeSpace.contains(tissue,BlockPos.of(p))))
                carvings.put(p,new ChalkMarks.Mark(0,e.getInt("Rotation"),1,e.getInt("Design")));
        }
        return new Snapshot(cells,excluded,carvings,tag.getBoolean("Initialized"));
    }
    private static Direction.Axis axis(int code) {
        return switch(code) { case 0 -> Direction.Axis.X; case 2 -> Direction.Axis.Z; default -> Direction.Axis.Y; };
    }

    static boolean readable(CompoundTag tag) {
        if (tag.isEmpty()) return true; // A plant without formed wood has no structure memory.
        if (!tag.contains("Version", net.minecraft.nbt.Tag.TAG_INT) || tag.getInt("Version") != 6
                || !tag.contains("Positions", net.minecraft.nbt.Tag.TAG_LONG_ARRAY)
                || !tag.contains("Axes", net.minecraft.nbt.Tag.TAG_BYTE_ARRAY)
                || !tag.contains("Pruned", net.minecraft.nbt.Tag.TAG_LONG_ARRAY)) return false;
        long[] positions = tag.getLongArray("Positions");
        byte[] axes = tag.getByteArray("Axes");
        if (positions.length != axes.length || positions.length > MAX_ENTRIES
                || tag.getLongArray("Pruned").length > MAX_PRUNED) return false;
        for (byte axis : axes) if (axis < 0 || axis > 2) return false;
        {
            if (!(tag.get("Materials") instanceof net.minecraft.nbt.ListTag list) || list.size() != positions.length
                    || !list.isEmpty() && list.getElementType() != net.minecraft.nbt.Tag.TAG_STRING) return false;
            for (int i = 0; i < list.size(); i++) {
                var id = net.minecraft.resources.ResourceLocation.tryParse(list.getString(i));
                if (id == null || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(id)) return false;
                // Persist only the wood contract, never arbitrary external state or block entities.
                var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);
                if (!(block instanceof TreePartBlock)) return false;
            }
        }
        {
            if (!tag.contains("LeafPositions", net.minecraft.nbt.Tag.TAG_LONG_ARRAY)
                    || !tag.contains("LeafStates", net.minecraft.nbt.Tag.TAG_BYTE_ARRAY)) return false;
            long[] leafPositions = tag.getLongArray("LeafPositions");
            byte[] leafStates = tag.getByteArray("LeafStates");
            if (leafPositions.length != leafStates.length || positions.length + leafPositions.length > MAX_ENTRIES) return false;
            Set<Long> seen = new HashSet<>();
            for (long pos : positions) if (!seen.add(pos)) return false;
            for (int i = 0; i < leafPositions.length; i++)
                if (!seen.add(leafPositions[i]) || leafStates[i] < 0 || leafStates[i] > 5) return false;
        }
        {
            if (!(tag.get("Carvings") instanceof net.minecraft.nbt.ListTag list) || list.size() > MAX_PRUNED
                    || !list.isEmpty() && list.getElementType() != net.minecraft.nbt.Tag.TAG_COMPOUND) return false;
            Set<Long> seen = new HashSet<>();
            for (int i = 0; i < list.size(); i++) {
                var entry = list.getCompound(i);
                if (!entry.contains("Position", net.minecraft.nbt.Tag.TAG_LONG) || !entry.contains("Rotation", net.minecraft.nbt.Tag.TAG_INT)
                        || entry.getInt("Rotation") < 0 || entry.getInt("Rotation") > 3
                        || (!entry.contains("Design", net.minecraft.nbt.Tag.TAG_INT)
                            || entry.getInt("Design") < 0 || entry.getInt("Design") >= dev.senluo.witchcraft.ritual.ChalkPattern.CARVING_ATLAS_DESIGNS)
                        || !seen.add(entry.getLong("Position"))) return false;
            }
        }
        return true;
    }

}
