package dev.senluo.witchcraft.time;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Current-format portable tissue trees and their accounting state. */
public final class TissueTag {
    private TissueTag() {}

    public static CompoundTag save(Tissue tissue) {
        if (!tissue.supported()) return tissue.preservedData();
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", TissueSchema.VERSION);
        if (tissue.rootProduction != null) tag.put("RootProduction", tissue.rootProduction.save());
        if (tissue.bond != null) tag.put("Bond", tissue.bond.save());
        tag.putLong("RitualCapacity", Tissue.bounded(tissue.ritualCapacity));
        tag.putBoolean("TreeRoot", tissue.treeRoot);
        tag.putBoolean("Giant", tissue.giant);
        tag.putBoolean("GiantGrowing", tissue.giantGrowing);
        tag.putInt("GiantTicks", tissue.giantTicks);
        tag.putInt("TreeFacing", tissue.treeFacing);
        tag.putInt("DisabledSources", tissue.disabledSources);
        tag.putInt("SelectedSource", tissue.selectedSource);
        tag.putInt("NaturalTicks", tissue.naturalTicks);
        tag.putInt("ProductionMode", tissue.productionMode);
        tag.putLong("ProductionChosenAt", tissue.productionChosenAt);
        if (tissue.productionChoice != null) tag.putUUID("ProductionChoice", tissue.productionChoice);
        tag.putLongArray("FormedTree", tissue.formedTree.stream().mapToLong(Long::longValue).toArray());
        tag.putLongArray("FormedLogs", tissue.formedLogs.stream().mapToLong(Long::longValue).toArray());
        if (tissue.tree) {
            tag.put("WoodMemory", tissue.shapeSpace.save());
            tag.put("FruitSites", tissue.fruitSites.save());
        }
        for (int i=0;i<5;i++) tag.putDouble("IncomeRemainder"+i,tissue.incomeRemainders[i]);
        tag.putUUID("Identity", tissue.identity);
        tag.putBoolean("Tree", tissue.tree);
        tag.putBoolean("TreeGrowing", tissue.treeGrowing);
        tag.putInt("TreeTicks", tissue.treeTicks);
        tag.putLong("BirthCapacity", tissue.birthCapacity);
        tag.putLong("Quantity", tissue.coreQuantity());
        tag.putInt("RootStage", tissue.rootStage);
        tag.putInt("AmberStage", tissue.amberStage);
        tag.putBoolean("Training", tissue.training);
        tag.putInt("TrainingTicks", tissue.trainingTicks);
        tag.putInt("SelectedSide", tissue.selectedSide);
        if (tissue.growth != null) {
            CompoundTag growth = new CompoundTag();
            growth.putInt("Profile", tissue.growth.profile);
            growth.putLong("Invested", tissue.growth.invested);
            growth.putInt("Ticks", tissue.growth.ticks);
            growth.putBoolean("Active", tissue.growth.active);
            tag.put("Growth", growth);
        }
        ListTag sides = new ListTag();
        for (Tissue.Side side : tissue.sides) {
            CompoundTag s = new CompoundTag();
            s.putBoolean("Locked", side.locked);
            s.put("Tissue", save(side.tissue));
            sides.add(s);
        }
        tag.put("Sides", sides);
        return tag;
    }

    public static Tissue load(CompoundTag tag) {
        if (!TissueSchema.readable(tag)) {
            Tissue tissue = new Tissue();
            tissue.preserve(tag);
            // Retain only recognition fields. No unknown account is available for spending or migration.
            tissue.tree = tag.getBoolean("Tree");
            tissue.bond = dev.senluo.witchcraft.ritual.TreeBond.read(tag.get("Bond"));
            if (tag.hasUUID("Identity")) tissue.identity = tag.getUUID("Identity");
            return tissue;
        }
        return load(tag, 1, new int[]{tag.getBoolean("Giant") && tag.getBoolean("Tree") ? GiantRoots.MAX_NODES : Tissue.MAX_NODES});
    }

    private static Tissue load(CompoundTag tag, int depth, int[] budget) {
        budget[0]--;
        Tissue tissue = new Tissue();
        if (!tag.contains("Version", Tag.TAG_INT)) return tissue;
        tissue.bond = dev.senluo.witchcraft.ritual.TreeBond.read(tag.get("Bond"));
        tissue.ritualCapacity = Tissue.bounded(tag.getLong("RitualCapacity"));
        if (tag.hasUUID("Identity")) tissue.identity = tag.getUUID("Identity");
        tissue.birthCapacity = Math.max(1, Math.min(9600*Tissue.TU, tag.getLong("BirthCapacity")));
        tissue.setCoreQuantity(Tissue.bounded(tag.getLong("Quantity")));
        tissue.rootStage = Tissue.bounded(tag.getInt("RootStage"), 4);
        tissue.amberStage = Tissue.bounded(tag.getInt("AmberStage"), 3);
        tissue.training = tissue.amberStage < 3 && tag.getBoolean("Training");
        tissue.trainingTicks = tissue.training
                ? Tissue.bounded(tag.getInt("TrainingTicks"), Tissue.AMBER_TIME[tissue.amberStage + 1]) : 0;
        tissue.selectedSide = Tissue.bounded(tag.getInt("SelectedSide"), 24);
        if (tag.contains("Growth", Tag.TAG_COMPOUND)) {
            tissue.growth = growth(tag.getCompound("Growth"));
        }
        tissue.tree = depth == 1 && tissue.established() && tissue.rootStage == 4 && tag.getBoolean("Tree");
        tissue.treeRoot = !tissue.tree && tag.getBoolean("TreeRoot");
        tissue.giant = tissue.tree && tag.getBoolean("Giant");
        tissue.giantGrowing = tissue.tree && !tissue.giant && tag.getBoolean("GiantGrowing");
        tissue.giantTicks = tissue.giantGrowing ? Tissue.bounded(tag.getInt("GiantTicks"),TreeBalance.GIANT_TICKS) : 0;
        tissue.treeFacing = tag.contains("TreeFacing") ? Math.max(2,Math.min(5,tag.getInt("TreeFacing"))) : 2;
        tissue.disabledSources = tag.getInt("DisabledSources") & 31;
        tissue.productionMode = tag.contains("ProductionMode") ? Tissue.bounded(tag.getInt("ProductionMode"), 2) : SupplyMode.NORMAL;
        tissue.productionChosenAt = tag.contains("ProductionChosenAt") ? tag.getLong("ProductionChosenAt") : Long.MIN_VALUE;
        tissue.productionChoice = tag.hasUUID("ProductionChoice") ? tag.getUUID("ProductionChoice") : null;
        int selectedSource = tag.getInt("SelectedSource");
        // Selection belongs to the five absorption sources; production has its own control.
        tissue.selectedSource = selectedSource >= 0 && selectedSource < 5 ? selectedSource : 0;
        long[] formed = tag.getLongArray("FormedTree");
        for (int i=0;tissue.tree && i<Math.min(formed.length,TreeShapeSpace.MAX_PARTS);i++) {
            var p=net.minecraft.core.BlockPos.of(formed[i]);
            if (Math.abs(p.getX())<=TreeShapeSpace.GIANT_RADIUS && Math.abs(p.getZ())<=TreeShapeSpace.GIANT_RADIUS && p.getY()>=-4 && p.getY()<=24) tissue.formedTree.add(formed[i]);
        }
        long[] logs=tag.getLongArray("FormedLogs");
        for(int i=0;tissue.tree&&i<Math.min(logs.length,TreeShapeSpace.MAX_PARTS);i++)if(tissue.formedTree.contains(logs[i]))tissue.formedLogs.add(logs[i]);
        tissue.shapeSpace.load(tag.getCompound("WoodMemory"), tissue);
        if (tissue.tree) tissue.fruitSites.load(tag.getCompound("FruitSites"));
        for (int i=0;i<5;i++) {
            double remainder=tag.getDouble("IncomeRemainder"+i);
            tissue.incomeRemainders[i]=Double.isFinite(remainder) ? Math.max(0,Math.min(0.999999999,remainder)) : 0;
        }
        tissue.treeGrowing = depth == 1 && !tissue.tree && tissue.established() && tissue.rootStage == 4 && tag.getBoolean("TreeGrowing");
        tissue.treeTicks = tissue.treeGrowing ? Tissue.bounded(tag.getInt("TreeTicks"), TreeBalance.ENTRY_TICKS) : 0;
        tissue.naturalTicks = Tissue.bounded(tag.getInt("NaturalTicks"), TreeBalance.GIANT_TICKS);
        ListTag sides = tag.getList("Sides", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(tissue.slots(), sides.size()) && depth < Tissue.MAX_DEPTH && budget[0] > 0; i++) {
            CompoundTag sideTag = sides.getCompound(i);
            Tissue.Side side = Tissue.Side.graft(load(sideTag.getCompound("Tissue"), depth + 1, budget));
            side.locked = sideTag.getBoolean("Locked");
            tissue.sides.add(side);
        }
        if (tag.contains("RootProduction", Tag.TAG_COMPOUND)) {
            CompoundTag production = tag.getCompound("RootProduction");
            tissue.rootProduction = RootProduction.load(production, load(production.getCompound("Output"), depth + 1, budget));
            tissue.setCoreQuantity(Math.max(0, Math.min(Tissue.MAX_VALUE + tissue.rootProduction.reserveCapacity(), tag.getLong("Quantity"))));

        }
        return tissue;
    }

    private static Tissue.Growth growth(CompoundTag tag) {
        Tissue.Growth growth = new Tissue.Growth(tag.getInt("Profile"));
        growth.invested = Tissue.bounded(tag.getLong("Invested"));
        growth.ticks = Tissue.bounded(tag.getInt("Ticks"), Tissue.SIDE_TIME[growth.profile]);
        growth.active = tag.getBoolean("Active");
        return growth;
    }
}
