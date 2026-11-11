package dev.senluo.witchcraft.time.shape;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/** Pure, bounded whole-block geometry. World placement and tree ownership remain with the caller. */
public final class TreeShapeGenerator {
    // Only YEARS_TREE / YEARS_GIANT have world adapters. Other forms are studies for future larger stages.
    public enum Form { YEARS_TREE, YEARS_GIANT, GIANT_FORKED, GIANT_BAOBAB, GIANT_BUTTRESSED, GIANT_ARCHED,
        ROUND, UMBRELLA, COLUMNAR, FORKED }
    public enum Material { WOOD, LEAF }
    public enum Axis { X, Y, Z }
    public record Position(int x, int y, int z) {
        public Position offset(int dx, int dy, int dz) { return new Position(x + dx, y + dy, z + dz); }
    }
    public record Part(Material material, Axis axis, int phase, boolean required) {
        public Part {
            Objects.requireNonNull(material);
            Objects.requireNonNull(axis);
            if (phase < 0 || phase > 3 || required && material != Material.WOOD)
                throw new IllegalArgumentException("Invalid tree part");
        }
    }
    public record Plan(Map<Position, Part> parts) {
        public Plan {
            if (parts.size() > 8192) throw new IllegalArgumentException("Tree exceeds the part budget");
            for (var entry : parts.entrySet()) {
                Position p = Objects.requireNonNull(entry.getKey());
                Objects.requireNonNull(entry.getValue());
                if (p.x < -32 || p.x > 32 || p.z < -32 || p.z > 32 || p.y < -8 || p.y > 48
                        || p.x == 0 && p.y == 0 && p.z == 0)
                    throw new IllegalArgumentException("Tree exceeds the reserved geometry envelope");
            }
            parts = Collections.unmodifiableMap(new LinkedHashMap<>(parts));
        }
    }

    private TreeShapeGenerator() {}

    /** Identical form and seed produce identical ordered parts; the two established forms ignore seed. */
    public static Plan generate(Form form, long seed) {
        Objects.requireNonNull(form);
        Builder b = new Builder();
        Random random = new Random(seed);
        switch (form) {
            case YEARS_TREE -> yearsTree(b);
            case YEARS_GIANT -> yearsGiant(b);
            case GIANT_FORKED -> giantForked(b, random);
            case GIANT_BAOBAB -> giantBaobab(b, random);
            case GIANT_BUTTRESSED -> giantButtressed(b, random);
            case GIANT_ARCHED -> giantArched(b, random);
            case ROUND -> round(b, random);
            case UMBRELLA -> umbrella(b, random);
            case COLUMNAR -> columnar(b, random);
            case FORKED -> forked(b, random);
        }
        return new Plan(b.parts);
    }

    private static void yearsTree(Builder b) {
        for (int y = 1; y <= 4; y++) b.wood(new Position(0, y, 0), Axis.Y, 0, false);
        for (int y = 3; y <= 5; y++) {
            int radius = y == 5 ? 1 : 2;
            for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
                if (Math.abs(x) == radius && Math.abs(z) == radius) continue;
                b.leaf(new Position(x, y, z), 0);
            }
        }
    }

    private static void yearsGiant(Builder b) {
        // These established coordinates, phases and insertion order preserve saved growth and fruit sites.
        for (int y = 0; y < 12; y++) for (int x = -1; x <= 1; x++) for (int z = 0; z <= 2; z++) {
            if (x == 0 && y == 0 && z == 0) continue;
            b.wood(new Position(x, y, z), Axis.Y, y < 2 ? 0 : 1, y < 2 || x == 0 && z == 1 && y < 8);
        }
        Builder small = new Builder();
        yearsTree(small);
        small.parts.forEach(b.parts::putIfAbsent);
        int[][] tips = {{7,12,3},{-7,14,4},{3,16,8},{-3,17,-5},{6,10,-5},{-6,5,-2},{1,17,1}};
        for (int i = 0; i < tips.length; i++) {
            int[] t = tips[i];
            Position end = new Position(t[0], t[1], t[2]);
            b.branch(new Position(0, i == 5 ? 4 : Math.min(10, t[1] - 3), 1), end, 2);
            b.crown(end, i == 5 ? 3 : i == 6 ? 5 : 4, i == 5 ? 3 : 4, 3, .78, 1, .86, .48);
        }
        // A branch may cross the trunk, but must not turn the trunk's bark sideways.
        // Keep existing coordinates, insertion order, phases and required flags.
        b.parts.replaceAll((p, part) -> part.material == Material.WOOD
                && p.y > 0 && p.y < 12 && Math.abs(p.x) <= 1 && p.z >= 0 && p.z <= 2
                ? new Part(part.material, Axis.Y, part.phase, part.required) : part);
        b.crown(new Position(0, 13, 1), 5, 5, 3, .78, 1, .86, .48);
        b.crown(new Position(-1, 15, 2), 5, 4, 3, .78, 1, .86, .48);
        for (int x = -5; x <= -2; x++) for (int z = -1; z <= 1; z++) {
            b.leaf(new Position(x, 5, z), 3);
            if (z == 0 && x > -5) b.leaf(new Position(x, 6, z), 3);
        }
        int[][] roots = {{5,-3,2},{-5,-3,3},{3,-3,6},{-2,-3,-4}};
        for (int[] end : roots) {
            Position bend = new Position(Integer.signum(end[0]), 0, 1 + Integer.signum(end[2] - 1));
            b.branch(new Position(0, 0, 1), bend, 0);
            b.branch(bend, new Position(end[0], end[1], end[2]), 0);
        }
        b.parts.remove(new Position(0, 0, -1));
        b.parts.remove(new Position(0, 1, -1));
    }

    private static void round(Builder b, Random random) {
        int height = 7 + random.nextInt(3);
        b.trunk(height);
        b.crown(new Position(0, height, 0), 4, 4, 3, .55, .9, 1, .85, .5);
        double offset = random.nextDouble() * Math.PI * 2;
        for (int i = 0; i < 5; i++) {
            Position tip = radial(3 + random.nextInt(2), offset + i * Math.PI * 2 / 5, height - 3 + random.nextInt(3));
            b.branch(new Position(0, height - 4, 0), tip, 2);
            b.crown(tip, 3, 3, 3, .55, 1, .85, .45);
        }
    }

    private static void umbrella(Builder b, Random random) {
        int height = 9 + random.nextInt(3);
        b.trunk(height - 2);
        double offset = random.nextDouble() * Math.PI * 2;
        for (int i = 0; i < 5; i++) {
            Position tip = radial(4 + random.nextInt(2), offset + i * Math.PI * 2 / 5, height + random.nextInt(2));
            b.branch(new Position(0, height - 4, 0), tip, 2);
            b.crown(tip, 4, 3, 3, .72, 1, .6);
        }
        b.crown(new Position(0, height, 0), 4, 4, 3, .7, 1, .6);
    }

    private static void columnar(Builder b, Random random) {
        int height = 16 + random.nextInt(5);
        b.trunk(height);
        double angle = random.nextDouble() * Math.PI * 2;
        for (int y = 5; y < height; y += 2) {
            int radius = y > height - 4 ? 1 : 2;
            Position tip = radial(radius, angle, y + 1);
            b.branch(new Position(0, y - 2, 0), tip, 2);
            b.crown(tip, 2, 2, 3, .6, 1, .7);
            angle += 2.4;
        }
        b.crown(new Position(0, height, 0), 1, 1, 3, 1, .4);
    }

    private static Position radial(int radius, double angle, int y) {
        return new Position((int) Math.round(radius * Math.cos(angle)), y, (int) Math.round(radius * Math.sin(angle)));
    }

    private static void forked(Builder b, Random random) {
        int height = 5 + random.nextInt(2);
        b.trunk(height);
        double angle = random.nextDouble() * Math.PI * 2;
        Position fork = new Position(0, height, 0);
        split(b, random, fork, angle, 4, 2);
        split(b, random, fork, angle + Math.PI, 4, 2);
    }

    private static void split(Builder b, Random random, Position start, double angle, int length, int depth) {
        Position delta = radial(length, angle, 2 + random.nextInt(2));
        Position end = start.offset(delta.x, delta.y, delta.z);
        b.branch(start, end, 2);
        if (depth == 0) {
            b.crown(end, 3, 3, 3, .6, 1, .75, .35);
        } else {
            // A small, explicit depth budget makes hierarchical forks independent of world tick frequency.
            split(b, random, end, angle - .75 - random.nextDouble() * .2, length - 1, depth - 1);
            split(b, random, end, angle + .75 + random.nextDouble() * .2, length - 1, depth - 1);
        }
    }

    // Giant candidates are independent shape policies, not scaled versions of the ordinary presets.
    private static void giantForked(Builder b, Random random) {
        int forkHeight = 6 + random.nextInt(2);
        for (int y = 0; y <= forkHeight; y++) b.section(new Position(0, y, 1), Axis.Y, y < 3 ? 2.2 : 1.5, y < 2 ? 0 : 1);
        double angle = random.nextDouble() * Math.PI * 2;
        buttresses(b, angle, 5, 3);
        for (int i = 0; i < 3; i++) {
            double heading = angle + i * Math.PI * 2 / 3 + (random.nextDouble() - .5) * .35;
            Position fork = radial(6 + random.nextInt(2), heading, forkHeight + 6 + random.nextInt(2)).offset(0, 0, 1);
            b.limb(new Position(0, forkHeight - 2, 1), fork, 1.5, 1.1, 2);
            for (int side : new int[]{-1, 1}) {
                Position delta = radial(5 + random.nextInt(2), heading + side * .65, 3 + random.nextInt(3));
                Position tip = fork.offset(delta.x, delta.y, delta.z);
                b.limb(fork, tip, 1.1, 0, 2);
                b.crown(tip, 5, 4, 3, .45, .78, 1, .92, .68, .32);
            }
            b.crown(fork.offset(0, 2, 0), 4, 4, 3, .5, .9, 1, .7, .3);
        }
        reserveCore(b);
    }

    private static void giantBaobab(Builder b, Random random) {
        int shoulder = 15 + random.nextInt(3);
        for (int y = 0; y <= shoulder; y++) {
            double radius = y < 3 ? 2.6 : y < shoulder - 5 ? 3.2 : y < shoulder - 2 ? 2.3 : 1.5;
            b.section(new Position(0, y, 1), Axis.Y, radius, y < 2 ? 0 : 1);
        }
        double angle = random.nextDouble() * Math.PI * 2;
        buttresses(b, angle, 5, 2);
        for (int i = 0; i < 5; i++) {
            double heading = angle + i * Math.PI * 2 / 5 + (random.nextDouble() - .5) * .3;
            Position elbow = radial(5 + random.nextInt(2), heading, shoulder + 2 + random.nextInt(2)).offset(0, 0, 1);
            b.limb(new Position(0, shoulder - 2, 1), elbow, 1.5, 1.0, 2);
            for (int side : new int[]{-1, 1}) {
                Position d = radial(3 + random.nextInt(2), heading + side * .65, 4 + random.nextInt(2));
                Position tip = elbow.offset(d.x, d.y, d.z);
                b.limb(elbow, tip, 1.0, 0, 2);
                b.crown(tip, 3, 3, 3, .55, 1, .9, .5);
            }
        }
        Position centerTip = new Position(-1, shoulder + 6, 2);
        b.limb(new Position(0, shoulder, 1), centerTip, 1.1, 0, 2);
        b.crown(centerTip, 4, 3, 3, .45, .85, 1, .65);
        reserveCore(b);
    }

    private static void giantButtressed(Builder b, Random random) {
        int height = 24 + random.nextInt(3);
        for (int y = 0; y <= height; y++) {
            Position center = new Position(y < 13 ? 0 : y < 21 ? 1 : 2, y, 1);
            b.section(center, Axis.Y, y < 4 ? 2.2 : y < height - 3 ? 1.5 : .5, y < 2 ? 0 : 1);
        }
        double angle = random.nextDouble() * Math.PI * 2;
        buttresses(b, angle, 8, 6);
        for (int i = 0; i < 5; i++) {
            double heading = angle + i * 2.38 + random.nextDouble() * .25;
            int y = height - 7 + i;
            Position start = new Position(y < 21 ? 1 : 2, y, 1);
            Position tip = radial(8 + random.nextInt(4), heading, height - 2 + random.nextInt(5)).offset(1, 0, 1);
            b.limb(start, tip, 1.2, .5, 2);
            b.crown(tip, 5, 4, 3, .5, .85, 1, .85, .4);
            Position d = radial(4, heading + (i % 2 == 0 ? .9 : -.9), 2);
            Position twig = tip.offset(d.x, d.y, d.z);
            b.limb(tip, twig, .5, 0, 2);
            b.crown(twig, 4, 3, 3, .55, 1, .8, .3);
        }
        b.crown(new Position(2, height + 1, 1), 5, 4, 3, .55, .9, 1, .7, .3);
        reserveCore(b);
    }

    private static void giantArched(Builder b, Random random) {
        double angle = random.nextDouble() * Math.PI * 2;
        Position base = new Position(0, 0, 1);
        Position fork = radial(3, angle + Math.PI, 9 + random.nextInt(2)).offset(0, 0, 1);
        b.limb(base, fork, 2.2, 1.5, 1);
        buttresses(b, angle, 6, 4);
        for (int i = 0; i < 3; i++) {
            double heading = angle + (i == 0 ? 0 : i == 1 ? 2.4 : -1.8);
            int reach = i == 0 ? 14 : 8;
            Position elbow = radial(reach / 2, heading, 14 + i).offset(0, 0, 1);
            Position tip = radial(reach, heading, 16 + random.nextInt(3)).offset(0, 0, 1);
            b.limb(fork, elbow, 1.5, 1.1, 2);
            b.limb(elbow, tip, 1.1, .5, 2);
            for (int side : new int[]{-1, 1}) {
                Position d = radial(4 + random.nextInt(2), heading + side * .9, 3 + random.nextInt(2));
                Position twig = tip.offset(d.x, d.y, d.z);
                b.limb(tip, twig, .5, 0, 2);
                b.crown(twig, 5, 4, 3, .45, .8, 1, .8, .4);
            }
            b.crown(elbow.offset(0, 2, 0), 4, 3, 3, .5, 1, .8, .3);
        }
        reserveCore(b);
    }

    private static void buttresses(Builder b, double angle, int reach, int height) {
        for (int i = 0; i < 4; i++) {
            Position end = radial(reach, angle + i * Math.PI / 2, 0).offset(0, 0, 1);
            // Solid triangular fins: lower courses extend further from the trunk.
            for (int y = 0; y < height; y++) {
                double fraction = 1.0 - (double) y / height;
                Position tip = new Position((int) Math.round(end.x * fraction), y, 1 + (int) Math.round((end.z - 1) * fraction));
                b.limb(new Position(0, y, 1), tip, y < 2 ? 1 : 0, 0, 0);
            }
        }
    }

    private static void reserveCore(Builder b) {
        b.parts.remove(new Position(0, 0, 0));
        b.parts.remove(new Position(0, 0, -1));
        b.parts.remove(new Position(0, 1, -1));
    }

    private static final class Builder {
        private final Map<Position, Part> parts = new LinkedHashMap<>();

        void wood(Position p, Axis axis, int phase, boolean required) {
            Part old = parts.get(p);
            parts.put(p, new Part(Material.WOOD, axis, phase, required || old != null && old.required));
        }
        void leaf(Position p, int phase) { parts.putIfAbsent(p, new Part(Material.LEAF, Axis.Y, phase, false)); }
        void trunk(int height) {
            for (int y = 1; y <= height; y++) wood(new Position(0, y, 0), Axis.Y, y <= 2 ? 0 : 1, y <= 3);
        }
        void section(Position center, Axis axis, double radius, int phase) {
            int extent = (int) Math.floor(radius);
            for (int u = -extent; u <= extent; u++) for (int v = -extent; v <= extent; v++) {
                if (u * u + v * v > radius * radius) continue;
                Position p = switch (axis) {
                    case X -> center.offset(0, u, v);
                    case Y -> center.offset(u, 0, v);
                    case Z -> center.offset(u, v, 0);
                };
                wood(p, axis, phase, phase == 0 && p.y >= 0 && p.x == 0 && p.z == 1);
            }
        }
        void limb(Position from, Position to, double startRadius, double endRadius, int phase) {
            int dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
            int steps = Math.max(1, Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) * 2);
            Axis axis = Math.abs(dy) >= Math.abs(dx) && Math.abs(dy) >= Math.abs(dz) ? Axis.Y
                    : Math.abs(dx) >= Math.abs(dz) ? Axis.X : Axis.Z;
            Position previous = from;
            for (int i = 0; i <= steps; i++) {
                double t = (double) i / steps;
                Position next = from.offset((int) Math.round(dx * t), (int) Math.round(dy * t), (int) Math.round(dz * t));
                double radius = startRadius + (endRadius - startRadius) * t;
                // Bridge every rounded diagonal with face-adjacent wood; no corner-only attachments.
                while (!previous.equals(next)) {
                    previous = previous.x != next.x ? previous.offset(Integer.signum(next.x - previous.x), 0, 0)
                            : previous.z != next.z ? previous.offset(0, 0, Integer.signum(next.z - previous.z))
                            : previous.offset(0, Integer.signum(next.y - previous.y), 0);
                    section(previous, axis, radius, phase);
                }
                section(next, axis, radius, phase);
            }
        }
        void branch(Position from, Position to, int phase) {
            Position p = from;
            while (!p.equals(to)) {
                int dx = to.x - p.x, dy = to.y - p.y, dz = to.z - p.z;
                Axis axis = Math.abs(dx) >= Math.abs(dy) && Math.abs(dx) >= Math.abs(dz) ? Axis.X
                        : Math.abs(dz) >= Math.abs(dy) ? Axis.Z : Axis.Y;
                p = p.offset(axis == Axis.X ? Integer.signum(dx) : 0, axis == Axis.Y ? Integer.signum(dy) : 0,
                        axis == Axis.Z ? Integer.signum(dz) : 0);
                wood(p, axis, phase, false);
            }
        }
        void crown(Position center, int rx, int rz, int phase, double... widths) {
            for (int layer = 0; layer < widths.length; layer++)
                for (int x = -rx; x <= rx; x++) for (int z = -rz; z <= rz; z++) {
                    double edge = (double) (x * x) / (rx * rx) + (double) (z * z) / (rz * rz);
                    if (edge <= widths[layer] * widths[layer]) leaf(center.offset(x, layer - 1, z), phase);
                }
        }
    }
}
