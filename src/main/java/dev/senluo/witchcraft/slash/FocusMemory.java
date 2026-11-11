package dev.senluo.witchcraft.slash;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** Addon-owned persistent focus state; no TC implementation classes in saved components. */
public record FocusMemory(List<ResourceLocation> upgrades, Map<ResourceLocation, String> properties) {
    public static final FocusMemory EMPTY = new FocusMemory(List.of(), Map.of());
    public static final Codec<FocusMemory> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.listOf(0, 5).optionalFieldOf("upgrades", List.of()).forGetter(FocusMemory::upgrades),
            Codec.unboundedMap(ResourceLocation.CODEC, Codec.string(0, 512))
                    .validate(p -> p.size() <= 64 && p.values().stream().mapToInt(String::length).sum() <= 8192
                            ? DataResult.success(p) : DataResult.error(() -> "Oversized slash focus properties"))
                    .optionalFieldOf("properties", Map.of()).forGetter(FocusMemory::properties)
    ).apply(i, FocusMemory::new));

    public FocusMemory {
        upgrades = List.copyOf(upgrades);
        properties = Map.copyOf(properties);
        if (upgrades.size() > 5 || properties.size() > 64
                || properties.values().stream().mapToInt(String::length).sum() > 8192)
            throw new IllegalArgumentException("Oversized slash focus state");
    }
}
