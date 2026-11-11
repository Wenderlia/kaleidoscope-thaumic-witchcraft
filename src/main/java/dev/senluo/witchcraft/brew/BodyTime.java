package dev.senluo.witchcraft.brew;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.common.util.INBTSerializable;

/** Base-use ticks are an internal unit, not TU or Time-in-a-Bottle ticks. */
public final class BodyTime implements INBTSerializable<CompoundTag> {
    private int grade;
    private double baseTicks;
    private CompoundTag unsupported;
    private long ledgerEpoch;
    private int outstandingHolds;
    public boolean acceptsWine() { return unsupported == null; }
    public int grade() { return grade; }
    public double baseTicks() { return baseTicks; }
    public boolean active() { return grade > 0 && (free() || baseTicks > 0); }
    public boolean free() { return grade >= 13; }
    /** Raw ability quotes stay in base-use ticks; only this ledger applies the grade discount. */
    public static double costFactor(int grade) { return Math.max(0,13-Math.max(1,grade))/12.0; }
    public double costFactor() { return costFactor(grade); }
    public double price(double ticks) { return free()?0:ticks*costFactor(); }
    public double compression() { return 1; }
    public double capacity() { return free() ? Double.POSITIVE_INFINITY : 6000 * Math.pow(2, Math.max(0, grade - 1)); }
    public void drink(double score) { drink(score, WineData.grade(score)); }
    public void drink(WineData wine) { drink(wine.score(), wine.grade()); }
    private void drink(double score, int incoming) {
        if (!acceptsWine() || incoming <= 0) return;
        grade = Math.max(grade, incoming);
        if (free()) { baseTicks = 0; return; }
        double credit = score * 60;
        baseTicks = Math.min(capacity(), baseTicks + credit);
    }
    /** Refill an existing tier without granting a tier or turning a refill into a wine score. */
    public double replenish(double ticks) {
        if (!acceptsWine() || grade<=0 || free() || !Double.isFinite(ticks) || ticks<=0) return 0;
        double accepted=Math.min(ticks,Math.max(0,capacity()-baseTicks));
        baseTicks+=accepted;
        return accepted;
    }
    public boolean canSpend(double ticks) {
        return active() && Double.isFinite(ticks) && ticks >= 0 && (free() || baseTicks >= price(ticks));
    }
    public boolean spend(double ticks) {
        if (!canSpend(ticks)) return false;
        if (!free()) { baseTicks = Math.max(0,baseTicks-price(ticks)); clearIfEmpty(); }
        return true;
    }
    /** Rituals can exhaust a balance and then continue on natural time. Returns actual base-use ticks. */
    public double drain(double quotedTicks) {
        if (!active() || !Double.isFinite(quotedTicks) || quotedTicks < 0 || free()) return 0;
        double paid = Math.min(baseTicks, price(quotedTicks));
        baseTicks = Math.max(0, baseTicks - paid);
        clearIfEmpty();
        return paid;
    }
    /** A ritual already discounted its shared invoice before paying from multiple balances. */
    public double drainActual(double ticks) {
        if (!active() || !Double.isFinite(ticks) || ticks <= 0 || free()) return 0;
        double paid = Math.min(baseTicks, ticks);
        baseTicks -= paid;
        clearIfEmpty();
        return paid;
    }
    /** Synchronous ability escrow: keep the tier until its accepted/cancelled outcome is known. */
    public Hold hold(double ticks) {
        if(!canSpend(ticks))return null;
        double factor=costFactor(), paid=price(ticks);
        baseTicks-=paid;
        outstandingHolds++;
        return new Hold(this,paid,factor,ledgerEpoch);
    }
    public static final class Hold {
        private final BodyTime ledger;
        private final double paid,factor;
        private final long epoch;
        private boolean closed;
        private Hold(BodyTime ledger,double paid,double factor,long epoch) {this.ledger=ledger;this.paid=paid;this.factor=factor;this.epoch=epoch;}
        public void finish(boolean accepted) {
            settle(accepted?paid:0);
        }
        public void finish(double charge) {
            // Snapshot the rate at reservation: callbacks may change the active grade.
            settle(factor==0?0:Math.min(paid,Math.max(0,charge)*factor));
        }
        private void settle(double charge) {
            if(closed)return;
            closed=true;
            if(ledger.ledgerEpoch!=epoch)return;
            ledger.outstandingHolds--;
            if(!ledger.free())ledger.baseTicks=Math.min(ledger.capacity(),ledger.baseTicks+Math.max(0,paid-charge));
            ledger.clearIfEmpty();
        }
    }
    public int payNegative(int duration, int amplifier) {
        if (!active() || duration <= 0) return 0;
        if (free()) return duration;
        double price = (double)amplifier + 1;
        int removed = (int)Math.min(duration, Math.floor(baseTicks / price(price)));
        // Quotient rounding can land just below an integer; the transaction quote is authoritative.
        if(removed<duration && canSpend(((double)removed+1)*price))removed++;
        if(removed>0 && !canSpend(removed*price))removed--;
        if (removed > 0) spend(removed * price);
        return removed;
    }
    public void tick() { tick(1); }
    public void tick(int maintenanceMultiplier) {
        if (active() && !free()) {
            baseTicks = Math.max(0, baseTicks - price(compression() * Math.max(1, maintenanceMultiplier)));
            clearIfEmpty();
        }
    }
    public int displayTicks() {
        if (free()) return -1;
        return (int)Math.min(Integer.MAX_VALUE, Math.ceil(baseTicks / price(compression())));
    }
    /** Client estimate only: both the displayed duration and nominal fees already divide out delta.
     * Display rounds upward, so omit its uncertain final tick. The server still settles every fee. */
    public static boolean displayCanSpend(int duration, double quotedTicks) {
        return Double.isFinite(quotedTicks) && quotedTicks >= 0
                && (duration == -1 || duration > 0 && quotedTicks <= duration - 1.0);
    }
    private void clearIfEmpty() {if(!active() && outstandingHolds==0)clear();}
    public void clear() { grade = 0; baseTicks = 0; unsupported = null; outstandingHolds=0; ledgerEpoch++; }
    @Override public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        if (unsupported != null) return unsupported.copy();
        CompoundTag tag = new CompoundTag(); tag.putInt("Version", 2);
        tag.putInt("Grade", grade); tag.putDouble("BaseTicks", baseTicks); return tag;
    }
    @Override public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        clear();
        if (!tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 2
                || !tag.contains("Grade", Tag.TAG_INT) || !tag.contains("BaseTicks", Tag.TAG_DOUBLE)) {
            unsupported = tag.copy(); return;
        }
        int loaded = tag.getInt("Grade"); double ticks = tag.getDouble("BaseTicks");
        if (loaded == 0 && ticks == 0) return; // Normal empty ledger written by serializeNBT.
        // Bounds come from representable scores, not a gameplay tier ceiling.
        if (loaded < 1 || loaded > WineData.grade(Double.MAX_VALUE) || !Double.isFinite(ticks) || ticks < 0
                || (loaded < 13 && ticks > 6000 * Math.pow(2, loaded - 1))) {
            unsupported = tag.copy(); return;
        }
        grade = loaded;
        baseTicks = free() ? 0 : Math.min(capacity(), ticks);
        if (!active()) clear();
    }
}
