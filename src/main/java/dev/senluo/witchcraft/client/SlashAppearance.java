package dev.senluo.witchcraft.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/** Immutable blade bands and their production dimensions. Also used by the standalone Java exporters. */
public final class SlashAppearance {
    public static final List<String> LAYERS = List.of("halo", "body", "edge", "rim");
    public static final List<String> FIELDS = List.of("color", "lowOpacity", "highOpacity", "length", "width", "offset");
    private static final double LAYER_STEP = .001;

    /** Negative and positive across-edges carry independent vertex alpha. Width is a half-width in blocks. */
    public record Band(double length, double offset, double width, int rgb, float lowOpacity, float highOpacity) {
        public int red() { return rgb >> 16 & 255; }
        public int green() { return rgb >> 8 & 255; }
        public int blue() { return rgb & 255; }
    }

    private final List<Band> bands;
    private final double longestBand;
    private final double farthestEdge;

    private SlashAppearance(List<Band> bands) {
        this.bands = List.copyOf(bands);
        if (halo().width() < body().width())
            throw new IllegalArgumentException("The halo must contain the body for the shared black/red mesh");
        longestBand = bands.stream().mapToDouble(Band::length).max().orElseThrow();
        farthestEdge = bands.stream().mapToDouble(b -> Math.abs(b.offset()) + b.width()).max().orElseThrow();
    }

    public Band halo() { return bands.get(0); }
    public Band body() { return bands.get(1); }

    /** The game reads a JSON resource. Standalone exporters receive the same named JSON scalars from Python. */
    public static SlashAppearance read(BiFunction<String, String, String> field) {
        var bands = new ArrayList<Band>(LAYERS.size());
        for (String id : LAYERS) {
            String color = field.apply(id, "color");
            if (color == null || !color.matches("#[0-9a-fA-F]{6}"))
                throw new IllegalArgumentException(id + ".color must be #RRGGBB");
            bands.add(new Band(number(field, id, "length", 0, 2), number(field, id, "offset", -1, 1),
                    number(field, id, "width", 0, 1), Integer.parseInt(color.substring(1), 16),
                    (float) number(field, id, "lowOpacity", 0, 1), (float) number(field, id, "highOpacity", 0, 1)));
        }
        return new SlashAppearance(bands);
    }

    private static double number(BiFunction<String, String, String> fields, String layer, String key, double min, double max) {
        String text = fields.apply(layer, key);
        if (text == null) throw new IllegalArgumentException("Missing " + layer + "." + key);
        double value = Double.parseDouble(text);
        if (!(value >= min && value <= max))
            throw new IllegalArgumentException(layer + "." + key + " must be within [" + min + ", " + max + "]");
        return value;
    }

    /** Eight blocks is the reference blade. Width follows whole-blade extent, including on a joined outline. */
    public static double widthScale(double length) {
        return Double.isFinite(length) && length > 0 ? Math.sqrt(length / 8) : 0;
    }

    /** View-independent extent from the carrier. Joined chord endpoints already lie inside its radial sphere. */
    public double cullingRadius(double radius, boolean joined) {
        double across = farthestEdge * widthScale(radius * 2);
        double extent = joined ? radius + across : Math.hypot(radius * longestBand, across);
        return extent + (bands.size() - 1) * LAYER_STEP;
    }

    @FunctionalInterface public interface RibbonSink {
        void ribbon(Band band, double half, double offset, double width, double towardCamera);
    }

    public void ribbons(double half, double wholeLength, boolean joined, RibbonSink sink) {
        double scale = widthScale(wholeLength);
        for (int i = 0; i < bands.size(); i++) {
            Band band = bands.get(i);
            // Joined contours keep the endpoints of each edge so neighboring edges meet.
            sink.ribbon(band, joined ? half : half * band.length(), band.offset() * scale,
                    band.width() * scale, i * LAYER_STEP);
        }
    }
}
