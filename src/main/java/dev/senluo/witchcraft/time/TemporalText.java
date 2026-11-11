package dev.senluo.witchcraft.time;

import java.math.BigDecimal;
import java.math.RoundingMode;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Shared units and development predicates; rendering never advances the organism. */
public final class TemporalText {
    public static final int BODY = 0xCCD6D3;
    public static final int AMOUNT = 0xFFC96A;
    public static final int CAPACITY = 0x78C9EC;
    public static final int TIME = 0x5DE0BA;
    public static final int ACTION = 0xF2AD63;
    public static final int STAGE = 0xABD867;
    public static final int SOURCE = 0x5555FF;
    public static final int SHIFT = 0xFFD16F;
    public static final int CONTROL = 0x71E6D5;
    public static final int SECONDARY = 0xA4B5B0;
    public static final int READY = 0xA2E37A;
    public static final int PENDING = 0xFFD075;
    public static final int LORE = 0xAA00AA;

    private TemporalText() {}

    public static Component magicLore(String key) {
        return text(key).copy().withStyle(style -> style.withColor(LORE).withItalic(true).withBold(false));
    }

    public static Component color(Component text, int color) {
        return text.copy().withStyle(style -> style.withColor(color));
    }

    public static Component number(Object value, int color) {
        return color(Component.literal(String.valueOf(value)), color);
    }

    public static Component amount(long milliTu, boolean precise) {
        return number(precise ? exact(milliTu) : compact(milliTu), AMOUNT);
    }

    public static Component capacity(long milliTu, boolean precise) {
        return number(precise ? exact(milliTu) : compact(milliTu), CAPACITY);
    }

    public static Component key(boolean control, boolean active) {
        return Component.literal(control ? "[Ctrl]" : "[Shift]")
                .withStyle(style -> style.withColor(control ? CONTROL : SHIFT).withBold(active));
    }

    public static Component item(String translationKey) {
        return color(Component.translatable(translationKey), ACTION);
    }

    public static Component consumable(String translationKey) {
        return color(text("consumable", item(translationKey)), ACTION);
    }

    public static Component sneak(Component item) {
        return color(text("sneak_item", item), ACTION);
    }

    public static String exact(long milliTu) {
        return BigDecimal.valueOf(milliTu, 3).stripTrailingZeros().toPlainString();
    }

    public static String compact(long milliTu) {
        if (milliTu < 10_000 * Tissue.TU) return exact(milliTu);
        int scale = milliTu >= 1_000_000 * Tissue.TU ? 9 : 6;
        return BigDecimal.valueOf(milliTu, scale).setScale(1, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
                + (scale == 9 ? "M" : "k");
    }

    public static Component duration(int ticks) {
        return seconds((Math.max(0, ticks) + 19) / 20);
    }

    public static Component elapsed(int ticks) {
        return seconds(Math.max(0, ticks) / 20);
    }

    private static Component seconds(int seconds) {
        if (seconds < 60) return text("duration.seconds", number(seconds, TIME));
        if (seconds % 60 == 0) return text("duration.minutes", number(seconds / 60, TIME));
        return text("duration.mixed", number(seconds / 60, TIME), number(seconds % 60, TIME));
    }

    public static Component state(Tissue tissue) {
        String key = tissue.established() ? "side_ready"
                : tissue.growth.remainingFee() > 0 ? "side_unfunded" : "side_growing_short";
        return color(message(key), tissue.established() ? READY : PENDING);
    }

    public static Component stage(Tissue tissue) {
        return color(text(tissue.giant ? "stage.giant" : tissue.tree ? "stage.tree"
                : tissue.treeRoot ? "stage.tree_root" : "stage." + (tissue.maturePlant() ? 4 : 0)), STAGE);
    }

    public static Component growth(Tissue.Growth growth) {
        if (growth.remainingFee() == 0)
            return message("side_wait_time", duration(growth.remainingTicks()));
        if (growth.remainingTicks() == 0)
            return message("side_wait_fee", capacity(growth.remainingFee(), false));
        return message("side_growing", capacity(growth.remainingFee(), false), duration(growth.remainingTicks()));
    }

    public static MutableComponent message(String key, Object... args) {
        return Component.translatable("text.kaleidoscope_witchcraft." + key, args).withStyle(style -> style.withColor(BODY));
    }

    public static Component notice(String key, Object... args) {
        return switch (key) {
            case "side_full", "too_young", "training", "finish_growth_first", "denser_stone",
                    "graft_rhizome_only", "graft_complex", "feed_full", "feed_empty", "data_unavailable" -> color(message(key, args), PENDING);
            case "max_stage" -> color(message(key, args), READY);
            default -> message(key, args);
        };
    }

    public static Component text(String key, Object... args) {
        return Component.translatable("tooltip.kaleidoscope_witchcraft." + key, args).withStyle(style -> style.withColor(BODY));
    }
}
