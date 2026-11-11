package dev.senluo.witchcraft.ritual;

/** Exact accounting: one essentia is one cauldron level, or one third of a bucket. */
public final class RitualWater {
    public static final int REACH = 12;
    private RitualWater() {}
    public record Amount(int essentia, int millibuckets, int previousThirds, int nextThirds) {}
    public static Amount quote(int essentia, int thirds) {
        if (essentia < 1 || essentia > 3 || thirds < 0 || thirds > 2)
            throw new IllegalArgumentException("Invalid water conversion");
        int total = essentia * 1000 + thirds;
        return new Amount(essentia, total / 3, thirds, total % 3);
    }
}
