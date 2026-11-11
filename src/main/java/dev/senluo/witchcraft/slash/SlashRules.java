package dev.senluo.witchcraft.slash;

import dev.senluo.witchcraft.ritual.BindingPair;
import dev.senluo.witchcraft.ritual.GeasBindings;
import net.minecraft.server.level.ServerPlayer;

/** Resolved binding semantics. Host acquisition, prices, and projectile snapshots remain separate. */
public record SlashRules(boolean restrained, boolean destructive, boolean chanting, boolean inverseChanting) {
    public static SlashRules bindings(ServerPlayer player) {
        var restraint = GeasBindings.state(player.server, player.getUUID(), GeasBindings.RESTRAINT_PAIR);
        var chant = GeasBindings.state(player.server, player.getUUID(), GeasBindings.CHANT_PAIR);
        // A malformed contradictory pair grants neither reward; do not combine both sides.
        return new SlashRules(restraint == BindingPair.State.POSITIVE, restraint == BindingPair.State.NEGATIVE,
                chant == BindingPair.State.POSITIVE, chant == BindingPair.State.NEGATIVE);
    }
    public double criticalChance() { return inverseChanting ? .1367 : SlashCritical.CHANCE; }
    public int chantTicks() { return chanting ? 100 : 60; }
    public double drawingFactor() { return inverseChanting ? 8 : chanting ? 4 : 1; }
    public double drawingLimit() { return chanting ? 64 : 32; }
    public double drawingPower(double rawMultiplier) {
        return Math.min(drawingLimit(), rawMultiplier * drawingFactor() * drawingFactor());
    }
    public double bindingPower() { return restrained ? dev.senluo.witchcraft.ritual.WorldRestraint.LIVING_DAMAGE_MULTIPLIER : 1; }
    public double rangeMultiplier() { return destructive ? 5 : 1; }
}
