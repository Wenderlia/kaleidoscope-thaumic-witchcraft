package dev.senluo.witchcraft.time;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Provenance scopes are opened only by the original level schedulers, with finally cleanup. */
public final class NativeTicks {
    public static final ThreadLocal<Sample> SAMPLE = new ThreadLocal<>();
    private NativeTicks() {}
    public static final class Sample {
        public final ServerLevel level;
        public final boolean nativePass;
        public BlockPos position;
        public Sample(ServerLevel level, boolean nativePass) { this.level = level; this.nativePass = nativePass; }
    }
}
