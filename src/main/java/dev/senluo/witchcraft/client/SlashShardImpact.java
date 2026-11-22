package dev.senluo.witchcraft.client;

import java.util.SplittableRandom;
import net.minecraft.world.phys.Vec3;

/** Small folded slivers peel away from the contacted surface and catch the light as they turn. */
final class SlashShardImpact implements SlashHitGeometry {
    private record Chip(Vec3 flight, double roll, double spin, double pitch, double size, double end) {}
    private final Frame frame;
    private final Chip[] chips;

    SlashShardImpact(Frame frame, int seed) {
        this.frame = frame;
        var random = new SplittableRandom(seed);
        chips = new Chip[3 + random.nextInt(3)];
        double angle = random.nextDouble(-.24, .24);
        for (int i = 0; i < chips.length; i++) {
            double bearing = angle + i * Math.TAU / chips.length + random.nextDouble(-.25, .25);
            Vec3 flight = frame.position(Math.cos(bearing) * random.nextDouble(.29, .60),
                    Math.sin(bearing) * random.nextDouble(.16, .34), -random.nextDouble(.10, .30));
            chips[i] = new Chip(flight, bearing, random.nextDouble(1.5, 3.0) * (i % 2 == 0 ? 1 : -1),
                    random.nextDouble(-1.15, .65), random.nextDouble(.032, .065), random.nextDouble(.75, 1.0));
        }
    }

    @Override public int ticks() { return 7; }
    @Override public double radius() { return .86; }

    @Override public void draw(double t, Vec3 eye, Vertex out) {
        if (t < 0 || t >= 1) return;
        for (Chip chip : chips) {
            double u = t / chip.end;
            if (u >= 1) continue;
            Vec3 center = chip.flight.scale(.035 + u * (1.73 - .78 * u)).add(0, -.13 * u * u, 0);
            double roll = chip.roll + u * chip.spin, pitch = chip.pitch + u * 2.7;
            double size = chip.size * (1 - .53 * u * u);
            Vec3 axis = frame.position(Math.cos(roll), Math.sin(roll), 0).scale(size);
            Vec3 side = frame.position(-Math.sin(roll) * Math.cos(pitch), Math.cos(roll) * Math.cos(pitch),
                    Math.sin(pitch)).scale(size);
            Vec3 base = center.subtract(axis), nose = center.add(axis.scale(1.35));
            Vec3 near = center.add(side.scale(.73)).subtract(axis.scale(.23));
            Vec3 far = center.subtract(side.scale(.48)).add(axis.scale(.15));
            boolean flip = axis.cross(side).dot(eye.subtract(center)) < 0;
            float body = (float)(.84 * (1 - SlashHitGeometry.ramp((u - .43) / .57)));
            triangle(out, base, nose, near, 0xc7d6da, body * .47F, flip);
            Vec3 rimTip = nose.lerp(near, .10);
            Vec3 rimEnd = nose.lerp(near, .71);
            triangle(out, rimTip, rimEnd, rimEnd.lerp(base, .19), 0xe9efed,
                    body * .96F, body * .72F, flip);
            triangle(out, base, far, nose, 0x798d98, body * .637F, flip);
            float glint = body * (float)Math.max(0, Math.cos(2.0 * pitch));
            if (glint > .1F) triangle(out, nose, near, near.lerp(base, .24), 0xf1f2e9, glint, flip);
        }
    }

    private static void triangle(Vertex out, Vec3 a, Vec3 b, Vec3 c, int color, float alpha, boolean flip) {
        triangle(out, a, b, c, color, alpha, alpha, flip);
    }

    private static void triangle(Vertex out, Vec3 a, Vec3 b, Vec3 c, int color,
                                 float tipAlpha, float edgeAlpha, boolean flip) {
        out.put(a, .5F, .5F, color, tipAlpha);
        out.put(flip ? c : b, .5F, .5F, color, edgeAlpha);
        out.put(flip ? b : c, .5F, .5F, color, edgeAlpha);
        out.put(a, .5F, .5F, color, tipAlpha);
    }
}
