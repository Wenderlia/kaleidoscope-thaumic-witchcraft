package dev.senluo.witchcraft.motion;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Bounded geometric planning; world adapters decide the metric and commit collision prefixes. */
public final class PathTravel {
    public static final int MAX_STEPS = MotionPath.MAX_SEGMENTS + 64;
    @FunctionalInterface public interface Space {
        TravelStep quote(Vec3 from, Vec3 direction, double budget, double maximum);
    }
    public record Segment(TravelStep travel, double pathStart) {}
    public record Plan(List<Segment> segments, boolean limited) {}
    private PathTravel() {}

    public static Plan plan(MotionPath path, Vec3 origin, double progress, double budget, Space space) {
        if (!MotionPath.finite(origin) || !Double.isFinite(progress) || progress < 0 || !Double.isFinite(budget) || budget < 0)
            throw new IllegalArgumentException("Invalid path planning state");
        List<Segment> segments = new ArrayList<>();
        double remaining = budget, cursor = progress;
        for (int i = 0; remaining > 1.0E-9 && i < MAX_STEPS; i++) {
            double maximum = path.nextDistance(cursor, remaining) - cursor;
            TravelStep step = space.quote(origin.add(path.at(cursor)), path.direction(cursor), remaining, maximum);
            if (!(step.work() > 0) || step.work() > remaining + 1.0E-7 || step.distance() > maximum + 1.0E-7)
                throw new IllegalStateException("Spatial quote exceeded path budget");
            segments.add(new Segment(step, cursor));
            cursor = step.distance() == maximum ? path.nextDistance(cursor, remaining) : cursor + step.distance();
            remaining = Math.max(0, remaining - step.work());
        }
        return new Plan(List.copyOf(segments), remaining > 1.0E-7);
    }
}
