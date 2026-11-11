package dev.senluo.witchcraft.ritual.visual;

/** Three bounded GUI passes. The glyph remains stationary; only faint light echoes drift.
 * No clock, texture, world lookup or GL state belongs to this presentation calculation. */
public final class GeasIconLayers {
    @FunctionalInterface public interface Sink {
        void layer(float x,float y,float r,float g,float b,float alpha);
    }
    public static void emit(double ticks,int size,Sink sink) {
        double phase=ticks*Math.PI/60;
        float distance=size/32f;
        float x=(float)(.45+.20*Math.sin(phase))*distance;
        float y=(float)(.25*Math.cos(phase))*distance;
        float alpha=(float)(.08+.025*Math.sin(phase));
        sink.layer(-x,y,1,.88f,.68f,alpha);
        sink.layer(x,-y,.85f,.88f,1,.16f-alpha);
        // Keep the native-pixel silhouette fully legible even when echoes are barely visible.
        sink.layer(0,0,1,1,1,1);
    }
    private GeasIconLayers() {}
}
