package dev.senluo.witchcraft.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import net.minecraft.world.phys.Vec3;

/** One world-space discharge. Its bends and pigmented edges stay attached as the current runs out. */
final class BlackFlashImpact implements SlashHitGeometry {
    private static final int LIFETIME = 8;
    // Texel centres on slash_discharge.png: three 256 x 48 strokes, then two 112 x 112 stamps.
    private static final float LAST_TEXEL = 255F;
    private final List<Arc> arcs = new ArrayList<>(13);
    private final Frame frame;
    private final Vec3 contact;
    private final Quad[] quads;
    private final double radius;
    private int count;

    BlackFlashImpact(Frame frame, int seed) {
        this.frame = frame;
        // A small offset on the incident side keeps the mark visible at a contacted surface.
        contact = frame.travel().scale(-.018);
        var rng = new SplittableRandom(seed);
        branch(rng, -.10, 1.53, .13, .215, .35, .97, true);
        branch(rng, 3.29, 1.60, -.37, .21, .29, .84, true);
        branch(rng, 2.28, 1.30, .40, .167, .36, .81, false);
        branch(rng, -.96, .99, .12, .124, .32, .76, false);
        branch(rng, 1.10, .61, -.17, .084, .25, .57, false);
        int capacity = 1;
        double extent = .51;
        for (Arc arc : arcs) {
            capacity += arc.points.length + 2; // A clipped gap can split one segment; allow one flare.
            for (Vec3 point : arc.points) extent = Math.max(extent, point.length() * 1.13 + arc.width * 1.15);
        }
        radius = extent;
        quads = new Quad[capacity];
        Arrays.setAll(quads, i -> new Quad());
    }

    private void branch(SplittableRandom rng, double angle, double reach, double depth,
                        double width, double peel, double end, boolean strong) {
        angle += rng.nextDouble(-.16, .16);
        reach *= rng.nextDouble(.92, 1.09);
        Vec3 axis = frame.position(Math.cos(angle), Math.sin(angle), depth);
        Vec3 side = frame.position(-Math.sin(angle), Math.cos(angle), 0);
        // The incident and receding branches separate in depth near the contact, then settle on their course.
        double arch = depth < -.3 ? -.25 : depth > .3 ? .23 : 0;
        Arc main = course(rng, contact, axis, side, reach, width, arch, strong ? 20 : 14,
                rng.nextDouble(.045, .115), rng.nextDouble(.19, .26), peel, end);
        arcs.add(main);
        int branches = strong ? 2 : 1;
        int turnSign = rng.nextBoolean() ? 1 : -1;
        for (int i = 0; i < branches; i++) {
            int joint = (int) ((main.points.length - 1) * (i == 0 ? rng.nextDouble(.31, .48) : rng.nextDouble(.62, .74)));
            double at = main.distance[joint];
            double turn = turnSign * rng.nextDouble(.42, .94);
            turnSign = -turnSign;
            Vec3 twig = axis.scale(Math.cos(turn)).add(side.scale(Math.sin(turn)))
                    .add(frame.travel().scale(rng.nextDouble(-.28, .28)));
            Vec3 across = side.scale(Math.cos(turn)).subtract(axis.scale(Math.sin(turn)));
            // Invert the parent's ease-out to light a fork only when the current reaches its root.
            double lit = main.delay + main.growth * (1 - Math.sqrt(1 - at));
            arcs.add(course(rng, main.points[joint], twig, across, reach * (1 - at) * rng.nextDouble(.61, .93),
                    width * (i == 0 ? .47 : .29), 0, 9, lit, main.growth * (1 - at), peel + .07,
                    end - rng.nextDouble(.04, .095)));
            if (strong && i == 0) main.flare = joint;
        }
    }

    private Arc course(SplittableRandom rng, Vec3 root, Vec3 axis, Vec3 side, double length, double width,
                       double arch, int steps, double delay, double growth, double peel, double end) {
        double[] stations = new double[steps + 1], bend = new double[steps + 1], relief = new double[steps + 1];
        for (int i = 1; i <= steps; i++) stations[i] = stations[i - 1] + rng.nextDouble(.49, 1.55);
        double total = stations[steps];
        for (int i = 1; i <= steps; i++) stations[i] /= total;
        subdivide(stations, bend, 0, steps, .27, rng);
        subdivide(stations, relief, 0, steps, .105, rng);
        Vec3[] points = new Vec3[steps + 1];
        for (int i = 0; i <= steps; i++) {
            double u = stations[i];
            double bow = arch * Math.sin(Math.PI * Math.sqrt(u));
            points[i] = root.add(axis.scale(length * u)).add(side.scale(length * bend[i]))
                    .add(frame.travel().scale(length * (relief[i] + bow)));
        }
        return new Arc(points, width, delay, growth, peel, end, rng.nextInt(3), rng.nextDouble(.49, .71));
    }

    /** Long bends get short irregular fractures. Each new point subdivides the same parent path. */
    private static void subdivide(double[] station, double[] offset, int a, int b, double amplitude,
                                  SplittableRandom rng) {
        if (b - a < 2) return;
        int mid = (a + b) / 2;
        double interval = station[b] - station[a];
        double at = (station[mid] - station[a]) / interval;
        offset[mid] = offset[a] + (offset[b] - offset[a]) * at
                + amplitude * Math.pow(interval, .77) * rng.nextDouble(-1, 1);
        subdivide(station, offset, a, mid, amplitude, rng);
        subdivide(station, offset, mid, b, amplitude, rng);
    }

    @Override public int ticks() { return LIFETIME; }
    @Override public double radius() { return radius; }

    @Override public void draw(double t, Vec3 eye, Vertex out) {
        if (t < 0 || t >= 1) return;
        count = 0;
        double bloom = SlashHitGeometry.ramp((t - .036) / .16);
        double clear = SlashHitGeometry.ramp((t - .23) / .23);
        if (clear < 1) {
            double spread = 1 - .38 * clear;
            stamp(contact, frame.cut(), eye, (.14 + .267 * bloom) * spread, (.035 + .254 * bloom) * spread,
                    0, (float)((.73 + .27 * bloom) * (1 - clear)));
        }
        for (Arc arc : arcs) arc.draw(this, t, eye);
        // The vanilla translucent particle sheet tests and writes depth. Submit far sections first so a
        // translucent glow cannot cut a hole in a more distant fork of the same burst.
        Arrays.sort(quads, 0, count);
        for (int i = 0; i < count; i++) quads[i].emit(out);
    }

    private void stamp(Vec3 center, Vec3 along, Vec3 eye, double rx, double ry, int left, float alpha) {
        Vec3 normal = SlashHitGeometry.unit(eye.subtract(center), frame.travel().reverse());
        Vec3 guide = Math.abs(normal.y) < .85 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 axis = SlashHitGeometry.unit(along.subtract(normal.scale(normal.dot(along))), guide.cross(normal).normalize());
        Vec3 up = normal.cross(axis).scale(ry);
        axis = axis.scale(rx);
        Vec3 a = center.subtract(axis), b = center.add(axis);
        quads[count++].set(a.subtract(up), b.subtract(up), b.add(up), a.add(up),
                (left + 1) / LAST_TEXEL, (left + 110) / LAST_TEXEL, 254 / LAST_TEXEL, 145 / LAST_TEXEL, alpha, eye);
    }

    private static final class Arc {
        final Vec3[] points, tangents;
        final double[] distance;
        final double width, delay, growth, peel, end, breakAt;
        final float v0, v1;
        int flare = -1;

        Arc(Vec3[] points, double width, double delay, double growth, double peel, double end, int row, double breakAt) {
            this.points = points;
            this.width = width;
            this.delay = delay;
            this.growth = growth;
            this.peel = peel;
            this.end = end;
            this.breakAt = breakAt;
            v0 = (row * 48 + 1) / LAST_TEXEL;
            v1 = (row * 48 + 46) / LAST_TEXEL;
            int last = points.length - 1;
            tangents = new Vec3[points.length];
            distance = new double[points.length];
            for (int i = 0; i <= last; i++) {
                tangents[i] = points[Math.min(i + 1, last)].subtract(points[Math.max(0, i - 1)]).normalize();
                if (i > 0) distance[i] = distance[i - 1] + points[i].distanceTo(points[i - 1]);
            }
            double total = distance[last];
            for (int i = 1; i <= last; i++) distance[i] /= total;
        }

        void draw(BlackFlashImpact flash, double t, Vec3 eye) {
            if (t <= delay || t >= end) return;
            double charge = Math.clamp((t - delay) / growth, 0, 1);
            double head = charge * (2 - charge);
            double retreat = SlashHitGeometry.ramp((t - peel) / (end - peel));
            double tail = retreat, expansion = 1 + .125 * retreat;
            float opacity = (float)((.71 + .29 * charge) * (1 - retreat * .78));
            double gap = .115 * SlashHitGeometry.ramp((retreat - .19) / .47);
            if (gap > .0001) {
                strip(flash, tail, Math.min(head, breakAt - gap), expansion, retreat, opacity, eye);
                strip(flash, Math.max(tail, breakAt + gap), head, expansion, retreat, opacity, eye);
            } else {
                strip(flash, tail, head, expansion, retreat, opacity, eye);
            }
            if (flare >= 0 && head > distance[flare] && tail < distance[flare]) {
                double pulse = Math.max(0, 1 - Math.abs(t - (delay + growth * .57)) / .12);
                if (pulse > .15) {
                    // The hot lip catches one side of the bend; leave its dark centre intact.
                    Vec3 joint = points[flare].scale(expansion);
                    Vec3 lip = SlashHitGeometry.unit(eye.subtract(joint).cross(tangents[flare]), flash.frame.across());
                    joint = joint.add(lip.scale(width * .11));
                    flash.stamp(joint, tangents[flare], eye, width * (.42 + .32 * pulse),
                            width * (.20 + .18 * pulse), 128, (float)(pulse * .92));
                }
            }
        }

        void strip(BlackFlashImpact flash, double from, double to, double expansion, double retreat,
                   float opacity, Vec3 eye) {
            if (to - from < .0001) return;
            Edge previous = null;
            for (int i = 0; i < points.length - 1 && distance[i] < to; i++) {
                if (distance[i + 1] <= from) continue;
                if (previous == null) previous = edge(flash.frame, i, Math.max(from, distance[i]), from, to, expansion, retreat, eye);
                Edge next = edge(flash.frame, i, Math.min(to, distance[i + 1]), from, to, expansion, retreat, eye);
                flash.quads[flash.count++].set(previous.lo, next.lo, next.hi, previous.hi,
                        previous.u, next.u, v0, v1, opacity, eye);
                previous = next;
            }
        }

        Edge edge(Frame frame, int i, double at, double from, double to, double expansion, double retreat, Vec3 eye) {
            double fraction = (at - distance[i]) / (distance[i + 1] - distance[i]);
            Vec3 p = points[i].lerp(points[i + 1], fraction).scale(expansion);
            Vec3 tangent = tangents[i].lerp(tangents[i + 1], fraction);
            Vec3 side = SlashHitGeometry.unit(eye.subtract(p).cross(tangent), frame.across());
            double tip = Math.min(1, .025 + 8 * (to - at));
            if (from > .0001) tip *= Math.min(1, .025 + 11 * (at - from));
            // The contact stamp occupies the root. The broadest pockets open farther down each branch.
            double opening = .36 + .64 * SlashHitGeometry.ramp(at / .29);
            double breadth = width * opening * (.32 + .68 * (1 - at * at)) * (1 - .48 * retreat) * tip;
            side = side.scale(breadth);
            return new Edge(p.subtract(side), p.add(side), (float)((1 + 253 * at) / LAST_TEXEL));
        }
    }

    private record Edge(Vec3 lo, Vec3 hi, float u) {}

    private static final class Quad implements Comparable<Quad> {
        Vec3 a, b, c, d;
        float u0, u1, v0, v1, opacity;
        double depth;

        void set(Vec3 a, Vec3 b, Vec3 c, Vec3 d, float u0, float u1, float v0, float v1,
                 float opacity, Vec3 eye) {
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
            this.u0 = u0;
            this.u1 = u1;
            this.v0 = v0;
            this.v1 = v1;
            this.opacity = opacity;
            depth = eye.dot(a) + eye.dot(b) + eye.dot(c) + eye.dot(d);
        }

        @Override public int compareTo(Quad other) { return Double.compare(depth, other.depth); }

        void emit(Vertex out) {
            out.put(a, u0, v0, 0xffffff, opacity);
            out.put(b, u1, v0, 0xffffff, opacity);
            out.put(c, u1, v1, 0xffffff, opacity);
            out.put(d, u0, v1, 0xffffff, opacity);
        }
    }
}
