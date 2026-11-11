package dev.senluo.witchcraft.ritual.visual;

/** Bounded presentation identity, fixed for one ritual attempt. No gameplay or client dependencies. */
public enum GeasGlyph {
    RESTRAINT("restraint", "crest"),
    RESTRAINT_INVERSE("restraint_inverse", "crest_restraint_inverse"),
    CHANT("chant", "crest_chant"),
    CHANT_INVERSE("chant_inverse", "crest_chant_inverse");

    private final String icon;
    private final String crest;
    GeasGlyph(String icon, String crest) {
        this.icon = "textures/gui/geas/" + icon + ".png";
        this.crest = crest;
    }
    public String icon() { return icon; }
    public String crest() { return crest; }
    public boolean outwardArcs() { return this == RESTRAINT_INVERSE || this == CHANT_INVERSE; }
}
