package dev.senluo.witchcraft.time;

/** Net milli-TU per native sample: 1 TU * 40% source ceiling * growth weight. */
public final class RandomSampleRates {

    private RandomSampleRates() {}

    public static long price(Tissue tissue) {
        return tissue.giant ? 300 : tissue.tree ? 220 : tissue.maturePlant() ? 140 : 20;
    }
}
