package dev.senluo.witchcraft.ritual;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Offering feedback belongs to the committed material, not its index in a recipe. */
public final class OfferingSounds {
    public record Voice(Supplier<SoundEvent> sound, float volume, float pitch) {
        public Voice {
            java.util.Objects.requireNonNull(sound);
            if (!Float.isFinite(volume) || !Float.isFinite(pitch) || volume < 0 || pitch <= 0)
                throw new IllegalArgumentException("Invalid offering voice");
        }
        public Voice(SoundEvent sound, float volume, float pitch) { this(() -> sound, volume, pitch); }
    }
    private static final Map<ResourceLocation, List<Voice>> VOICES = new HashMap<>();
    private static final Voice POTION = new Voice(SoundEvents.BOTTLE_EMPTY, .4F, 1.15F);
    static {
        register("minecraft:gold_ingot", new Voice(() -> RitualContent.GOLD_OFFERING.get(), .35F, 1F));
        register("minecraft:coal", new Voice(SoundEvents.FIRECHARGE_USE, .35F, 1.25F));
        register("minecraft:charcoal", new Voice(SoundEvents.FIRECHARGE_USE, .35F, 1.15F));
        register("minecraft:feather", new Voice(SoundEvents.PHANTOM_FLAP, .3F, 1.5F));
        register("minecraft:string", new Voice(SoundEvents.ENCHANTMENT_TABLE_USE, .35F, .7F));
        register("minecraft:cobweb", new Voice(SoundEvents.SPIDER_DEATH, .35F, .8F));
        register("minecraft:diamond_pickaxe", new Voice(SoundEvents.ANVIL_USE, .35F, 1.2F));
        register("minecraft:ender_pearl", new Voice(SoundEvents.ENDER_PEARL_THROW, .45F, .85F));
        register("minecraft:crying_obsidian", new Voice(SoundEvents.RESPAWN_ANCHOR_CHARGE, .4F, .7F));
        register("minecraft:amethyst_shard", new Voice(SoundEvents.AMETHYST_BLOCK_RESONATE, .4F, .85F));
        // The vanilla chime sample has its own 0.2 volume multiplier.
        register("minecraft:diamond", new Voice(SoundEvents.AMETHYST_BLOCK_CHIME, .9F, 1.25F));
        register("minecraft:bone_meal", new Voice(SoundEvents.BONE_MEAL_USE, .4F, 1.0F));
        register("minecraft:nether_wart", new Voice(SoundEvents.NETHER_WART_BREAK, .45F, .85F));
    }
    private OfferingSounds() {}

    /** Register once during common setup. Optional integrations own their material identities. */
    public static void register(String material, Voice... voices) {
        if (voices.length == 0) throw new IllegalArgumentException("Empty offering voice");
        VOICES.put(ResourceLocation.parse(material), List.of(voices));
    }
    /** Material voices take precedence; the common cue is only a fallback. */
    public static void absorb(ServerLevel level, Vec3 position, ItemStack material) {
        if (material.isEmpty()) return;
        var selected = voices(material, level, position);
        if (selected.isEmpty()) RitualSounds.absorb(level, position);
        else playVoices(level, position, selected);
    }

    /** A committed reusable catalyst resonance; does not imply consumption. */
    public static void play(ServerLevel level, Vec3 position, ItemStack material) {
        if (material.isEmpty()) return;
        playVoices(level, position, voices(material, level, position));
    }

    private static List<Voice> voices(ItemStack material, ServerLevel level, Vec3 position) {
        var voices = VOICES.get(BuiltInRegistries.ITEM.getKey(material.getItem()));
        if (voices == null && material.has(DataComponents.POTION_CONTENTS)) voices = List.of(POTION);
        if (voices == null && material.getItem() instanceof net.minecraft.world.item.BlockItem block)
            voices = List.of(new Voice(block.getBlock().defaultBlockState()
                    .getSoundType(level, net.minecraft.core.BlockPos.containing(position), null).getBreakSound(), .4F, .9F));
        return voices == null ? List.of() : voices;
    }

    private static void playVoices(ServerLevel level, Vec3 position, List<Voice> voices) {
        for (Voice voice : voices) {
            SoundEvent sound = voice.sound().get();
            if (sound != null) level.playSound(null, position.x, position.y, position.z, sound,
                    SoundSource.BLOCKS, voice.volume(), voice.pitch());
        }
    }
}
