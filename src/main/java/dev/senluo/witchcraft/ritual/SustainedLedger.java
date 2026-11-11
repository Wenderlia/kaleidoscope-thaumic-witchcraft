package dev.senluo.witchcraft.ritual;

import dev.senluo.witchcraft.brew.BodyTime;
import dev.senluo.witchcraft.time.SourceIncomeRates;
import dev.senluo.witchcraft.time.Tissue;

/** One native-second invoice; plant income and temporary protection balances are distinct. */
public final class SustainedLedger {
    public static final double QUOTE = 5_120, CONVERSION = 64, BODY_UNITS = 256;
    public static final int GRACE_SECONDS = 600;
    private static final int CURRENT_ECONOMY = 2;
    private final int economyVersion;
    private double pending, temporary;
    private int seconds;

    public SustainedLedger() { this(CURRENT_ECONOMY); }
    private SustainedLedger(int economyVersion) { this.economyVersion = economyVersion; }

    public double baseline(double volume) {
        double rate = SourceIncomeRates.ENTITY_SECOND;
        return volume * 16 * rate / Tissue.TU;
    }

    public net.minecraft.nbt.CompoundTag save() {
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putInt("EconomyVersion", economyVersion);
        tag.putDouble("Pending", pending); tag.putDouble("Temporary", temporary);
        tag.putInt("Seconds", seconds);
        return tag;
    }

    public static SustainedLedger load(net.minecraft.nbt.CompoundTag tag) {
        if (!tag.contains("Seconds", net.minecraft.nbt.Tag.TAG_INT) || tag.getInt("Seconds") < 0
                || tag.getInt("Seconds") > GRACE_SECONDS) throw new IllegalArgumentException("Invalid ritual grace");
        int economy = tag.getInt("EconomyVersion");
        if (tag.contains("EconomyVersion") && !tag.contains("EconomyVersion", net.minecraft.nbt.Tag.TAG_INT)
                || economy != CURRENT_ECONOMY)
            throw new IllegalArgumentException("Unsupported ritual economy");
        SustainedLedger ledger = new SustainedLedger(economy);
        ledger.seconds = tag.getInt("Seconds");
        ledger.pending = balance(tag, "Pending"); ledger.temporary = balance(tag, "Temporary");
        return ledger;
    }

    private static double balance(net.minecraft.nbt.CompoundTag tag, String key) {
        double value = tag.getDouble(key);
        if (!tag.contains(key, net.minecraft.nbt.Tag.TAG_DOUBLE) || !Double.isFinite(value)
                || value < 0 || value > 1.0e15) throw new IllegalArgumentException("Invalid ritual balance");
        return value;
    }

    public record Payment(double plantTu, float healthLoss) {}

    public void add(double amount) {
        if (Double.isFinite(amount) && amount > 0) pending = Math.min(1.0e15, pending + amount);
    }

    public Payment settle(double baseline, BodyTime body) {
        double earned = pending;
        pending = 0;
        boolean afterGrace = seconds >= GRACE_SECONDS;
        if (seconds < GRACE_SECONDS) seconds++;
        // During grace the extra time goes straight to the tree, never into a future shield.
        if (afterGrace) temporary = Math.min(1.0e15, temporary + earned);
        double fee = QUOTE * (body != null && body.active() ? body.costFactor() : 1);
        double shield = Math.min(temporary, fee);
        temporary -= shield;
        double paid = shield + (body == null ? 0 : body.drainActual((fee - shield) / BODY_UNITS) * BODY_UNITS);
        float loss = afterGrace && fee > 0 ? (float) Math.clamp(1 - paid / fee, 0, 1) : 0;
        return new Payment(Math.max(baseline, earned / CONVERSION), loss);
    }
}
