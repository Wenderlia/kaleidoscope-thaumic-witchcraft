package dev.senluo.witchcraft.ritual;

import java.util.List;
import net.minecraft.resources.ResourceLocation;

/** A recipe selects a pair and a direction; the ledger owns the two-step transition. */
public final class GeasRites {
    public record Rite(BindingPair pair, boolean inverse) {
        public ResourceLocation id() { return pair.id(inverse); }
        public dev.senluo.witchcraft.ritual.visual.GeasGlyph glyph() {
            if (pair.equals(GeasBindings.RESTRAINT_PAIR)) return inverse
                    ? dev.senluo.witchcraft.ritual.visual.GeasGlyph.RESTRAINT_INVERSE
                    : dev.senluo.witchcraft.ritual.visual.GeasGlyph.RESTRAINT;
            if (pair.equals(GeasBindings.CHANT_PAIR)) return inverse
                    ? dev.senluo.witchcraft.ritual.visual.GeasGlyph.CHANT_INVERSE
                    : dev.senluo.witchcraft.ritual.visual.GeasGlyph.CHANT;
            throw new IllegalStateException("Unregistered geas presentation: " + id());
        }
        public boolean accepts(BindingPair.State state) {
            return state == BindingPair.State.NONE || state == (inverse ? BindingPair.State.POSITIVE : BindingPair.State.NEGATIVE);
        }
    }
    public static final List<Rite> ALL = List.of(new Rite(GeasBindings.RESTRAINT_PAIR, false),
            new Rite(GeasBindings.RESTRAINT_PAIR, true), new Rite(GeasBindings.CHANT_PAIR, false),
            new Rite(GeasBindings.CHANT_PAIR, true));
    public static Rite find(ResourceLocation id) {
        return ALL.stream().filter(rite -> rite.id().equals(id)).findFirst().orElse(null);
    }
    private GeasRites() {}
}
