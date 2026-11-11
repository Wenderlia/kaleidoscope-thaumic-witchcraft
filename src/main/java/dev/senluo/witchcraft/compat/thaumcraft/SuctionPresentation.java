package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.tc4port.thaumcraft.api.presentation.ActionPresentationApi;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Presentation-only boundary; it cannot roll back or retry a committed essentia transfer. */
public final class SuctionPresentation {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
            KaleidoscopeWitchcraft.MOD_ID, "suction_transfer_rgb24");
    private static final Logger LOG = LoggerFactory.getLogger(SuctionPresentation.class);
    private static boolean warned;

    private SuctionPresentation() {}

    // TC4R's generic value is bounded to 1,000,000, too small for RGB24. The two
    // public fields carry R (0..255, detail <=256) and GB (0..65535) losslessly.
    public static double colorDetail(int rgb) { return (rgb >>> 16) & 0xFF; }
    public static int colorValue(int rgb) { return rgb & 0xFFFF; }

    /** Reject other encodings instead of truncating a malformed cosmetic event. */
    public static int decodeColor(double detail, int value) {
        if (!Double.isFinite(detail) || detail < 0 || detail > 255 || detail != Math.rint(detail)
                || value < 0 || value > 0xFFFF) return -1;
        return ((int) detail << 16) | value;
    }

    public static void send(ServerLevel level, Vec3 origin, Vec3 target, int rgb) {
        try {
            ActionPresentationApi.presentAt(level, ID, origin, target, null, colorDetail(rgb), colorValue(rgb));
        } catch (RuntimeException failure) {
            // Only this optional external presentation call is isolated. Transfers and their
            // accounting remain outside the catch, and fatal JVM errors are not swallowed.
            if (!warned) {
                warned = true;
                LOG.warn("Suction presentation failed from {} to {}; committed transfers remain settled. Further presentation failures are suppressed.",
                        origin, target, failure);
            }
        }
    }
}
