package dev.senluo.witchcraft.client;

import java.util.Arrays;

/** Bounded radial envelope of a model projected onto a contact plane.
 * This keeps concave changes such as shoulders/neck instead of fitting an ellipse
 * or a convex hull. Small gaps between limbs are spanned by one continuous film.
 * Input is already posed geometry; no model names, entity types or world queries.
 */
final class BarrierOutline {
    static final int SEGMENTS = 32;
    static final int MAX_VERTICES = 2048;
    static final float[] COS = new float[SEGMENTS], SIN = new float[SEGMENTS];
    private final float[] intersections = new float[SEGMENTS];
    private final float[] radii = new float[SEGMENTS];
    private final float[] quadX = new float[4], quadY = new float[4];
    private int vertices;
    private boolean invalid;
    private float nearDepth, maxRadius;

    static {
        for (int i = 0; i < SEGMENTS; i++) {
            double angle = i * Math.PI * 2 / SEGMENTS;
            COS[i] = (float)Math.cos(angle);
            SIN[i] = (float)Math.sin(angle);
        }
    }

    void begin() {
        Arrays.fill(intersections, 0);
        vertices = 0;
        invalid = false;
        nearDepth = Float.POSITIVE_INFINITY;
    }

    void vertex(float x, float y, float z) {
        if (invalid) return;
        if (vertices == MAX_VERTICES || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                || Math.abs(x) > 64 || Math.abs(y) > 64 || Math.abs(z) > 64) {
            invalid = true;
            return;
        }
        nearDepth = Math.min(nearDepth, z);
        int corner = vertices++ & 3;
        quadX[corner] = x;
        quadY[corner] = y;
        if (corner == 3) {
            for (int i = 0; i < 4; i++) edge(quadX[i], quadY[i], quadX[(i + 1) & 3], quadY[(i + 1) & 3]);
        }
    }

    private void edge(float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        float area = ax * dy - ay * dx;
        for (int i = 0; i < SEGMENTS; i++) {
            // Keep narrow limbs/wing tips that fall between two sampled rays.
            float alongRay = ax * COS[i] + ay * SIN[i];
            float offRay = Math.abs(ax * SIN[i] - ay * COS[i]);
            if (alongRay > 0 && offRay <= alongRay * .10F) {
                intersections[i] = Math.max(intersections[i], alongRay + offRay * .10F);
            }
            float denominator = COS[i] * dy - SIN[i] * dx;
            if (Math.abs(denominator) < 1.0E-7F) continue;
            float distance = area / denominator;
            if (distance <= intersections[i]) continue;
            float along = (ax * SIN[i] - ay * COS[i]) / denominator;
            if (along >= -1.0E-5F && along <= 1.00001F) intersections[i] = distance;
        }
    }

    /** Incomplete/unsupported streams never become a partial, misleading silhouette. */
    boolean finish(float padding) {
        if (invalid || vertices < 4 || (vertices & 3) != 0) return false;
        int covered = 0;
        float maximum = 0;
        for (float distance : intersections) {
            if (distance > 1.0E-4F) covered++;
            maximum = Math.max(maximum, distance);
        }
        if (covered < SEGMENTS / 4 || maximum < .01F) return false;
        maxRadius = 0;
        for (int i = 0; i < SEGMENTS; i++) {
            float current = intersections[i];
            float left = intersections[(i + SEGMENTS - 1) % SEGMENTS];
            float right = intersections[(i + 1) % SEGMENTS];
            // Round only small notches outward; do not pull the film through limbs.
            radii[i] = Math.max(current, .7F * current + .15F * (left + right)) + padding;
            maxRadius = Math.max(maxRadius, radii[i]);
        }
        nearDepth -= padding;
        return true;
    }

    float radius(int segment) { return radii[segment % SEGMENTS]; }
    float maxRadius() { return maxRadius; }
    float nearDepth() { return nearDepth; }
}
