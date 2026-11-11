package dev.senluo.witchcraft.slash;

import dev.senluo.witchcraft.motion.BezierSpeed;

/** Cast-time spatial specification. Remaining potential never resizes an already emitted blade. */
public record SlashShape(double range, double initialLength, double finalLength) {
    public static final double MAX_RANGE = 32768, MAX_LENGTH = 8192, MAX_SPEED = 327680;
    public static final SlashShape DEFAULT = new SlashShape(24, 1.5, 8);

    public SlashShape {
        if (!Double.isFinite(range) || range <= 0 || range > MAX_RANGE
                || !Double.isFinite(initialLength) || initialLength <= 0
                || !Double.isFinite(finalLength) || finalLength < initialLength || finalLength > MAX_LENGTH)
            throw new IllegalArgumentException("Invalid slash dimensions");
    }

    public double halfLength(double work) {
        return (initialLength + (finalLength - initialLength) * Math.clamp(work / range, 0, 1)) * .5;
    }

    public BezierSpeed speed(double startPerSecond, double endPerSecond) {
        if (!Double.isFinite(startPerSecond) || !Double.isFinite(endPerSecond)
                || startPerSecond <= 0 || endPerSecond < startPerSecond || endPerSecond > MAX_SPEED)
            throw new IllegalArgumentException("Invalid slash speed");
        double start = startPerSecond / 20, end = endPerSecond / 20;
        return new BezierSpeed(start, start, end, end, range / ((start + end) * .5));
    }
}
