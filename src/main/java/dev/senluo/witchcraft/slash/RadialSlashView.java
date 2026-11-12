package dev.senluo.witchcraft.slash;

/** Client presentation only: no world access, movement receipts, payments or saved gameplay state. */
final class RadialSlashView {
    private final double[] previous, displayed, target;
    private final float[] previousAlpha, alpha, targetAlpha;
    private int interpolation;

    RadialSlashView(int count) {
        previous = new double[count]; displayed = new double[count]; target = new double[count];
        previousAlpha = new float[count]; alpha = new float[count]; targetAlpha = new float[count];
    }

    /** A trimmed child inherits the displayed motion of its previous front. */
    RadialSlashView remap(int[] parents) {
        RadialSlashView result = new RadialSlashView(parents.length);
        for (int i = 0; i < parents.length; i++) {
            int old = parents[i];
            if (old < 0 || old >= target.length) continue;
            result.previous[i] = previous[old]; result.displayed[i] = displayed[old]; result.target[i] = target[old];
            result.previousAlpha[i] = previousAlpha[old]; result.alpha[i] = alpha[old]; result.targetAlpha[i] = targetAlpha[old];
        }
        result.interpolation = interpolation;
        return result;
    }

    void accept(double[] radii, byte[] phases, byte[] moving) {
        for (int i = 0; i < target.length; i++) {
            // A late observer or a resumed, invisible front starts at its known position.
            if (alpha[i] == 0) previous[i] = displayed[i] = radii[i];
            target[i] = radii[i];
            targetAlpha[i] = phases[i] == 1 && moving[i] != 0 ? 1 : 0;
        }
        interpolation = 2;
    }

    void tick() {
        for (int i = 0; i < target.length; i++) {
            previous[i] = displayed[i]; previousAlpha[i] = alpha[i];
            if (interpolation > 0) displayed[i] += (target[i] - displayed[i]) / interpolation;
            alpha[i] += Math.clamp(targetAlpha[i] - alpha[i], -.2F, .5F);
        }
        if (interpolation > 0) interpolation--;
    }

    double radius(int part, float partial) {
        return previous[part] + (displayed[part] - previous[part]) * Math.clamp(partial, 0, 1);
    }
    float opacity(int part, float partial) {
        return previousAlpha[part] + (alpha[part] - previousAlpha[part]) * Math.clamp(partial, 0, 1);
    }
}
