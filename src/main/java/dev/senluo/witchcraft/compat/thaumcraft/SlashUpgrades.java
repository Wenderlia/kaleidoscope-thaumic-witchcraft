package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.slash.SlashContent;
import dev.tc4port.thaumcraft.api.focus.*;

/** Native focus upgrade slots are the only source of upgrade eligibility. */
public final class SlashUpgrades {
    public static final FocusUpgradeId SURGE = FocusUpgradeId.of(SlashContent.id("surge"));
    public static final FocusUpgradeId PENETRATION = FocusUpgradeId.of(SlashContent.id("penetration"));
    public static final FocusUpgradeId FISSION = FocusUpgradeId.of(SlashContent.id("fission"));
    public static final FocusUpgradeId BLACK_FLASH = FocusUpgradeId.of(SlashContent.id("black_flash"));

    private SlashUpgrades() {}
}
