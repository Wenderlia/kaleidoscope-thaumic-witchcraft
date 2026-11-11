package dev.senluo.witchcraft.time;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;

/** Portable fruit identities and fixed sites. Progress and paid TU belong only to FruitEntity. */
public final class FruitSites {
    public static final class Site {
        public final UUID identity;
        public final int grade;
        public BlockPos fruit;
        private Site(UUID identity, int grade, BlockPos fruit) {
            this.identity = identity; this.grade = grade; this.fruit = fruit;
        }
    }
    private final Map<BlockPos, Site> sites = new LinkedHashMap<>();
    public Site get(BlockPos source) { return sites.get(source); }
    public Site ensure(BlockPos source, int grade) {
        Site current = sites.get(source);
        if (current != null && current.grade == grade) return current;
        if (current == null && sites.size() >= TreeShapeSpace.MAX_PARTS) return null;
        Site next = new Site(UUID.randomUUID(), grade, null);
        sites.put(source.immutable(), next);
        return next;
    }
    void rotate(int turns) {
        Map<BlockPos, Site> rotated = new LinkedHashMap<>();
        sites.forEach((source, site) -> {
            if (site.fruit != null) site.fruit = TreeOrientation.rotate(site.fruit, turns);
            rotated.put(TreeOrientation.rotate(source, turns), site);
        });
        sites.clear(); sites.putAll(rotated);
    }
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); tag.putInt("Version", 1);
        ListTag entries = new ListTag();
        sites.forEach((source, site) -> {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Source", source.asLong()); entry.putUUID("Identity", site.identity);
            entry.putInt("Grade", site.grade);
            if (site.fruit != null) entry.putLong("Fruit", site.fruit.asLong());
            entries.add(entry);
        });
        tag.put("Sites", entries); return tag;
    }
    private static boolean bounded(BlockPos pos) {
        // Includes the natural giant crown and the fruit one block below its supporting leaf.
        return Math.abs((long)pos.getX()) <= 16 && Math.abs((long)pos.getZ()) <= 16
                && pos.getY() >= -5 && pos.getY() <= 24;
    }
    public static boolean readable(CompoundTag tag) {
        if (tag.isEmpty()) return true;
        if (tag.getInt("Version") != 1 || !(tag.get("Sites") instanceof ListTag list)
                || list.size() > TreeShapeSpace.MAX_PARTS || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) return false;
        Set<Long> sources = new HashSet<>(); Set<UUID> identities = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            BlockPos source = BlockPos.of(entry.getLong("Source")); int grade = entry.getInt("Grade");
            if (!entry.contains("Source", Tag.TAG_LONG) || !bounded(source) || !sources.add(source.asLong())
                    || !entry.hasUUID("Identity") || !identities.add(entry.getUUID("Identity"))
                    || grade < 1 || grade > 5 || (grade == 5) != source.equals(BlockPos.ZERO)) return false;
            if (entry.contains("Fruit") && (!entry.contains("Fruit", Tag.TAG_LONG)
                    || !bounded(BlockPos.of(entry.getLong("Fruit"))))) return false;
        }
        return true;
    }
    public void load(CompoundTag tag) {
        sites.clear();
        if (!readable(tag)) return;
        var list = tag.getList("Sites", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            var entry = list.getCompound(i);
            sites.put(BlockPos.of(entry.getLong("Source")), new Site(entry.getUUID("Identity"), entry.getInt("Grade"),
                    entry.contains("Fruit") ? BlockPos.of(entry.getLong("Fruit")) : null));
        }
    }
}
