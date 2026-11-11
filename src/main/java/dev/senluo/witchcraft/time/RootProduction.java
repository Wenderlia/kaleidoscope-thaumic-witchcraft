package dev.senluo.witchcraft.time;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

/** One harvest account and clock, plus the great tree's permanent trunk reserve. */
public final class RootProduction {
    private static final int RESERVE_ROOT_UNITS = 23;
    public static final int COOLDOWN = Tissue.ROOT_TICKS;
    static final long MAX_RESERVE = Tissue.MAX_VALUE * GiantRoots.MAX_NODES;
    private final long reserveCapacity;
    private Tissue.Side output;
    private int remaining;

    private RootProduction(long reserveCapacity, Tissue.Side output, int remaining) {
        this.reserveCapacity = reserveCapacity;
        this.output = output;
        this.remaining = remaining;
    }

    /** Move real balances once on promotion; no new TU is credited. */
    static RootProduction fromRoots(Tissue mother) {
        Tissue.Side first = mother.sides.isEmpty() ? null : mother.sides.getFirst();
        Tissue.Side output = freshOutput(mother);
        output.tissue.identity = first == null ? UUID.nameUUIDFromBytes((mother.identity + ":root-output")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)) : first.tissue.identity;
        int remaining = first == null ? 0 : first.remainingTicks();
        long reserve = RESERVE_ROOT_UNITS * GiantRoots.CAPACITY;
        for (Tissue.Side side : mother.sides) {
            // On the living tree's upgrade, branch storage and bonuses become trunk storage.
            // Harvesting this producer always yields one plain tree root.
            reserve += Math.max(0, fullCapacity(side.tissue) - GiantRoots.CAPACITY);
            mother.setCoreQuantity(mother.coreQuantity() + side.stored());
        }
        if (first != null) {
            output.locked = first.locked;
            output.tissue.setCoreQuantity(Math.min(first.stored(), output.capacity()));
            mother.setCoreQuantity(mother.coreQuantity() - output.tissue.coreQuantity());
        }
        mother.sides.clear();
        mother.selectedSide = 0;
        return new RootProduction(reserve, output, remaining);
    }

    private static long fullCapacity(Tissue tissue) {
        long total = tissue.matureCoreCapacity();
        for (Tissue.Side side : tissue.sides) total += fullCapacity(side.tissue);
        return total;
    }
    private static Tissue.Side freshOutput(Tissue mother) {
        Tissue.Side root = GiantRoots.fresh(mother);
        root.advance(root.remainingTicks());
        return root;
    }
    public long reserveCapacity() { return reserveCapacity; }
    public Tissue.Side output() { return output; }
    public int remainingTicks() { return remaining; }
    public boolean ready() { return remaining == 0 && output.mature(); }
    public int advance(int elapsed) {
        int advanced = Math.min(Math.max(0, elapsed), remaining);
        remaining -= advanced;
        return advanced;
    }
    Tissue harvest(Tissue mother) {
        if (!ready()) return null;
        Tissue picked = output.tissue;
        output = freshOutput(mother);
        remaining = COOLDOWN;
        picked.inheritProduction(mother);
        picked.naturalTicks = 0;
        return picked;
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", 1);
        tag.putLong("ReserveCapacity", reserveCapacity);
        tag.putInt("Cooldown", remaining);
        tag.putBoolean("Locked", output.locked);
        tag.put("Output", TissueTag.save(output.tissue));
        return tag;
    }
    static RootProduction load(CompoundTag tag, Tissue tissue) {
        Tissue.Side output = Tissue.Side.graft(tissue);
        output.locked = tag.getBoolean("Locked");
        return new RootProduction(tag.getLong("ReserveCapacity"), output,
                Math.max(Tissue.bounded(tag.getInt("Cooldown"), COOLDOWN), output.remainingTicks()));
    }
}
