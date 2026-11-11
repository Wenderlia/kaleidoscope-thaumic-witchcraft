package dev.senluo.witchcraft.ritual;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server policies for collection and bounded native ritual work. */
public final class RitualConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue RESTRICT_NON_PVP;
    public static final ModConfigSpec.BooleanValue RESTRICT_STAFF;
    public static final ModConfigSpec.IntValue EXTRA_UPDATES;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("taglock");
        RESTRICT_NON_PVP = builder.comment("Reject sampling other players when server PvP is disabled.")
                .define("restrictCollectionOnNonPvpServers", false);
        RESTRICT_STAFF = builder.comment("Reject sampling other players who have operator privileges.")
                .define("restrictCollectionForStaffMembers", false);
        builder.pop();
        builder.push("execution");
        EXTRA_UPDATES = builder.comment("Shared extra native-update allowance per dimension and server tick.",
                "Includes steam machines/fire, promoted nodes and tree-funded machine updates; native updates are never charged.",
                "Recurring targets share capacity. Excess work is discarded, never replayed later.")
                .defineInRange("maxExtraUpdatesPerLevelTick", 4096, 1, 65536);
        builder.pop();
        SPEC = builder.build();
    }

    private RitualConfig() {}
}
