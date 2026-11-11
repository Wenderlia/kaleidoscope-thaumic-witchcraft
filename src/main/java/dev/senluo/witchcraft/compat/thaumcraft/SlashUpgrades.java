package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.slash.SlashContent;
import dev.tc4port.thaumcraft.api.focus.*;
import net.minecraft.world.item.ItemStack;

/** Native focus upgrade slots are the only source of upgrade eligibility. */
public final class SlashUpgrades {
    public static final FocusUpgradeId SURGE = FocusUpgradeId.of(SlashContent.id("surge"));
    public static final FocusUpgradeId PENETRATION = FocusUpgradeId.of(SlashContent.id("penetration"));
    public static final FocusUpgradeId FISSION = FocusUpgradeId.of(SlashContent.id("fission"));
    public static final FocusUpgradeId BLACK_FLASH = FocusUpgradeId.of(SlashContent.id("black_flash"));

    public static int level(ItemStack wand, FocusUpgradeId id) {
        return FocusApi.installedState(wand).filter(s -> s.focusId().equals(SlashFocus.ID))
                .map(s -> Math.clamp(s.level(id), 0, id.equals(SURGE) ? 5 : 1)).orElse(0);
    }
    public static double surge(ItemStack wand) {
        int level = level(wand, SURGE);
        return level == 0 ? 1 : Math.pow(FocusApi.parameter(wand, "surge_base"), level);
    }
    private SlashUpgrades() {}
}
