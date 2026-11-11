package dev.senluo.witchcraft.brew;

import dev.senluo.witchcraft.time.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Per-bottle inputs, rather than a copy of the whole barrel's time. */
public record WineData(double rawTu, double maturity, int quality, int version) {
    public static final String KEY = "WitchcraftWine";
    public static final int CURRENT_VERSION = 3;
    public static final double[] AGE_MULTIPLIERS = {1, 1.25, 1.5, 2, 2.5, 3};
    public WineData(double rawTu, double maturity, int quality) { this(rawTu, maturity, quality, CURRENT_VERSION); }
    public WineData {
        if (!supported(version) || !Double.isFinite(rawTu) || rawTu < 0 || !Double.isFinite(maturity))
            throw new IllegalArgumentException("Invalid wine inputs");
        maturity = Math.clamp(maturity, 0, 1);
        quality = Math.clamp(quality, 1, 6);
    }
    private static boolean supported(int version) { return version == CURRENT_VERSION; }
    public double score() {
        double stock = rawTu / 1200;
        return (80 * Math.sqrt(stock) + 20 * maturity) * AGE_MULTIPLIERS[quality - 1];
    }
    public int grade() { return grade(score(), version); }
    public int durationTicks() {
        BodyTime preview=new BodyTime();
        preview.drink(this);
        return preview.displayTicks();
    }
    public static int grade(double score) { return grade(score, CURRENT_VERSION); }
    public static int grade(double score, int version) {
        if (!supported(version) || !Double.isFinite(score) || score <= 0) return 0;
        int grade = 1;
        for (double upper = 100; score > upper; upper *= 1.6) grade++;
        return grade;
    }
    public WineData aged(int quality) { return new WineData(rawTu, maturity, quality, version); }
    public void write(ItemStack stack) {
        CompoundTag root = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", version); tag.putDouble("RawTu", rawTu);
        tag.putDouble("Maturity", maturity); tag.putInt("Quality", quality);
        root.put(KEY, tag); stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        if (net.neoforged.fml.ModList.get().isLoaded("kaleidoscope_tavern"))
            dev.senluo.witchcraft.compat.tavern.TavernWine.setQuality(stack, quality);
    }
    public static WineData read(ItemStack stack) {
        CompoundTag root = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!root.contains(KEY, Tag.TAG_COMPOUND)) return null;
        CompoundTag tag = root.getCompound(KEY);
        if (!tag.contains("Version", Tag.TAG_INT) || !supported(tag.getInt("Version"))
                || !tag.contains("RawTu", Tag.TAG_DOUBLE) || !tag.contains("Maturity", Tag.TAG_DOUBLE)
                || !tag.contains("Quality", Tag.TAG_INT)
                || !Double.isFinite(tag.getDouble("RawTu")) || tag.getDouble("RawTu") < 0
                || !Double.isFinite(tag.getDouble("Maturity"))) return null;
        return new WineData(tag.getDouble("RawTu"), tag.getDouble("Maturity"), tag.getInt("Quality"), tag.getInt("Version"));
    }
    public static double maturity(Tissue tissue) {
        double[] sum = {0, 0}; collect(tissue, sum); return sum[0] / sum[1];
    }
    private static void collect(Tissue tissue, double[] sum) {
        double progress = tissue.growth != null ? (double) tissue.growth.ticks / Tissue.ROOT_TICKS
                : tissue.maturePlant() || tissue.amberStage > 0 ? 1 : (double) tissue.naturalTicks / Tissue.ROOT_TICKS;
        sum[0] += Math.clamp(progress, 0, 1); sum[1]++;
        for (Tissue.Side side : tissue.sides) collect(side.tissue, sum);
    }
}
