package dev.senluo.witchcraft.time;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/** Wood membership shared by connectivity, growth, restoration and host integrations. */
public final class TreeWood {
    public static final TagKey<Block> LOGS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft", "years_logs"));

    private TreeWood() {}
}
