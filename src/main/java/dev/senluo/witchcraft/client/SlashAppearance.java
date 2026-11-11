package dev.senluo.witchcraft.client;

import java.util.List;

/** World blade appearance. The offline preview reads these exact production bands. */
public final class SlashAppearance {
    /** Opacity runs across the existing band, from its negative to positive edge. */
    public record Band(double length, double offset, double width, int red, int green, int blue,
                       float lowOpacity, float highOpacity) {}
    static final Band HALO = new Band(1.015, 0, .075, 160, 180, 203, .07F, .07F);
    static final Band BODY = new Band(1.0, 0, .040, 38, 51, 65, .16F, .32F);
    public static final List<Band> BANDS = List.of(HALO, BODY,
            new Band(1.0, .008, .014, 213, 228, 240, .50F, .74F),
            new Band(.96, -.028, .007, 134, 160, 183, .17F, .24F));
    private SlashAppearance() {}

    /** Eight blocks is the reference blade. Sublinear growth keeps long slashes slender.
     * Use whole-blade extent, never an individual tessellation edge's length. */
    public static double widthScale(double length) {
        return Double.isFinite(length) && length > 0 ? Math.sqrt(length / 8) : 0;
    }
}
