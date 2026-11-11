package dev.senluo.witchcraft.time;

/** Ordinary attached roots and the transition into independent great-tree production. */
public final class GiantRoots {
    public static final int COUNT = 24;
    public static final long CAPACITY = 1200L * Tissue.TU + Tissue.ROOT_CAP[4] + TreeBalance.CAPACITY_BONUS;
    public static final int MAX_NODES = Tissue.MAX_NODES + COUNT;

    private GiantRoots() {}

    /** World lifecycle only. Reading a tag or selecting a root must never call this. */
    public static boolean restore(Tissue mother) {
        if (!mother.supported()) return false;
        boolean changed = synchronize(mother);
        if (mother.giant && mother.tree) {
            return initializeGreatTree(mother) || changed;
        }
        int limit = mother.giant && mother.tree ? MAX_NODES : Tissue.MAX_NODES;
        while (mother.sides.size() < mother.slots() && mother.nodeCount() < limit) {
            mother.sides.add(fresh(mother));
            changed = true;
        }
        return changed;
    }

    /** Initialize production when a living tree reaches the great-tree form; never mint stock. */
    static boolean initializeGreatTree(Tissue mother) {
        if (!mother.supported() || !mother.giant || !mother.tree || mother.rootProduction != null) return false;
        synchronize(mother);
        mother.rootProduction = RootProduction.fromRoots(mother);
        return true;
    }

    /** Follow the direct parent's preceding capacity stage without creating tissue or stock. */
    public static boolean synchronize(Tissue mother) {
        if (!mother.supported()) return false;
        boolean changed = false;
        if (mother.rootProduction != null) {
            changed |= inheritStage(mother, mother.rootProduction.output().tissue);
            changed |= synchronize(mother.rootProduction.output().tissue);
        }
        for (Tissue.Side side : mother.sides) {
            changed |= inheritStage(mother, side.tissue);
            changed |= synchronize(side.tissue);
        }
        return changed;
    }

    private static boolean inheritStage(Tissue mother, Tissue child) {
        if (!child.supported() || child.tree || child.treeRoot) return false;
        boolean treeRoot = mother.giant && mother.tree;
        int stage = treeRoot || mother.tree || mother.treeRoot ? 4 : Math.max(0, mother.rootStage - 1);
        long target = 1200L * Tissue.TU + Tissue.ROOT_CAP[stage];
        // Prior upgrades and grafts may already exceed the natural target; never downgrade them.
        if (!treeRoot && target <= child.birthCapacity + Tissue.ROOT_CAP[child.rootStage]) return false;
        child.birthCapacity = 1200L * Tissue.TU;
        child.rootStage = stage;
        child.treeRoot = treeRoot;
        return true;
    }

    static Tissue.Side fresh(Tissue mother) {
        Tissue.Side side = new Tissue.Side(1);
        side.tissue.bond = mother.bond;
        side.tissue.inheritProduction(mother);
        inheritStage(mother, side.tissue);
        return side;
    }

    /** The caller checks maturity/permission, equalizes once, then returns this detached account. */
    static Tissue harvest(Tissue mother, Tissue.Side picked) {
        if (mother.giant) return mother.rootProduction != null && picked == mother.rootProduction.output()
                ? mother.rootProduction.harvest(mother) : null;
        int index = mother.sides.indexOf(picked);
        if (index < 0 || !picked.mature()) return null;
        Tissue.Side replacement = fresh(mother);
        mother.sides.set(index, replacement);
        picked.tissue.inheritProduction(mother);
        picked.tissue.naturalTicks = 0; // A cut root begins its own reproductive/wood age.
        return picked.tissue;
    }

    public static Tissue harvest(Tissue mother) {
        return harvest(mother, mother.selectedSide());
    }
}
