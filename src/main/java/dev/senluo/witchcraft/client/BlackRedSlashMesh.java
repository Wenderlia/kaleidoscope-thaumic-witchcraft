package dev.senluo.witchcraft.client;

/** Shared by the native renderer and offline preview. Coordinates are in block units.
 * No particles, world queries, resources, stored trail history or per-vertex objects. */
public final class BlackRedSlashMesh {
    private BlackRedSlashMesh() {}

    @FunctionalInterface public interface Sink {
        /** Four successive vertices form one quad; lag points opposite flight. */
        void vertex(double along, double across, double lag, double towardCamera, int rgb, float alpha);
    }

    // Short blades narrow with length; long criticals keep the ordinary eight-block silhouette.
    private static double widthScale(double length) {
        return Math.min(1, SlashAppearance.widthScale(length));
    }

    public static double bodyHalfWidth(double length) {
        return SlashAppearance.BODY.width() * widthScale(length);
    }

    public static void emit(double half, double widthLength, double age, int seed, boolean joined, float opacity, Sink sink) {
        if (!Double.isFinite(half) || half <= 0 || !Double.isFinite(age) || !Float.isFinite(opacity) || opacity <= 0) return;
        opacity = Math.min(1, opacity);
        // Fixed topology: growth must not change subdivision count and pop the contour.
        int sections = joined ? 1 : 20;
        double scale = widthScale(widthLength);
        if (scale <= 0) return;
        double width = SlashAppearance.BODY.width() * scale;
        double halo = SlashAppearance.HALO.width() * scale - width;
        double lip = halo * .15, near = halo * .45;
        double phase = (seed & 1023) * .006135923;
        // Adjacent bands share boundaries, so layers cannot darken each other or fight for depth.
        for (int i = 0; i < sections; i++) {
            double a = -1 + 2.0 * i / sections, b = -1 + 2.0 * (i + 1) / sections;
            double ta = taper(a, joined), tb = taper(b, joined);
            // A rigid spatial cut: straight spine and planar lips, no wavering centreline.
            double ca = 0, cb = 0;
            for (int sign : SIGNS) {
                // A continuous travelling luminance accent, not frame-random shape jitter.
                float energy = joined ? 1 : (float)(.88 + .12*Math.sin((a+b)*3 - age*.23 + phase));
                float light = opacity * energy * (sign > 0 ? 1 : .72F) * .72F;
                strip(sink, a*half, b*half, ca, cb, sign*(width+halo), sign*(width+near), ta, tb,
                        0, 0, 0x75051A, 0xDB0928, 0, .18F*light);
                strip(sink, a*half, b*half, ca, cb, sign*(width+near), sign*(width+lip), ta, tb,
                        0, 0, 0xDB0928, 0xCF304B, .18F*light, .55F*light);
                strip(sink, a*half, b*half, ca, cb, sign*(width+lip), sign*width, ta, tb,
                        0, 0, sign > 0 ? 0xE96976 : 0xD5102B, 0xD72D49, .92F*light, .98F*light);
            }
            strip(sink, a*half, b*half, ca, cb, -width, width, ta, tb,
                    0, .001, 0x29101C, 0x361525, .24F*opacity, .40F*opacity);
        }
        if (joined) return; // A drawn outline gets one coherent edge, not three tails per tiny segment.
        for (int tail = 0; tail < 3; tail++) {
            double center = (tail-1)*half*.23, length = half*(.58-tail*.09);
            double lag = (.012 + tail*.008) * scale;
            double side = (tail == 1 ? -1 : 1) * (.014 + .004*tail) * scale;
            // Only the outermost, weakest accent may curve. The blade and first two cuts stay rigid.
            double bend = tail == 2 ? .006 * scale : 0;
            for (int i = 0; i < 6; i++) {
                double a = -1 + i/3.0, b = -1 + (i+1)/3.0;
                double lean = (tail == 1 ? -1 : 1) * .002 * scale;
                double ca = side + lean*a + bend*(1-a*a), cb = side + lean*b + bend*(1-b*b);
                double ta = taper(a, false), tb = taper(b, false);
                float alpha = opacity * (float)(.44-tail*.10) * (tail == 2 ? .7F : 1) * .72F;
                double tailWidth = (tail == 2 ? .005 : .009) * scale;
                strip(sink, center+a*length, center+b*length, ca, cb, -tailWidth, 0, ta, tb,
                        lag, 0, 0x72051D, 0xCE1431, 0, alpha);
                strip(sink, center+a*length, center+b*length, ca, cb, 0, tailWidth, ta, tb,
                        lag, 0, 0xCE1431, 0x72051D, alpha, 0);
            }
        }
    }

    private static final int[] SIGNS = {-1, 1};
    private static double taper(double t, boolean joined) {
        // Long parallel edges, then short straight cuts meeting at needle tips.
        // The corner at |t|=.7 is on the fixed body mesh; no ellipse or sinusoidal outline.
        return joined ? 1 : Math.clamp((1-Math.abs(t))/.3, 0, 1);
    }
    private static void strip(Sink sink, double a, double b, double ca, double cb, double lo, double hi,
                              double ta, double tb, double lag, double layer, int lowColor, int highColor,
                              float lowAlpha, float highAlpha) {
        sink.vertex(a, ca+lo*ta, lag, layer, lowColor, lowAlpha);
        sink.vertex(b, cb+lo*tb, lag, layer, lowColor, lowAlpha);
        sink.vertex(b, cb+hi*tb, lag, layer, highColor, highAlpha);
        sink.vertex(a, ca+hi*ta, lag, layer, highColor, highAlpha);
    }
}
