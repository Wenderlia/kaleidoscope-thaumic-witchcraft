package dev.senluo.witchcraft.client;

import dev.senluo.witchcraft.slash.drawing.StrokeSphere;
import dev.senluo.witchcraft.slash.drawing.StrokeTrace;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Drawing feedback in world-relative coordinates, before clipping to the current camera. */
final class StrokeGuideMesh {
    interface Sink {
        void quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int alpha, int red, int green, int blue);
    }

    static void draw(StrokeSphere sphere, StrokeTrace trace, Vec3 liveTip, Sink sink) {
        draw(sink, sphere, trace.raw(), .013, 86, 127, 177, 196);
        draw(sink, sphere, trace.fitted(), .023, 174, 205, 221, 230);
        if (liveTip != null && trace.ticks() < StrokeTrace.MAX_TICKS) {
            Vec3 end = trace.fitted().getLast();
            line(sink, sphere, end, liveTip, .023, 174, 205, 221, 230);
            // The light follows only the last .09 blocks of the live motion; it adds no input samples.
            double length = StrokeSphere.arc(end, liveTip);
            if (length > .002) {
                Vec3 tipStart = StrokeSphere.between(end, liveTip, Math.max(0, 1 - .09 / length)).scale(StrokeSphere.RADIUS);
                line(sink, sphere, tipStart, liveTip, .014, 205, 232, 240, 237);
            }
        }
    }

    private static void draw(Sink sink, StrokeSphere sphere, List<Vec3> points,
                             double thickness, int alpha, int red, int green, int blue) {
        for (int i = 1; i < points.size(); i++)
            line(sink, sphere, points.get(i - 1), points.get(i), thickness, alpha, red, green, blue);
    }

    private static void line(Sink sink, StrokeSphere sphere, Vec3 from, Vec3 to,
                             double thickness, int alpha, int red, int green, int blue) {
        double angle = StrokeSphere.arc(from, to) / StrokeSphere.RADIUS;
        int count = Math.max(1, (int) Math.ceil(angle / .08));
        Vec3 a = from;
        for (int n = 1; n <= count; n++) {
            Vec3 b = n == count ? to : StrokeSphere.between(from, to, (double) n / count).scale(StrokeSphere.RADIUS);
            segment(sink, sphere, a, b, thickness, alpha, red, green, blue); a = b;
        }
    }

    private static void segment(Sink sink, StrokeSphere sphere, Vec3 from, Vec3 to,
                                double thickness, int alpha, int red, int green, int blue) {
        Vec3 delta = to.subtract(from);
        if (delta.lengthSqr() < 1.0E-8) return;
        Vec3 across = delta.cross(from.add(to)).normalize().scale(thickness);
        sink.quad(sphere.offset(from.subtract(across)), sphere.offset(to.subtract(across)),
                sphere.offset(to.add(across)), sphere.offset(from.add(across)), alpha, red, green, blue);
    }

    private StrokeGuideMesh() {}
}
