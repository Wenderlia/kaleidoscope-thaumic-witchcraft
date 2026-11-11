package dev.senluo.witchcraft.time;

/** A visual wood variant with a local fruit role; it has no ticker or independent time account. */
public final class FruitWoodBlock extends TreePartBlock {
    private final int fruit;
    private final boolean node;
    public FruitWoodBlock(Properties properties, int fruit, boolean node) {
        super(properties);
        this.fruit = fruit;
        this.node = node;
    }
    public int fruit() { return fruit; }
    public boolean node() { return node; }
    @Override public String getDescriptionId() {
        return node ? super.getDescriptionId() : "block.kaleidoscope_witchcraft.years_log";
    }
}
