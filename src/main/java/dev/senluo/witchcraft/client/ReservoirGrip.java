package dev.senluo.witchcraft.client;

import dev.senluo.witchcraft.reservoir.ReservoirProfile;

// Generated from each host's actual carry_grip, in block units.
final class ReservoirGrip {
    record Anchor(double x, double y, double z) {}
    private static final Anchor STANDARD = new Anchor(0.000000000, 1.017187500, 0.134375000);
    private static final Anchor SILVERWOOD = new Anchor(0.000000000, 0.955937500, 0.030625000);
    static Anchor of(ReservoirProfile profile) {
        return profile == ReservoirProfile.SILVERWOOD ? SILVERWOOD : STANDARD;
    }
    private ReservoirGrip() {}
}
