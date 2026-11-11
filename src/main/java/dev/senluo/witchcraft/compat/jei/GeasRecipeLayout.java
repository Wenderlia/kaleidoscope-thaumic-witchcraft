package dev.senluo.witchcraft.compat.jei;

/** Pure layout shared with the offline recipe preview. Coordinates are JEI content pixels. */
public final class GeasRecipeLayout {
    public static final int WIDTH=144, HEIGHT=112;
    public static final int CENTRE_X=50, CENTRE_Y=47, RADIUS=30;
    public static final int CREST_X=104, CREST_Y=31, CREST_W=32, CREST_H=32;
    public static final int ASPECT_Y=89, ASPECT_STEP=26;
    public static int componentX(int index,int count) {
        return CENTRE_X+(int)(Math.cos(-Math.PI/2+index*2*Math.PI/count)*RADIUS)-8;
    }
    public static int componentY(int index,int count) {
        return CENTRE_Y+(int)(Math.sin(-Math.PI/2+index*2*Math.PI/count)*RADIUS)-8;
    }
    public static int aspectX(int index,int count) {
        return (WIDTH-(16+(count-1)*ASPECT_STEP))/2+index*ASPECT_STEP;
    }
    private GeasRecipeLayout() {}
}
