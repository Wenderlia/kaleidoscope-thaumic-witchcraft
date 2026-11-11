package dev.senluo.witchcraft.accessory;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** A captured native travel request mapped onto one authoritative path. Never a second simulated entity. */
public record FlightPath(boolean active, Vec3 start, Vec3 limit, Vec3 input, long epoch,
        float yaw, float pitch, double rate) {
    public static final double MAPPING_WIDTH = 4;
    public static final FlightPath FREE = new FlightPath(false, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, 0, 0, 0, 1);
    public static final StreamCodec<RegistryFriendlyByteBuf, FlightPath> STREAM_CODEC = StreamCodec.of((buf, path) -> {
        buf.writeBoolean(path.active);
        if (!path.active) return;
        write(buf, path.start); write(buf, path.limit); write(buf, path.input);
        buf.writeLong(path.epoch); buf.writeFloat(path.yaw); buf.writeFloat(path.pitch);
        buf.writeDouble(path.rate);
    }, buf -> {
        if (!buf.readBoolean()) return FREE;
        Vec3 start = read(buf), limit = read(buf), input = read(buf);
        long epoch = buf.readLong();
        float yaw = buf.readFloat(), pitch = buf.readFloat();
        double rate = buf.readDouble();
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || (rate != 1 && rate != .5))
            throw new IllegalArgumentException("Invalid constrained flight orientation");
        return new FlightPath(true, start, limit, input, epoch, yaw, pitch, rate);
    });

    public static FlightPath begin(Entity entity, Vec3 limit) {
        return begin(entity, limit, entity.getDeltaMovement());
    }

    public static FlightPath begin(Entity entity, Vec3 limit, Vec3 input) {
        return begin(entity, limit, input, 1);
    }

    public static FlightPath begin(Entity entity, Vec3 limit, Vec3 input, double rate) {
        return new FlightPath(true, entity.position(), limit, input, entity.level().getGameTime(),
                entity.getYRot(), entity.getXRot(), rate);
    }

    public Vec3 at(double gameTime) {
        double travel = input.length() * Math.max(0, gameTime - epoch) * rate;
        return MotionConstraints.approachByTravel(start, limit, travel, MAPPING_WIDTH);
    }

    private static void write(RegistryFriendlyByteBuf buf, Vec3 value) {
        buf.writeDouble(value.x); buf.writeDouble(value.y); buf.writeDouble(value.z);
    }

    private static Vec3 read(RegistryFriendlyByteBuf buf) {
        Vec3 value = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        if (!Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z)
                || Math.abs(value.x) > 60_000_000 || Math.abs(value.z) > 60_000_000 || Math.abs(value.y) > 60_000_000)
            throw new IllegalArgumentException("Invalid constrained flight position");
        return value;
    }
}
