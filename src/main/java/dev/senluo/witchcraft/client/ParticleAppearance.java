package dev.senluo.witchcraft.client;

/** Small presentation-only curves shared by particles and the contact membrane. */
final class ParticleAppearance {
    static float smooth(float value) {
        float t = Math.clamp(value, 0, 1);
        return t * t * (3 - 2 * t);
    }

    static float envelope(float age, float lifetime, float arrival, float release) {
        return smooth(age / arrival) * smooth((lifetime - age) / release);
    }

    static float runeAlpha(float age, float lifetime) { return .94F * envelope(age, lifetime, 1.5F, lifetime * .55F); }
    static float runeScale(float age, float lifetime) {
        return (.76F + .24F * smooth(age / 2.5F)) * (1 - .28F * smooth((age / lifetime - .5F) * 2));
    }
    static float runeGreen(float age, float lifetime) { return .93F - .23F * smooth(age / lifetime); }
    static float runeBlue(float age, float lifetime) { return .61F - .38F * smooth(age / lifetime); }
    static float waveAlpha(float age, float lifetime) { return .62F * envelope(age, lifetime, 2.5F, 12); }
    static float waveScale(float age, float lifetime) {
        return (.82F + .14F * smooth(age / 8)) * (1 - .16F * smooth((age / lifetime - .65F) / .35F));
    }

    private ParticleAppearance() {}
}
