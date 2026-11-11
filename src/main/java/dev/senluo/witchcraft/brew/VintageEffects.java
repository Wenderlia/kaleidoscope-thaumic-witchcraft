package dev.senluo.witchcraft.brew;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/** Shared grant policy for wine and fruit; ability ledgers retain their own budgets and clocks. */
public final class VintageEffects {
    private VintageEffects() {}

    public static AmberVintage fruitVintage(int grade) {
        return switch (grade) {
            case 1 -> AmberVintage.DAWN;
            case 2 -> AmberVintage.HONEYED;
            case 3 -> AmberVintage.FROST;
            case 4 -> AmberVintage.AGED;
            case 5 -> AmberVintage.EVERDAY;
            default -> throw new IllegalArgumentException("Invalid fruit grade: " + grade);
        };
    }
    public static int fruitQuality(int grade) { return grade == 3 ? 4 : 3; }
    public static int duration(AmberVintage vintage, int quality) {
        if (quality < 2 || quality > 6) return 0;
        return switch (vintage) {
            case DAWN -> new int[]{0, 0, 240, 360, 450, 540, 600}[quality] * 20;
            case HONEYED -> new int[]{0, 0, 60, 180, 360, 720, 720}[quality] * 20;
            case FROST -> new int[]{0, 0, 180, 300, 360, 420, 480}[quality] * 20;
            case AGED -> quality * 30 * 20;
            case EVERDAY -> new int[]{0, 0, 80, 120, 140, 170, 200}[quality];
        };
    }
    public static Holder<MobEffect> marker(AmberVintage vintage) {
        return switch (vintage) {
            case DAWN -> MobEffects.ABSORPTION;
            case HONEYED -> MobEffects.FIRE_RESISTANCE;
            case FROST -> MobEffects.NIGHT_VISION;
            case AGED -> MobEffects.MOVEMENT_SPEED;
            case EVERDAY -> null;
        };
    }
    public static MobEffectInstance fruitMarker(int grade) {
        AmberVintage vintage = fruitVintage(grade);
        if (grade == 5) throw new IllegalArgumentException("Grade V keeps its saturation effect");
        return new MobEffectInstance(marker(vintage), duration(vintage, fruitQuality(grade)), 0);
    }
    public static void eatFruit(ServerPlayer player, int grade) {
        if (grade >= 1 && grade <= 4) WineAbilities.drink(player, fruitVintage(grade), fruitQuality(grade));
    }
    static int align(ServerPlayer player, AmberVintage vintage, int remaining) {
        Holder<MobEffect> effect = marker(vintage);
        if (effect == null) return remaining;
        MobEffectInstance current = player.getEffect(effect);
        // An unrelated infinite command effect is not a source of infinite ability charges or time.
        if (current != null && current.isInfiniteDuration()) return remaining;
        int duration = Math.max(remaining, current == null ? 0 : current.getDuration());
        player.addEffect(new MobEffectInstance(effect, duration, current == null ? 0 : current.getAmplifier(),
                current != null && current.isAmbient(), current == null || current.isVisible(), true));
        return duration;
    }
}
