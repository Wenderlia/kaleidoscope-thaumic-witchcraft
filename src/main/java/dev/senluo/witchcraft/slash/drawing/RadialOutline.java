package dev.senluo.witchcraft.slash.drawing;

import dev.senluo.witchcraft.slash.SlashGeometry;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.world.phys.Vec3;

/** Immutable angular shape. Baked once, reused by collision, rendering and save restoration. */
public final class RadialOutline {
    public static final int MAX_PARTS = 512;
    public record Edge(Vec3 first, Vec3 last, Vec3 middle, Vec3 axis, double half) {
        public SlashGeometry.Sweep sweep(Vec3 center, double before, double after) {
            return new SlashGeometry.Sweep(center.add(middle.scale(before)), center.add(middle.scale(after)),
                    axis, half * before, half * after);
        }
    }
    private final List<Vec3> directions;
    private final List<Edge> edges;
    private final double initialRadius;

    private RadialOutline(List<Vec3> directions, double initialRadius) {
        if (directions.size() < 2 || directions.size() > MAX_PARTS + 1) throw new IllegalArgumentException("Invalid radial outline count");
        if (!Double.isFinite(initialRadius) || initialRadius <= 0 || initialRadius > 16)
            throw new IllegalArgumentException("Invalid radial initial radius");
        this.initialRadius = initialRadius;
        this.directions = List.copyOf(directions);
        List<Edge> result = new ArrayList<>();
        for (Vec3 p : directions)
            if (!Double.isFinite(p.lengthSqr()) || Math.abs(p.lengthSqr() - 1) > 1.0E-6)
                throw new IllegalArgumentException("Invalid radial direction");
        for (int i = 1; i < directions.size(); i++) {
            Vec3 a = directions.get(i - 1), b = directions.get(i), delta = b.subtract(a);
            if (delta.lengthSqr() < 1.0E-12 || a.dot(b) < .95) throw new IllegalArgumentException("Invalid radial edge");
            result.add(new Edge(a, b, a.add(b).scale(.5), delta.normalize(), delta.length() * .5));
        }
        edges = List.copyOf(result);
    }

    public static RadialOutline from(StrokeSphere sphere, StrokeTrace trace, double range) {
        List<Vec3> source = trace.fitted().stream().map(sphere::offset).map(Vec3::normalize).toList();
        // Aim for <= 0.04 block sagitta at the far edge, with a hard, per-stroke geometry budget.
        double step = Math.min(Math.PI / 16, 2 * Math.acos(1 - .04 / (StrokeSphere.RADIUS + range)));
        int count;
        for (;;) {
            count = 0;
            for (int i = 1; i < source.size(); i++) {
                double angle = Math.acos(Math.clamp(source.get(i - 1).dot(source.get(i)), -1, 1));
                if (angle >= 1.0E-6) count += Math.max(1, (int) Math.ceil(angle / step));
            }
            if (count <= MAX_PARTS) break;
            step *= 1.25;
            if (step > .3) throw new IllegalArgumentException("Radial geometry budget exceeded");
        }
        List<Vec3> points = new ArrayList<>(count + 1); points.add(source.getFirst());
        for (int i = 1; i < source.size(); i++) {
            Vec3 a = source.get(i - 1), b = source.get(i);
            double angle = Math.acos(Math.clamp(a.dot(b), -1, 1));
            if (angle < 1.0E-6) continue;
            int divisions = Math.max(1, (int) Math.ceil(angle / step));
            for (int n = 1; n <= divisions; n++) points.add(n == divisions ? b : StrokeSphere.between(a, b, (double) n / divisions));
        }
        return new RadialOutline(points, StrokeSphere.RADIUS);
    }
    public List<Edge> edges() { return edges; }
    public double initialRadius() { return initialRadius; }
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); ListTag points = new ListTag();
        tag.putInt("GeometryVersion", 1); tag.putDouble("InitialRadius", initialRadius);
        for (Vec3 p : directions) {
            CompoundTag point = new CompoundTag(); point.putDouble("X", p.x); point.putDouble("Y", p.y); point.putDouble("Z", p.z); points.add(point);
        }
        tag.put("Directions", points); return tag;
    }
    public static RadialOutline load(CompoundTag tag) {
        if (!tag.contains("GeometryVersion", Tag.TAG_INT) || tag.getInt("GeometryVersion") != 1
                || !tag.contains("InitialRadius", Tag.TAG_ANY_NUMERIC))
            throw new IllegalArgumentException("Unsupported radial geometry");
        double initialRadius = tag.getDouble("InitialRadius");
        ListTag points = tag.getList("Directions", Tag.TAG_COMPOUND);
        if (points.size() < 2 || points.size() > MAX_PARTS + 1) throw new IllegalArgumentException("Invalid radial outline count");
        List<Vec3> directions = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            CompoundTag point = points.getCompound(i);
            for (String key : new String[]{"X", "Y", "Z"})
                if (!point.contains(key, Tag.TAG_ANY_NUMERIC)) throw new IllegalArgumentException("Incomplete radial direction");
            directions.add(new Vec3(point.getDouble("X"), point.getDouble("Y"), point.getDouble("Z")));
        }
        return new RadialOutline(directions, initialRadius);
    }
}
