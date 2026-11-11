package dev.senluo.witchcraft.ritual;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

/** A permanent role on living tissue, independent of the sample and the current root network. */
public final class TreeBond {
    private final Tag encoded;
    private final UUID player;
    private final String name;

    private TreeBond(Tag data) {
        encoded = data.copy();
        if (data instanceof CompoundTag tag && tag.contains("Version", Tag.TAG_INT)
                && tag.getInt("Version") == 1 && tag.hasUUID("Player")) {
            player = tag.getUUID("Player");
            name = cleanName(tag.getString("Name"));
        } else {
            // Keep an unrecognized binding; it must not become an unowned, claimable plant.
            player = null;
            name = "";
        }
    }

    public static TreeBond of(UUID player, String name) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", 1);
        tag.putUUID("Player", player);
        tag.putString("Name", cleanName(name));
        return new TreeBond(tag);
    }

    public static TreeBond read(Tag tag) { return tag == null ? null : new TreeBond(tag); }
    public Tag save() { return encoded.copy(); }
    public UUID player() { return player; }
    public boolean supported() { return player != null; }
    public boolean belongsTo(UUID candidate) { return player != null && player.equals(candidate); }
    public Component displayName() {
        return !supported() ? Component.translatable("tooltip.kaleidoscope_witchcraft.bond.unsupported")
                : name.isEmpty() ? Component.translatable("entity.minecraft.player") : Component.literal(name);
    }

    public static boolean compatible(TreeBond first, TreeBond second) {
        return first == null ? second == null : second != null && first.belongsTo(second.player);
    }

    private static String cleanName(String value) {
        if (value == null || value.length() > 128 || value.chars().anyMatch(c -> Character.isISOControl(c) || c == '\u00a7')) return "";
        return value;
    }
}
