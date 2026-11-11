package dev.senluo.witchcraft.time;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Validate the current portable organism before decoding its accounts. */
final class TissueSchema {
    static final int VERSION = 11;

    private TissueSchema() {}

    static boolean readable(CompoundTag tag) {
        // A genuinely new item has no tissue data. Nonempty unversioned data is not a new item.
        if (tag.isEmpty()) return true;
        int limit = tag.getBoolean("Tree") && tag.getBoolean("Giant") ? GiantRoots.MAX_NODES : Tissue.MAX_NODES;
        return readable(tag, 1, new int[]{limit});
    }

    private static boolean readable(CompoundTag tag, int depth, int[] remaining) {
        if (depth > Tissue.MAX_DEPTH || --remaining[0] < 0 || !tag.contains("Version", Tag.TAG_INT)) return false;
        int version = tag.getInt("Version");
        if (version != VERSION) return false;
        if (tag.contains("WoodMemory") && (!tag.contains("WoodMemory", Tag.TAG_COMPOUND)
                || !TreeShapeSpace.readable(tag.getCompound("WoodMemory")))) return false;
        if (tag.contains("FruitSites") && (!tag.contains("FruitSites", Tag.TAG_COMPOUND)
                || !FruitSites.readable(tag.getCompound("FruitSites")))) return false;
        if (tag.contains("RootProduction")) {
            if (depth != 1 || !tag.getBoolean("Tree") || !tag.getBoolean("Giant")
                    || !tag.contains("RootProduction", Tag.TAG_COMPOUND)) return false;
            CompoundTag production = tag.getCompound("RootProduction");
            if (tag.getInt("RootStage") != 4
                    || tag.contains("Growth") && (!tag.contains("Growth", Tag.TAG_COMPOUND)
                    || tag.getCompound("Growth").getInt("Ticks") < Tissue.ROOT_TICKS)) return false;
            if (!production.contains("Version", Tag.TAG_INT) || production.getInt("Version") != 1 || !production.contains("ReserveCapacity", Tag.TAG_LONG)
                    || production.getLong("ReserveCapacity") < 0 || production.getLong("ReserveCapacity") > RootProduction.MAX_RESERVE
                    || !production.contains("Cooldown", Tag.TAG_INT) || production.getInt("Cooldown") < 0
                    || production.getInt("Cooldown") > RootProduction.COOLDOWN
                    || !production.contains("Output", Tag.TAG_COMPOUND)
                    || !readable(production.getCompound("Output"), depth + 1, remaining)) return false;
            CompoundTag output = production.getCompound("Output");
            if (!output.getBoolean("TreeRoot") || output.getBoolean("Tree") || output.getBoolean("Giant")
                    || output.getInt("RootStage") != 4 || !output.getList("Sides", Tag.TAG_COMPOUND).isEmpty()) return false;
        }
        if (!tag.contains("Sides")) return true;
        if (!(tag.get("Sides") instanceof ListTag sides)
                || !sides.isEmpty() && sides.getElementType() != Tag.TAG_COMPOUND) return false;
        int slots = tag.contains("RootProduction") ? 0 : tag.getBoolean("Giant") ? GiantRoots.COUNT : 6;
        if (sides.size() > slots) return false;
        for (int i = 0; i < sides.size(); i++) {
            CompoundTag side = sides.getCompound(i);
            if (!side.contains("Tissue", Tag.TAG_COMPOUND)
                    || !readable(side.getCompound("Tissue"), depth + 1, remaining)) return false;
        }
        return true;
    }
}
