package dev.senluo.witchcraft.slash;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Immediate, open crossings; no delayed clicks, retroactive rotation, or visual-only collision offsets. */
public final class SlashFormation {
    public static final double FAN_SLOPE = .085;
    public static final int WINDOW_TICKS = 12;
    private static final Map<ServerPlayer, Pose> LAST = new WeakHashMap<>();
    public record Pose(Vec3 direction, float roll, Vec3 aim, long tick, int index, ResourceKey<Level> dimension,
                       boolean gestureDirected) {}
    private SlashFormation() {}

    public static Pose preview(ServerPlayer player) {
        Vec3 aim = player.getLookAngle().normalize();
        long tick = player.server.getTickCount();
        Pose last = LAST.get(player);
        boolean continuing = last != null && last.dimension.equals(player.level().dimension())
                && tick - last.tick <= WINDOW_TICKS && last.aim.dot(aim) > .94;
        int index = continuing ? (last.index + 1) & 15 : 0;
        // Each new line crosses at a clear angle; avoid almost parallel white bundles.
        float roll = continuing ? last.roll + (float) Math.toRadians(61 + player.getRandom().nextFloat() * 28)
                : player.getRandom().nextFloat() * (float) (Math.PI * 2);
        var gesture = SlashGesture.resolve(player, aim, roll);
        roll = (float) (gesture.roll() % (Math.PI * 2));
        Vec3 direction = aim;
        if (continuing && !gesture.directed()) {
            Vec3 across = aim.cross(SlashGeometry.blade(aim, roll)).normalize();
            double spread = FAN_SLOPE * ((index & 1) == 0 ? -1 : 1);
            direction = aim.add(across.scale(spread)).normalize();
        }
        return new Pose(direction, roll, aim, tick, index, player.level().dimension(), gesture.directed());
    }

    public static void commit(ServerPlayer player, Pose pose) { LAST.put(player, pose); }
}
