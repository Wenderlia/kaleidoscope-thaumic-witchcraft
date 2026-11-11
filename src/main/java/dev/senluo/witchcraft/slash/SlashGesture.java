package dev.senluo.witchcraft.slash;

import dev.senluo.witchcraft.motion.ViewTurn;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Resolve new blade roll and path eligibility together; never alter aim or an emitted blade. */
public final class SlashGesture {
    public record Selection(float roll, boolean directed) {}
    private record Track(ResourceKey<Level> dimension, ViewTurn turn) {}
    private static final Map<ServerPlayer, Track> TRACKS = new WeakHashMap<>();
    private SlashGesture() {}

    public static void clear(ServerPlayer player) { TRACKS.remove(player); }

    public static void sample(ServerPlayer player) {
        Track track = TRACKS.get(player);
        if (track == null || !track.dimension.equals(player.level().dimension())) {
            track = new Track(player.level().dimension(), new ViewTurn());
            TRACKS.put(player, track);
        }
        track.turn.sample(player.server.getTickCount(), player.getYRot(), player.getXRot());
    }

    public static Selection resolve(ServerPlayer player, Vec3 aim, float fallback) {
        sample(player);
        var gesture = TRACKS.get(player).turn.gesture(player.server.getTickCount());
        if (gesture.strength() <= 0) return new Selection(fallback, false);
        double yaw = Math.toRadians(player.getYRot());
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3 up = right.cross(aim).normalize();
        Vec3 motion = right.scale(gesture.horizontal()).add(up.scale(gesture.vertical()));
        double angle = Math.atan2(motion.dot(SlashGeometry.blade(aim, (float) (Math.PI / 2))),
                motion.dot(SlashGeometry.blade(aim, 0)));
        // A blade is an unoriented line: select the nearest equivalent axis modulo pi.
        double difference = angle - fallback;
        difference -= Math.PI * Math.floor((difference + Math.PI / 2) / Math.PI);
        // Once intent clears the dead zone, random formation cannot dilute a horizontal/vertical stroke.
        float roll = (float) (fallback + difference);
        return new Selection(roll, true);
    }
}
