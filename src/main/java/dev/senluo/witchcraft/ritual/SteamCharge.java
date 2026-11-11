package dev.senluo.witchcraft.ritual;

/** Immutable activation quote. The vessel adapter owns payment; fields only consume the paid rate. */
public record SteamCharge(int radius, int energyGroups) {
    public static final int ENERGY_PER_GROUP = 32;
    // This is the representable aspect balance, not an execution-rate cap.
    public static final int MAX_ENERGY_GROUPS = Integer.MAX_VALUE / ENERGY_PER_GROUP;

    public SteamCharge {
        if (radius < 1 || radius > 32 || energyGroups < 0 || energyGroups > MAX_ENERGY_GROUPS)
            throw new IllegalArgumentException("Invalid steam activation quote");
    }

    public static SteamCharge quote(int radius, int energy) {
        if (energy < 0) throw new IllegalArgumentException("Negative energy balance");
        return new SteamCharge(radius, energy / ENERGY_PER_GROUP);
    }

    public int rangeCost() { return 4 * radius; }
    public int energyCost() { return ENERGY_PER_GROUP * energyGroups; }
    public int doublings() { return 1 + energyGroups; }

    public boolean canPay(int vacuos, int ordo, int energy) {
        return vacuos >= rangeCost() && ordo >= rangeCost() && energy >= energyCost();
    }
}
