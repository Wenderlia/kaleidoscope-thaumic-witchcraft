package dev.senluo.witchcraft.ritual;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Client-side mirrors only; no entry here grants server authority or advances a ritual. */
public final class RitualView {
    public record State(UUID session, ResourceLocation dimension, Vec3 anchor, int progress, int escaping, RitualPose pose) {
        public float gray() { return 1 - Math.clamp(escaping / (float) OwnershipRitual.ESCAPE, 0, 1); }
    }
    private static final Map<UUID, State> STATES = new HashMap<>();
    private RitualView() {}
    public static State get(Entity entity) {
        State state = STATES.get(entity.getUUID());
        return state != null && state.dimension.equals(entity.level().dimension().location()) ? state : null;
    }
    public static void put(UUID player, State state) { STATES.put(player, state); }
    public static void remove(UUID player, UUID session) {
        State state = STATES.get(player);
        if (state != null && state.session.equals(session)) STATES.remove(player);
    }
    public static void clear() { STATES.clear(); }
}
