package dev.senluo.witchcraft.time;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The accounting model uses integer milli-TU. It has no world clock or offline income. */
public final class Tissue {
    public static final long TU = 1_000L;
    public static final long MAX_VALUE = 1_000_000_000_000L;
    public static final int ROOT_TICKS = 6 * 60 * 20;
    public static final long[] ROOT_FEE = {0, 0, 0, 0, 0};
    public static final long[] ROOT_CAP = {0, 4800*TU, 22800*TU, 94800*TU, 382800*TU};
    public static final int[] SLOTS = {6, 6, 6, 6, 6};
    public static final long[] SIDE_CAP = {0, 1200*TU, 2400*TU, 4800*TU, 9600*TU};
    public static final long[] SIDE_FEE = {0, 0, 0, 0, 0};
    public static final int[] SIDE_TIME = {0, ROOT_TICKS, ROOT_TICKS, ROOT_TICKS, ROOT_TICKS};
    public static final long[] AMBER_FEE = {0, 0, 0, 0};
    public static final long[] AMBER_CAP = {0, 4800*TU, 48000*TU, 480000*TU};
    public static final int[] AMBER_TIME = {0, 6000, 12000, 24000};
    public static final int MAX_NODES = 128;
    public static final int MAX_DEPTH = 8;

    private net.minecraft.nbt.CompoundTag preservedData;

    public boolean supported() { return preservedData == null; }
    void preserve(net.minecraft.nbt.CompoundTag tag) { preservedData = tag.copy(); }
    net.minecraft.nbt.CompoundTag preservedData() { return preservedData == null ? null : preservedData.copy(); }

    public long birthCapacity = 1200*TU;
    private long quantity; // This core only; attached side cores own their balances.
    private record BalanceWeight(int total, int unlocked) {}
    // Cached colony views observe our own writes. Short-lived item/tooltip views do not subscribe.
    private java.util.Map<TimeStorage, BalanceWeight> balanceViews;
    public long coreQuantity() { return quantity; }
    public void setCoreQuantity(long amount) {
        long delta = amount - quantity;
        if (delta == 0) return;
        quantity = amount;
        if (balanceViews != null) balanceViews.forEach((view, weight) ->
                view.balanceChanged(delta, weight.total, weight.unlocked));
    }
    void watchBalance(TimeStorage view, boolean locked) {
        if (balanceViews == null) balanceViews = new java.util.WeakHashMap<>();
        balanceViews.merge(view, new BalanceWeight(1, locked ? 0 : 1),
                (a, b) -> new BalanceWeight(a.total + b.total, a.unlocked + b.unlocked));
    }
    void unwatchBalance(TimeStorage view) {
        if (balanceViews != null) {
            balanceViews.remove(view);
            if (balanceViews.isEmpty()) balanceViews = null;
        }
    }
    public UUID identity = UUID.randomUUID();
    public dev.senluo.witchcraft.ritual.TreeBond bond;
    public long ritualCapacity;
    public boolean tree;
    public boolean treeRoot;
    public boolean giant;
    public boolean giantGrowing;
    public int giantTicks;
    public int treeFacing = 2;
    public int disabledSources = 31;
    public int selectedSource; // 0..4 are absorption sources; production is a separate interaction.
    public int productionMode = SupplyMode.NORMAL;
    public long productionChosenAt = Long.MIN_VALUE;
    public UUID productionChoice;
    public int naturalTicks;
    public final java.util.Set<Long> formedTree = new java.util.HashSet<>();
    public final java.util.Set<Long> formedLogs = new java.util.HashSet<>();
    public final TreeShapeSpace shapeSpace = new TreeShapeSpace();
    public final FruitSites fruitSites = new FruitSites();
    public final double[] incomeRemainders = new double[5];
    public boolean treeGrowing;
    public int treeTicks;
    public int rootStage;
    public int amberStage;
    public boolean training;
    public int trainingTicks;
    public Growth growth;
    public final List<Side> sides = new ArrayList<>(6);
    public int selectedSide;
    public RootProduction rootProduction;

    public boolean established() { return growth == null || growth.mature(); }
    public long coreCapacity() {
        return coreCapacity(false);
    }
    public long matureCoreCapacity() { return coreCapacity(true); }
    private long coreCapacity(boolean mature) {
        if (!supported()) return 0;
        long base = treeRoot && !tree ? GiantRoots.CAPACITY : birthCapacity + ROOT_CAP[rootStage]
                + (tree ? TreeBalance.CAPACITY_BONUS : 0) + (giant ? TreeBalance.GIANT_CAPACITY_BONUS : 0);
        // A young side's inherited stage is its mature target, not an immediately available store.
        if (!mature && !established()) base = Math.max(TU, base * growth.ticks / ROOT_TICKS);
        return Math.min(MAX_VALUE, base + AMBER_CAP[amberStage] + ritualCapacity)
                + (rootProduction == null ? 0 : rootProduction.reserveCapacity());
    }
    public long receiveRitual(long amount) {
        if (!supported()) return 0;
        long nativeCapacity = coreCapacity() - (rootProduction == null ? 0 : rootProduction.reserveCapacity());
        long accepted = Math.max(0, Math.min(amount, MAX_VALUE - nativeCapacity));
        ritualCapacity += accepted;
        setCoreQuantity(quantity + accepted);
        return accepted;
    }
    public int slots() { return giant ? 0 : SLOTS[rootStage]; }
    /** The six direct roots qualify the next form; nested tissue and storage fullness do not. */
    public boolean readyForGiant() {
        return supported() && tree && !giant && bond != null && bond.supported()
                && sides.size() == SLOTS[4]
                && sides.stream().allMatch(side -> side.tissue.supported() && side.mature());
    }
    public boolean maturePlant() { return rootStage == 4 || tree; }
    public int radius() { return giant ? 40 : tree ? 24 : maturePlant() ? 12 : 4; }
    public int harvestAge() {
        if (tree) return 4;
        int age = 0;
        for (Side side : sides) if (!side.locked) {
            if (side.mature()) return 4;
            age = Math.max(age, Math.min(3, side.tissue.growth.ticks * 4 / ROOT_TICKS));
        }
        return age;
    }
    public boolean sourceEnabled(int source) { return supported() && (disabledSources & (1 << source)) == 0; }
    public long stored() { return TimeStorage.of(this).quantity(); }

    public long capacity() {
        long cap = coreCapacity();
        for (Side side : sides) cap += side.capacity();
        if (rootProduction != null) cap += rootProduction.output().capacity();
        return cap;
    }

    public long reserved() {
        return TimeStorage.of(this).reserved();
    }

    public long spendable() { return TimeStorage.of(this).spendable(); }
    public long freeCapacity() { return TimeStorage.of(this).freeCapacity(); }
    public long room() { return TimeStorage.of(this).room(); }
    public boolean credit(long amount) { return TimeStorage.of(this).credit(amount); }

    public Side selectedSide() {
        if (giant) return rootProduction == null ? null : rootProduction.output();
        if (sides.isEmpty()) return null;
        int index = selectedSideIndex();
        return index < sides.size() ? sides.get(index) : null;
    }

    /** Every action and presentation uses the same public root on a great tree. */
    public int selectedSideIndex() {
        if (giant) return rootProduction == null ? -1 : 0;
        if (sides.isEmpty()) return -1;
        return Math.floorMod(selectedSide, sides.size());
    }

    public boolean acceptsBond(dev.senluo.witchcraft.ritual.TreeBond incoming) {
        return supported() && incoming != null && incoming.supported()
                && (bond == null || bond.belongsTo(incoming.player()))
                && sides.stream().allMatch(side -> side.tissue.acceptsBond(incoming))
                && (rootProduction == null || rootProduction.output().tissue.acceptsBond(incoming));
    }

    /** The caller checks the whole tissue before committing, so a foreign graft is never overwritten. */
    public void inheritBond(dev.senluo.witchcraft.ritual.TreeBond incoming) {
        bond = incoming;
        for (Side side : sides) side.tissue.inheritBond(incoming);
        if (rootProduction != null) rootProduction.output().tissue.inheritBond(incoming);
    }

    public int nodeCount() { return 1 + sides.stream().mapToInt(s -> s.tissue.nodeCount()).sum()
            + (rootProduction == null ? 0 : rootProduction.output().tissue.nodeCount()); }
    public int depth() { return 1 + Math.max(sides.stream().mapToInt(s -> s.tissue.depth()).max().orElse(0),
            rootProduction == null ? 0 : rootProduction.output().tissue.depth()); }

    /** A separated root keeps the policy that governed it, without inventing a new explicit choice. */
    public void inheritProduction(Tissue parent) {
        productionMode = parent.productionMode;
        productionChosenAt = parent.productionChosenAt;
        productionChoice = parent.productionChoice;
    }

    public List<Tissue> detachAll() {
        if (!supported()) return List.of(this);
        List<Tissue> detached = new ArrayList<>();
        detached.add(this);
        for (Side side : List.copyOf(sides)) {
            side.tissue.naturalTicks = 0;
            side.tissue.inheritProduction(this);
            detached.addAll(side.tissue.detachAll());
        }
        sides.clear();
        selectedSide = 0;
        return detached;
    }

    public void train(int hostGrade, int elapsed) {
        if (!training || amberStage == 3 || hostGrade < amberStage + 1) return;
        trainingTicks = Math.min(AMBER_TIME[amberStage + 1], trainingTicks + elapsed);
        if (trainingTicks == AMBER_TIME[amberStage + 1]) {
            amberStage++;
            trainingTicks = 0;
            training = false;
        }
    }

    public static long proportional(long part, long quantity, long capacity) {
        if (part <= 0 || quantity <= 0 || capacity <= 0) return 0;
        // Avoid floating-point rounding and overflow when a large colony is almost full.
        return BigInteger.valueOf(part).multiply(BigInteger.valueOf(quantity))
                .divide(BigInteger.valueOf(capacity)).min(BigInteger.valueOf(part)).longValueExact();
    }

    public static long bounded(long value) { return Math.max(0, Math.min(MAX_VALUE, value)); }
    public static int bounded(int value, int max) { return Math.max(0, Math.min(max, value)); }

    /** Development belongs to the tissue, so a detached immature side keeps its real progress. */
    public static final class Growth {
        public final int profile;
        public long invested;
        public int ticks;
        public boolean active;

        public Growth(int profile) { this.profile = Math.max(1, Math.min(4, profile)); }
        public long capacity() {
            return SIDE_CAP[profile] * ticks / SIDE_TIME[profile];
        }
        public long remainingFee() { return 0; }
        public int remainingTicks() { return Math.max(0, SIDE_TIME[profile] - ticks); }
        public void advance(int elapsed) { ticks = (int) Math.min(SIDE_TIME[profile], ticks + (long) Math.max(0, elapsed)); }
        public boolean mature() { return remainingFee() == 0 && remainingTicks() == 0; }
    }

    public static final class Side {
        public Tissue tissue;
        public boolean locked;

        public Side(int profile) {
            tissue = new Tissue();
            tissue.growth = new Growth(profile);
            tissue.birthCapacity = SIDE_CAP[tissue.growth.profile];
        }
        public static Side graft(Tissue tissue) { Side side = new Side(1); side.tissue = tissue; return side; }
        public long capacity() { return tissue.capacity(); }
        public long stored() { return tissue.stored(); }
        public boolean mature() { return tissue.established(); }
        public boolean active() { return tissue.growth != null && tissue.growth.active; }
        public long remainingFee() { return tissue.growth == null ? 0 : tissue.growth.remainingFee(); }
        public int remainingTicks() { return tissue.growth == null ? 0 : tissue.growth.remainingTicks(); }
        public void advance(int elapsed) { if (tissue.growth != null) tissue.growth.advance(elapsed); }
        public void invest(long amount) { if (tissue.growth != null) tissue.growth.invested += amount; }
    }
}
