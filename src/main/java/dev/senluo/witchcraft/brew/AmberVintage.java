package dev.senluo.witchcraft.brew;

/** Fruit variety is independent of Tavern's fermentation quality. */
public enum AmberVintage {
    DAWN("dawn_fruit_wine", "dawn_pulp", 5.3, 5.685, 10.7, 10.3, 14.95),
    HONEYED("amber_mead", "honeyed_pulp", 4.6, 6.085, 11.4, 9.915, 12.6),
    FROST("frostgold_icewine", "frost_pulp", 5.85, 5.835, 10.15, 10.165, 15.55),
    AGED("dusk_brandy", "aged_amber_pulp", 4.55, 5.585, 11.45, 10.415, 12.0),
    EVERDAY("everday_elixir", "temporal_marrow_pulp", 5.875, 6.235, 10.125, 9.75, 8.96);

    public final String wine, pulp;
    // Authored north-facing count1 model bounds; multi-bottle models use the same offsets.
    public final double minX, minZ, maxX, maxZ, height;
    public net.minecraft.network.chat.Component lore() {
        return dev.senluo.witchcraft.time.TemporalText.magicLore("vintage." + wine);
    }
    AmberVintage(String wine, String pulp, double minX, double minZ, double maxX, double maxZ, double height) {
        this.wine=wine; this.pulp=pulp;
        this.minX=minX; this.minZ=minZ; this.maxX=maxX; this.maxZ=maxZ; this.height=height;
    }
}
