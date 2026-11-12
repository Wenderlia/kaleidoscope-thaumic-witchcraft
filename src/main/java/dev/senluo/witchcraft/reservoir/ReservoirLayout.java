package dev.senluo.witchcraft.reservoir;

/** Shared native-pixel layout for slots, drawing and chamber hit testing. */
public final class ReservoirLayout {
    public static final int WIDTH=244,HEIGHT=224;
    public static final int INPUT_X=15,INPUT_Y=57,OUTPUT_X=203,OUTPUT_Y=57;
    public static final int PLUGIN_PANEL_X=-28,PLUGIN_PANEL_Y=20,PLUGIN_PANEL_WIDTH=32;
    public static final int PLUGIN_X=-21,PLUGIN_Y=47,PLUGIN_STEP=24;
    public static final int PLUGIN_SLOT_SIZE=22,PLUGIN_SLOT_INSET=3;
    public int pluginPanelHeight(boolean expanded){return expanded?28+profile.plugins*PLUGIN_STEP:PLUGIN_COLLAPSED_HEIGHT;}
    public static final int PLUGIN_COLLAPSED_HEIGHT=24;
    public static final int PLUGIN_TAB_X=PLUGIN_PANEL_X+4,PLUGIN_TAB_Y=PLUGIN_PANEL_Y+4;
    public static final int PLUGIN_TAB_WIDTH=24,PLUGIN_TAB_HEIGHT=16;
    public static final int PLUGIN_GLYPH_X=PLUGIN_TAB_X+5,PLUGIN_GLYPH_Y=PLUGIN_TAB_Y+1;
    public static final int PLUGIN_FOLD_X=PLUGIN_TAB_X+19,PLUGIN_FOLD_Y=PLUGIN_TAB_Y+6,PLUGIN_FOLD_WIDTH=5,PLUGIN_FOLD_HEIGHT=3;
    public static final int EXTRA_LEFT=-PLUGIN_PANEL_X;
    public static final int INVENTORY_X=41,INVENTORY_Y=137,HOTBAR_Y=195;
    public static final int CHAMBER_Y=24,CHAMBER_WIDTH=32,CHAMBER_HEIGHT=90;
    public int chamberX(int index){return 56+(chamberWidth()+4)*index;}
    public int chamberWidth(){return CHAMBER_WIDTH;}
    public int chamberHeight(){return CHAMBER_HEIGHT;}
    public int chamberY(){return CHAMBER_Y+(CHAMBER_HEIGHT-chamberHeight())/2;}
    public int liquidWidth(){return 20;}
    public int liquidHeight(){return 52;}
    public int liquidBottom(){return 62;}
    public int glassHeight(){return 56;}
    public int labelY(){return 69;}
    public int labelWidth(){return 28;}
    public static int pluginY(int index){return PLUGIN_Y+PLUGIN_STEP*index;}
    public static final int PLUGIN_SWITCH_OFFSET=17,PLUGIN_SWITCH_HEIGHT=4;
    public int pluginSwitchAt(double x,double y){
        if(x<PLUGIN_X || x>=PLUGIN_X+16)return -1;
        for(int i=0;i<profile.plugins;i++)if(y>=pluginY(i)+PLUGIN_SWITCH_OFFSET && y<pluginY(i)+PLUGIN_SWITCH_OFFSET+PLUGIN_SWITCH_HEIGHT)return i;
        return -1;
    }
    
    public boolean pluginPanelContains(double x,double y,boolean expanded){
        return x>=PLUGIN_PANEL_X && x<PLUGIN_PANEL_X+PLUGIN_PANEL_WIDTH
                && y>=PLUGIN_PANEL_Y && y<PLUGIN_PANEL_Y+pluginPanelHeight(expanded);
    }
    public int chamberAt(double x,double y){
        if(y<chamberY() || y>=chamberY()+chamberHeight())return -1;
        for(int i=0;i<profile.cells;i++)if(x>=chamberX(i) && x<chamberX(i)+chamberWidth())return i;
        return -1;
    }
    public final ReservoirProfile profile;
    public ReservoirLayout(ReservoirProfile profile){this.profile=profile;}
    public int extraWidth(){return profile.cells*(chamberWidth()+4)-3*(CHAMBER_WIDTH+4);}
    public int width(){return WIDTH+extraWidth();}
    public int outputX(){return OUTPUT_X+extraWidth();}
    public int inventoryX(){return INVENTORY_X+extraWidth()/2;}
}
