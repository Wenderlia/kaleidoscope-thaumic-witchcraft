package dev.senluo.witchcraft.client;

import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Clips a convex stroke quad before perspective division. Reused scratch, no world or GPU state. */
final class StrokeProjection {
    // Include duplicate corners when an input edge lies exactly on a clip plane.
    private final float[][] first = new float[20][6], second = new float[20][6];
    private final Vector4f point = new Vector4f();
    private float[][] polygon = first;
    private int size;

    void corner(int index, Matrix4f transform, float x, float y, float z, float u, float v) {
        transform.transform(point.set(x, y, z, 1));
        float[] p = first[index];
        p[0] = point.x; p[1] = point.y; p[2] = point.z; p[3] = point.w; p[4] = u; p[5] = v;
    }

    boolean clip() {
        polygon = first; size = 4;
        for (int i = 0; i < size; i++)
            for (int axis = 0; axis < 4; axis++) if (!Float.isFinite(first[i][axis])) { size = 0; return false; }
        float[][] output = second;
        for (int plane = 0; plane < 7 && size > 0; plane++) {
            int count = 0;
            float[] previous = polygon[size - 1];
            float previousDistance = distance(previous, plane);
            for (int i = 0; i < size; i++) {
                float[] current = polygon[i];
                float currentDistance = distance(current, plane);
                if ((previousDistance >= 0) != (currentDistance >= 0)) {
                    float t = previousDistance / (previousDistance - currentDistance);
                    float[] crossing = output[count++];
                    for (int c = 0; c < 6; c++) crossing[c] = previous[c] + (current[c] - previous[c]) * t;
                }
                if (currentDistance >= 0) System.arraycopy(current, 0, output[count++], 0, 6);
                previous = current; previousDistance = currentDistance;
            }
            float[][] swap = polygon; polygon = output; output = swap; size = count;
        }
        return size >= 3;
    }

    private static float distance(float[] p, int plane) {
        return switch (plane) {
            // Positive w alone is insufficient: also clip near/far and the four viewport sides.
            case 0 -> p[3] - 1.0E-4F;
            case 1 -> p[3] + p[0];
            case 2 -> p[3] - p[0];
            case 3 -> p[3] + p[1];
            case 4 -> p[3] - p[1];
            case 5 -> p[3] + p[2];
            default -> p[3] - p[2];
        };
    }

    int size() { return size; }
    float x(int i, float width) { return (polygon[i][0] / polygon[i][3] + 1) * .5F * width; }
    float y(int i, float height) { return (1 - polygon[i][1] / polygon[i][3]) * .5F * height; }
    float u(int i) { return polygon[i][4]; }
    float v(int i) { return polygon[i][5]; }
}
