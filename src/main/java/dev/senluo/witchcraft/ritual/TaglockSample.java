package dev.senluo.witchcraft.ritual;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.CustomData;

/** An identity snapshot, never an entity inventory, location or chunk-loading request. */
public record TaglockSample(int version, UUID subject, ResourceLocation entityType, String name,
                            boolean literalName, CustomData preservedData) {
    public static final int CURRENT_VERSION = 1;
    private static final int MAX_NAME = 128;
    private static final ResourceLocation PLAYER = ResourceLocation.withDefaultNamespace("player");
    private static final Codec<String> NAME_CODEC = Codec.STRING.validate(value ->
            validName(value) ? DataResult.success(value) : DataResult.error(() -> "Invalid taglock display name"));
    private static final Codec<ResourceLocation> TYPE_CODEC = ResourceLocation.CODEC.validate(value ->
            value.toString().length() <= 256 ? DataResult.success(value) : DataResult.error(() -> "Taglock type is too long"));
    private static final Codec<TaglockSample> FIELDS_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(1, Integer.MAX_VALUE).fieldOf("version").forGetter(TaglockSample::version),
            UUIDUtil.CODEC.fieldOf("subject").forGetter(TaglockSample::subject),
            TYPE_CODEC.fieldOf("entity_type").forGetter(TaglockSample::entityType),
            NAME_CODEC.fieldOf("name").forGetter(TaglockSample::name),
            Codec.BOOL.optionalFieldOf("literal_name", true).forGetter(TaglockSample::literalName)
    ).apply(instance, TaglockSample::new));
    // Keep fields we cannot interpret when loading a newer sample with the same identity
    // envelope. Rebinding is disabled for it, so an older game cannot erase newer data.
    public static final Codec<TaglockSample> CODEC = CompoundTag.CODEC.flatXmap(tag ->
            FIELDS_CODEC.parse(NbtOps.INSTANCE, tag).map(sample -> sample.supported() ? sample
                    : new TaglockSample(sample.version, sample.subject, sample.entityType, sample.name,
                            sample.literalName, CustomData.of(tag))),
            sample -> sample.preservedData.isEmpty()
                    ? FIELDS_CODEC.encodeStart(NbtOps.INSTANCE, sample).map(tag -> (CompoundTag) tag)
                    : DataResult.success(sample.preservedData.copyTag()));
    public static final StreamCodec<ByteBuf, TaglockSample> STREAM_CODEC =
            ByteBufCodecs.fromCodec(CODEC, () -> NbtAccounter.create(4096));

    public TaglockSample {
        Objects.requireNonNull(subject);
        Objects.requireNonNull(entityType);
        Objects.requireNonNull(preservedData);
        if (version < 1 || entityType.toString().length() > 256 || !validName(name))
            throw new IllegalArgumentException("Invalid taglock sample");
    }

    public TaglockSample(int version, UUID subject, ResourceLocation entityType, String name, boolean literalName) {
        this(version, subject, entityType, name, literalName, CustomData.EMPTY);
    }

    public static TaglockSample from(Entity entity) {
        String display = entity.getName().getString().replaceAll("[\\p{Cntrl}§]", " ").strip();
        if (display.isEmpty()) display = entity.getType().getDescription().getString();
        if (display.length() > MAX_NAME) {
            int end = Character.isHighSurrogate(display.charAt(MAX_NAME - 1)) ? MAX_NAME - 1 : MAX_NAME;
            display = display.substring(0, end);
        }
        return new TaglockSample(CURRENT_VERSION, entity.getUUID(),
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()), display,
                entity instanceof Player || entity.hasCustomName());
    }

    public boolean supported() {
        return version == CURRENT_VERSION;
    }

    public boolean isPlayer() {
        return entityType.equals(PLAYER);
    }

    public Component displayName() {
        if (literalName) return Component.literal(name);
        // Unnamed creatures use the viewer's language; missing entity types retain the snapshot.
        return BuiltInRegistries.ENTITY_TYPE.getOptional(entityType)
                .map(type -> type.getDescription()).orElseGet(() -> Component.literal(name));
    }

    private static boolean validName(String name) {
        return name != null && !name.isBlank() && name.length() <= MAX_NAME
                && name.chars().noneMatch(c -> Character.isISOControl(c) || c == '§');
    }
}
