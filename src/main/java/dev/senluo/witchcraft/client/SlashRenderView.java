package dev.senluo.witchcraft.client;

import dev.senluo.witchcraft.compat.thaumcraft.SlashClientBridge;
import dev.senluo.witchcraft.slash.SlashEntity;
import net.minecraft.world.phys.Vec3;

/** Per-blade transient rendering state; contains no entity/world reference and no gameplay clock. */
final class SlashRenderView {
    private static final double EDGE_ON_START = .02, EDGE_ON_END = .15;
    private final Vec3 releaseOffset;
    private Vec3[] sides = new Vec3[0];

    SlashRenderView(SlashEntity slash) { releaseOffset = SlashClientBridge.captureLaunchOffset(slash); }
    Vec3 launchOffset(SlashEntity slash, float partial) { return SlashClientBridge.launchOffset(slash, partial, releaseOffset); }

    void segments(int count) { if (sides.length != count) sides = new Vec3[count]; }

    /** Keep the side of an unoriented billboard continuous, including a camera looking along its blade. */
    Vec3 across(int segment, Vec3 axis, Vec3 towardCamera, Vec3 forward) {
        Vec3 prior = sides[segment];
        Vec3 reference = prior == null ? axis.cross(forward) : prior.subtract(axis.scale(prior.dot(axis)));
        if (reference.lengthSqr() < 1.0E-8) reference = axis.cross(forward);
        if (reference.lengthSqr() < 1.0E-8)
            reference = axis.cross(Math.abs(axis.y) > .9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0));
        reference = reference.normalize();
        Vec3 facing = axis.cross(towardCamera);
        double length = facing.length();
        if (length > EDGE_ON_START) {
            facing = facing.scale(1 / length);
            if (facing.dot(reference) < 0) facing = facing.scale(-1);
            double t = Math.clamp((length - EDGE_ON_START) / (EDGE_ON_END - EDGE_ON_START), 0, 1);
            reference = reference.lerp(facing, t*t*(3-2*t)).normalize();
        }
        sides[segment] = reference;
        return reference;
    }
}
