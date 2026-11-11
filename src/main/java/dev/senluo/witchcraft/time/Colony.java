package dev.senluo.witchcraft.time;

import java.util.List;
import net.minecraft.core.BlockPos;
import java.util.Set;

/** A transient view over physical tissue balances, including attached side roots. */
public final class Colony {
    public final List<LivingEntity> members;
    public final List<BlockPos> roots;
    public final Set<BlockPos> rootSet;
    public final List<BlockPos> logs;
    long reserveTick = Long.MIN_VALUE;
    long reserveAmount;
    private int productionMode;
    private long modeChosenAt;
    private java.util.UUID modeChoice;
    private List<Tissue> storageRoots = List.of();
    private TimeStorage storageView;
    public Colony(List<LivingEntity> members, List<BlockPos> roots) {
        this(members, roots, List.of());
    }
    public Colony(List<LivingEntity> members, List<BlockPos> roots, List<BlockPos> logs) {
        this.members = List.copyOf(members); this.roots = List.copyOf(roots); this.rootSet = Set.copyOf(roots);
        this.logs = List.copyOf(logs);
        LivingEntity leader = members.stream().filter(e -> e.tissue.productionChoice != null)
                .max(java.util.Comparator.comparingLong((LivingEntity e) -> e.tissue.productionChosenAt)
                        .thenComparing(e -> e.tissue.productionChoice)).orElse(null);
        productionMode = leader == null ? members.stream().mapToInt(e -> e.tissue.productionMode).min().orElse(SupplyMode.NORMAL) : leader.tissue.productionMode;
        modeChosenAt = leader == null ? Long.MIN_VALUE : leader.tissue.productionChosenAt;
        modeChoice = leader == null ? null : leader.tissue.productionChoice;
    }
    public int productionMode() { return productionMode; }
    public void chooseMode(int mode, long tick, java.util.UUID identity) {
        productionMode = Math.clamp(mode, 0, 2);
        modeChosenAt = tick;
        modeChoice = identity;
        synchronizeMode();
    }
    void synchronizeMode() {
        for (LivingEntity member : members) {
            if (member.tissue.productionMode == productionMode && member.tissue.productionChosenAt == modeChosenAt
                    && java.util.Objects.equals(member.tissue.productionChoice, modeChoice)) continue;
            member.tissue.productionMode = productionMode;
            member.tissue.productionChosenAt = modeChosenAt;
            member.tissue.productionChoice = modeChoice;
            member.setChanged();
        }
    }
    private TimeStorage storage() {
        boolean same = storageRoots.size() == members.size();
        for (int i = 0; same && i < members.size(); i++) same = storageRoots.get(i) == members.get(i).tissue;
        if (!same || storageView == null || !storageView.current(storageRoots)) {
            releaseStorage();
            storageRoots = members.stream().map(e -> e.tissue).toList();
            storageView = TimeStorage.track(storageRoots);
        }
        return storageView;
    }
    void releaseStorage() {
        if (storageView != null) storageView.close();
        storageView = null; storageRoots = List.of();
    }
    private void changed() { members.forEach(LivingEntity::setChanged); }
    public long quantity() { return storage().quantity(); }
    public long capacity() { return storage().capacity(); }
    public long spendable() { return storage().spendable(); }
    public long freeCapacity() { return storage().freeCapacity(); }
    public long room() { return storage().room(); }

    public boolean credit(long amount, LivingEntity receiver) {
        if (amount <= 0 || !storage().credit(amount)) return false;
        receiver.received(amount, true);
        changed();
        return true;
    }

    /** A final native opportunity may fill a fractional gap; its excess is lost. */
    public boolean creditAvailable(long amount, LivingEntity receiver) {
        TimeStorage storage = storage();
        long accepted = Math.min(amount, storage.room());
        if (accepted <= 0 || !storage.credit(accepted)) return false;
        receiver.received(accepted, true);
        changed();
        return true;
    }
    public boolean spend(long amount) {
        if (!storage().spend(amount)) return false;
        if (amount > 0) changed();
        return true;
    }

    public void equalize() {
        if (storage().equalize()) changed();
    }

    public Tissue harvest(LivingEntity mother) {
        Tissue.Side side = mother.tissue.selectedSide();
        if (side == null || !side.mature()
                || mother.tissue.rootProduction != null && !mother.tissue.rootProduction.ready()) return null;
        synchronizeMode();
        equalize();
        Tissue picked = GiantRoots.harvest(mother.tissue, side);
        equalize();
        mother.changed();
        return picked;
    }

    public List<Tissue> harvestBatch(LivingEntity mother) {
        if (mother.tissue.giant) {
            Tissue.Side side = mother.tissue.selectedSide();
            if (side == null || side.locked) return List.of();
            Tissue picked = harvest(mother);
            return picked == null ? List.of() : List.of(picked);
        }
        List<Tissue.Side> ready = mother.tissue.sides.stream().filter(s -> s.mature() && !s.locked).toList();
        if (ready.isEmpty()) return List.of();
        synchronizeMode();
        equalize();
        List<Tissue> result = new java.util.ArrayList<>();
        for (Tissue.Side side : ready) {
            Tissue picked = GiantRoots.harvest(mother.tissue, side);
            if (picked != null) result.add(picked);
        }
        equalize();
        mother.changed();
        return result;
    }

    public boolean chargeSide(LivingEntity mother) {
        Tissue.Side side = mother.tissue.selectedSide();
        if (side == null || !side.mature()
                || mother.tissue.rootProduction != null && !mother.tissue.rootProduction.ready()) return false;
        equalize();
        boolean wasLocked = side.locked;
        side.locked = true;
        long fill = Math.min(side.tissue.room(), spendable());
        if (fill == 0 && side.stored() == 0) { side.locked = wasLocked; return false; }
        if (!spend(fill)) { side.locked = wasLocked; return false; }
        if (!side.tissue.credit(fill)) throw new IllegalStateException("Side precharge exceeded its space");
        mother.changed();
        return true;
    }

    public boolean releaseSide(LivingEntity mother) {
        Tissue.Side side = mother.tissue.selectedSide();
        if (side == null || !side.locked) return false;
        side.locked = false;
        equalize();
        mother.changed();
        return true;
    }

    public long cultivate(LivingEntity mother, Tissue.Side side) {
        if (side == null || side.mature()) return 0;
        return cultivate(mother, side.tissue);
    }

    public long cultivate(LivingEntity mother, Tissue part) {
        if (part.established()) return 0;
        part.growth.active = true;
        long payment = Math.min(part.growth.remainingFee(), spendable());
        if (!spend(payment)) throw new IllegalStateException("Side cultivation lost its balance");
        part.growth.invested += payment;
        equalize();
        mother.changed();
        return payment;
    }

    public boolean graft(LivingEntity mother, Tissue tissue) {
        if (mother.tissue.sides.size() >= mother.tissue.slots()) return false;
        return graft(mother, tissue, returned -> {});
    }

    public boolean graft(LivingEntity mother, Tissue tissue, java.util.function.Consumer<Tissue> returned) {
        Tissue.Side previous = mother.tissue.sides.size() >= mother.tissue.slots() ? mother.tissue.selectedSide() : null;
        if (!mother.tissue.supported() || !tissue.supported() || mother.tissue.giant || tissue.tree || tissue.treeGrowing || mother.amber() || mother.tissue.sides.size() >= mother.tissue.slots() && previous == null
                || mother.tissue.nodeCount() + tissue.nodeCount() - (previous == null ? 0 : previous.tissue.nodeCount()) > Tissue.MAX_NODES || tissue.depth() >= Tissue.MAX_DEPTH) return false;
        if (!dev.senluo.witchcraft.ritual.TreeOwnership.canGraft(mother.tissue, tissue)) return false;
        synchronizeMode();
        equalize();
        boolean adoptsOwner = mother.tissue.bond == null && tissue.bond != null;
        var bond = mother.tissue.bond != null ? mother.tissue.bond : tissue.bond;
        // All structural and ownership checks must succeed before modifying either tissue.
        if (bond != null) {
            mother.tissue.inheritBond(bond);
            tissue.inheritBond(bond);
        }
        Tissue.Side incoming = Tissue.Side.graft(tissue);
        incoming.locked = true;
        if (previous == null) {
            mother.tissue.sides.add(incoming);
            mother.tissue.selectedSide = mother.tissue.sides.size() - 1;
        } else {
            mother.tissue.sides.set(mother.tissue.sides.indexOf(previous), incoming);
        }
        // Moving an existing tissue is not new income for cultivation or crop output.
        if (adoptsOwner) {
            // This view still contains the former unowned neighbours. Rebuild before sharing
            // any of the incoming balance; physical root links do not pass ownership onward.
            if (mother.getLevel() instanceof net.minecraft.server.level.ServerLevel server) {
                TemporalWorld world = TemporalWorld.get(server);
                world.invalidate();
                world.colony(mother).equalize();
            } else {
                new Colony(List.of(mother), List.of()).equalize();
            }
        } else {
            equalize();
        }
        mother.changed();
        if (adoptsOwner && mother.getLevel() instanceof net.minecraft.server.level.ServerLevel server) {
            server.sendBlockUpdated(mother.getBlockPos(), mother.getBlockState(), mother.getBlockState(), 3);
        }
        if (previous != null) {
            previous.tissue.inheritProduction(mother.tissue);
            previous.tissue.naturalTicks = 0;
            returned.accept(previous.tissue);
        }
        return true;
    }
}
