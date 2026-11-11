package dev.senluo.witchcraft.ritual;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** Explicit optional requirements; unavailable providers never count as completed research. */
public final class RitualResearch {
    public enum Result { ALLOWED, DENIED, UNAVAILABLE }
    public record Requirement(ResourceLocation provider, String key) {
        public Requirement {
            java.util.Objects.requireNonNull(provider);
            if (key == null || key.isBlank() || key.length() > 256 || key.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Invalid research key");
        }
    }
    @FunctionalInterface public interface Access { Result check(ServerLevel level, UUID player, String key); }
    private static final Map<ResourceLocation, Access> PROVIDERS = new HashMap<>();
    private RitualResearch() {}
    /** Project research identifiers share the existing optional Thaumcraft provider. */
    public static Optional<Requirement> thaumcraft(String key) {
        return Optional.of(new Requirement(ResourceLocation.fromNamespaceAndPath("thaumcraft", "research"),
                "kaleidoscope_witchcraft:" + key));
    }
    public static void register(ResourceLocation provider, Access access) {
        java.util.Objects.requireNonNull(access);
        if (PROVIDERS.putIfAbsent(provider, access) != null) throw new IllegalStateException("Duplicate research provider: " + provider);
    }
    public static Result check(ServerLevel level, UUID player, Optional<Requirement> requirement) {
        if (requirement.isEmpty()) return Result.ALLOWED;
        if (player == null || !level.getServer().isSameThread()) return Result.UNAVAILABLE;
        var needed = requirement.get();
        Access access = PROVIDERS.get(needed.provider());
        return access == null ? Result.UNAVAILABLE : java.util.Objects.requireNonNull(access.check(level, player, needed.key()));
    }
}
