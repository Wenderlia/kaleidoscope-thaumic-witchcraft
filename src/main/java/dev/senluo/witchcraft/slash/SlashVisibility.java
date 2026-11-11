package dev.senluo.witchcraft.slash;

import java.util.UUID;

/** Visual audience only: never changes collision, damage, sound or entity tracking. */
public enum SlashVisibility {
    EVERYONE(0, "everyone"), CASTER_ONLY(1, "caster_only"), HIDDEN(2, "hidden");

    private final int wireId;
    private final String key;

    SlashVisibility(int wireId, String key) { this.wireId = wireId; this.key = key; }
    public int wireId() { return wireId; }
    public String key() { return key; }

    public boolean visibleTo(UUID caster, UUID viewer) {
        return viewer != null && (this == EVERYONE || this == CASTER_ONLY && viewer.equals(caster));
    }

    public static SlashVisibility fromId(int id) {
        return switch (id) { case 0 -> EVERYONE; case 1 -> CASTER_ONLY; default -> HIDDEN; };
    }

    public static SlashVisibility fromKey(String key) {
        return switch (key) { case "everyone" -> EVERYONE; case "caster_only" -> CASTER_ONLY; default -> HIDDEN; };
    }
}
