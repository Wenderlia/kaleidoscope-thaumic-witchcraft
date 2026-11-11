package dev.senluo.witchcraft.client;

/** One continuous, shallow contact membrane. Positive depth faces the incoming object.
 * The production renderer and offline preview use this same mesh. Render-thread confined;
 * no world queries, gameplay state, per-frame vertex arrays or per-vertex objects.
 */
final class BarrierGeometry {
    static final int SEGMENTS = 32;
    static final int MAX_QUADS = 256;
    private static final int STRIDE = 9;
    private static final float[] RADII = {0, .22F, .44F, .64F, .80F, .88F, .935F, .962F, 1};
    private static final float[] COS = new float[SEGMENTS + 1], SIN = new float[SEGMENTS + 1];
    private static final float[] VERTICES = new float[RADII.length * (SEGMENTS + 1) * STRIDE];

    interface Sink {
        void vertex(float x, float y, float depth, float u, float v,
                float r, float g, float b, float alpha);
    }

    static {
        for (int i = 0; i <= SEGMENTS; i++) {
            double angle = i * Math.PI * 2 / SEGMENTS;
            COS[i] = (float)Math.cos(angle);
            SIN[i] = (float)Math.sin(angle);
        }
    }

    static void emit(Sink sink, float radius, float aspect, float opacity,
            float flow, float compression, float viewX, float viewY, float viewZ, boolean coarse) {
        emit(sink, radius, aspect, opacity, flow, compression, viewX, viewY, viewZ, coarse, null);
    }

    static void emit(Sink sink, float radius, float aspect, float opacity,
            float flow, float compression, float viewX, float viewY, float viewZ, boolean coarse, BarrierOutline outline) {
        int step = coarse ? 2 : 1;
        float phase = flow * (float)(2 * Math.PI);
        float phaseSin = (float)Math.sin(phase), phaseCos = (float)Math.cos(phase);
        // Pressure settles into a dent. It never launches a train of planes along the travel axis.
        float pressure = .11F + .08F * (1 - Math.clamp(compression, 0, 1));
        for (int band = 0; band < RADII.length; band++) {
            float r = RADII[band], r2 = r * r, bowl = (1 - r2) * (1 - r2);
            float wave = (float)Math.sin(phase * 2 - r * 8);
            float edge = 1 - ParticleAppearance.smooth((r - .96F) / .04F);
            float rim = Math.max(0, 1 - Math.abs(r - .935F) / .058F);
            rim *= rim;
            for (int segment = 0; segment <= SEGMENTS; segment += step) {
                float c = COS[segment], s = SIN[segment];
                float angular = (c * c - s * s) * phaseCos + 2 * c * s * phaseSin;
                float contour = 1 + .008F * angular * r2;
                float extent = outline == null ? radius : outline.radius(segment);
                float x = extent * r * c * contour, y = extent * aspect * r * s * contour;
                float depthScale = Math.min(radius, 1.2F);
                float z = -.028F - depthScale * pressure * bowl
                        + depthScale * .018F * wave * r * (1 - r) * (1 + .25F * angular);
                // Analytic normal of the main dent. View response stays two-sided and bounded.
                float slope = 4 * pressure * r * (1 - r2);
                float nx = -slope * c, ny = -slope * s / aspect;
                float dot = (nx * viewX + ny * viewY + viewZ)
                        / (float)Math.sqrt(1 + nx * nx + ny * ny);
                float grazing = 1 - Math.min(1, Math.abs(dot));
                grazing *= grazing;
                // Broad, slow colour domains belong to the surface, rather than separate light rings.
                float sheen = .5F + .5F * (wave * .55F + angular * .45F) * r;
                float shoulder = ParticleAppearance.smooth((r - .58F) / .34F);
                float glancing = .65F * c - .76F * s;
                glancing *= glancing;
                // Two broad grazing highlights describe a film edge; no solid hoop or moving depth bands.
                float reflection = rim * (.14F + .86F * glancing) * (.84F + .16F * sheen);
                float highlight = Math.min(1, .12F + .30F * sheen + .36F * grazing + .72F * reflection);
                float alpha = opacity * edge * (.085F + .12F * grazing + .07F * shoulder
                        + .55F * reflection + .025F * sheen);
                int index = (band * (SEGMENTS + 1) + segment) * STRIDE;
                VERTICES[index] = x;
                VERTICES[index + 1] = y;
                VERTICES[index + 2] = z;
                VERTICES[index + 3] = .5F + r * c * .42F + .045F * phaseSin;
                VERTICES[index + 4] = .5F + r * s * .42F + .035F * phaseCos;
                VERTICES[index + 5] = .23F + .65F * highlight + .025F * angular;
                VERTICES[index + 6] = .42F + .53F * highlight;
                VERTICES[index + 7] = .65F + .33F * highlight;
                VERTICES[index + 8] = alpha;
            }
        }
        for (int band = 0; band < RADII.length - 1; band++) {
            for (int s = 0; s < SEGMENTS; s += step) {
                vertex(sink, band, s);
                vertex(sink, band + 1, s);
                vertex(sink, band + 1, s + step);
                vertex(sink, band, s + step);
            }
        }
    }

    private static void vertex(Sink sink, int band, int segment) {
        int i = (band * (SEGMENTS + 1) + segment) * STRIDE;
        sink.vertex(VERTICES[i], VERTICES[i + 1], VERTICES[i + 2], VERTICES[i + 3],
                VERTICES[i + 4], VERTICES[i + 5], VERTICES[i + 6], VERTICES[i + 7], VERTICES[i + 8]);
    }

    private BarrierGeometry() {}
}
