package dev.senluo.witchcraft.ritual;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** A pair's state transition, independent of its effect, ritual, cost and presentation. */
public record BindingPair(ResourceLocation positive, ResourceLocation negative) {
    public enum State { NONE, POSITIVE, NEGATIVE, CONFLICT, UNAVAILABLE }
    public enum Change { GRANTED, RELEASED, UNCHANGED, REJECTED }
    public BindingPair {
        Objects.requireNonNull(positive); Objects.requireNonNull(negative);
        if (positive.equals(negative)) throw new IllegalArgumentException("A binding cannot be its own inverse");
    }
    public ResourceLocation id(boolean inverse) { return inverse ? negative : positive; }
    public State state(boolean hasPositive, boolean hasNegative) {
        return hasPositive ? hasNegative ? State.CONFLICT : State.POSITIVE : hasNegative ? State.NEGATIVE : State.NONE;
    }
}
