package dev.senluo.witchcraft.slash;

/** Cosmetic cast snapshot. It never participates in quotation, geometry or damage. */
public enum SlashVisual {
    NORMAL(0, "normal"), BLACK_RED(1, "black_red");

    private final int wireId;
    private final String key;
    SlashVisual(int wireId, String key) { this.wireId = wireId; this.key = key; }
    public int wireId() { return wireId; }
    public String key() { return key; }
    public static SlashVisual fromId(int id) { return id == BLACK_RED.wireId ? BLACK_RED : NORMAL; }
}
