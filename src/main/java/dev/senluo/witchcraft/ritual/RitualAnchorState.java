package dev.senluo.witchcraft.ritual;

import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.common.util.INBTSerializable;

/** Server-only center identity and fractional ledgers. A moved copy is not authority to move a session. */
public final class RitualAnchorState implements INBTSerializable<CompoundTag> {
    private UUID identity = UUID.randomUUID();
    private int waterCredit, waterSupplyThirds;
    private CompoundTag preserved;
    public boolean supported() { return preserved == null; }
    public UUID identity() { return identity; }
    public int waterCredit() { return waterCredit; }
    public int waterSupplyThirds() { return waterSupplyThirds; }
    public void waterSupplyThirds(int value) {
        if (!supported() || value < 0 || value > 2) throw new IllegalArgumentException("Invalid supply fraction");
        waterSupplyThirds = value;
    }
    public boolean commitWater(RitualStability.WaterQuote quote) {
        if (!supported() || quote.previousCredit() != waterCredit || quote.nextCredit() < 0 || quote.nextCredit() > 3) return false;
        waterCredit = quote.nextCredit();
        return true;
    }
    @Override public CompoundTag serializeNBT(HolderLookup.Provider registries) {
        if (preserved != null) return preserved.copy();
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", 1); tag.putUUID("Identity", identity);
        tag.putInt("WaterCredit", waterCredit);
        tag.putInt("WaterSupplyThirds", waterSupplyThirds);
        return tag;
    }
    @Override public void deserializeNBT(HolderLookup.Provider registries, CompoundTag tag) {
        if (!tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 1 || !tag.hasUUID("Identity")
                || !tag.contains("WaterCredit", Tag.TAG_INT) || tag.getInt("WaterCredit") < 0 || tag.getInt("WaterCredit") > 3
                || tag.contains("WaterSupplyThirds") && (!tag.contains("WaterSupplyThirds", Tag.TAG_INT)
                    || tag.getInt("WaterSupplyThirds") < 0 || tag.getInt("WaterSupplyThirds") > 2)) {
            preserved = tag.copy();
            return;
        }
        identity = tag.getUUID("Identity"); waterCredit = tag.getInt("WaterCredit");
        waterSupplyThirds = tag.getInt("WaterSupplyThirds");
        preserved = null;
    }
}
