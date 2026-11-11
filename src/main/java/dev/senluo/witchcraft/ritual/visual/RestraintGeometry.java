package dev.senluo.witchcraft.ritual.visual;

import java.util.List;
import static dev.senluo.witchcraft.ritual.visual.RitualLightGeometry.*;

/** Presentation only. The game and the offline exporter consume exactly these quads.
 * All positions are relative to the matrix centre; no world queries or gameplay clocks live here. */
public final class RestraintGeometry {
    public enum Phase { FORMING, BINDING, COMPLETE, CANCELLED, WAITING }
    public enum Material { RADIAL, RIBBON, CREST_HALO, CREST, GLOW, ORBIT_HEAD, ORBIT_SHORT, ORBIT_LONG,
        RING_BODY, RING_HALO, RING_MIST }
    public record V(double x, double y, double z) {
        public V add(V b) { return new V(x+b.x, y+b.y, z+b.z); }
        public V scale(double k) { return new V(x*k, y*k, z*k); }
        public V sub(V b) { return add(b.scale(-1)); }
        public V cross(V b) { return new V(y*b.z-z*b.y, z*b.x-x*b.z, x*b.y-y*b.x); }
        public V unit() { double n=Math.sqrt(x*x+y*y+z*z); return n<1e-8 ? new V(1,0,0) : scale(1/n); }
    }
    public record Frame(Phase phase, double phaseTicks, double animationTicks, V right, V up, V camera,
            V recipient, double revealAtExit, Layout layout, List<OfferingPulse> pulses, GeasGlyph glyph) {
        public Frame {
            java.util.Objects.requireNonNull(glyph);
            pulses=List.copyOf(pulses);if(pulses.size()>4)throw new IllegalArgumentException("Too many pulses");
        }
    }
    public record OfferingPulse(V source,double age) {}
    /** Host-provided anchors, relative to matrix centre; pure immutable presentation data. */
    public record Layout(double groundY, double groundRadius, List<V> offerings) {
        public static final int MAX_OFFERINGS=8;
        public Layout {
            if(!Double.isFinite(groundY) || !Double.isFinite(groundRadius)
                    || groundRadius<=0 || groundRadius>8 || Math.abs(groundY)>12)
                throw new IllegalArgumentException("Invalid ritual layout");
            offerings=List.copyOf(offerings);
            if(offerings.size()>MAX_OFFERINGS || offerings.stream().anyMatch(v->
                    !Double.isFinite(v.x) || !Double.isFinite(v.y) || !Double.isFinite(v.z)
                    || Math.abs(v.x)>10 || Math.abs(v.z)>10 || Math.abs(v.y)>12))
                throw new IllegalArgumentException("Invalid offering anchors");
        }
    }
    @FunctionalInterface public interface Sink {
        void texturedQuad(Material material,V a,V b,V c,V d,float u0,float v0,float u1,float v1,
                float r0,float g0,float b0,float alpha0,float r1,float g1,float b1,float alpha1);
        default void gradientQuad(Material material,V a,V b,V c,V d,
                float r0,float g0,float b0,float alpha0,float r1,float g1,float b1,float alpha1) {
            texturedQuad(material,a,b,c,d,0,1,1,0,r0,g0,b0,alpha0,r1,g1,b1,alpha1);
        }
        default void quad(Material material,V a,V b,V c,V d,float r,float g,float blue,float alpha) {
            gradientQuad(material,a,b,c,d,r,g,blue,alpha,r,g,blue,alpha);
        }
    }
    public static final int FORM_TICKS=80, COMPLETE_TICKS=30, CANCEL_TICKS=14;
    // Shared square 16x16 source; its negative space and pixel proportions must remain intact.
    public static final double HEIGHT=2.1, WIDTH=2.1, RISE=1.7;

    public static double opacity(Frame f) {
        if(f.phase==Phase.BINDING)return 1;
        if(f.phase==Phase.COMPLETE || f.phase==Phase.CANCELLED)
            return 1-smooth(f.phaseTicks/(f.phase==Phase.COMPLETE?COMPLETE_TICKS:CANCEL_TICKS));
        return smooth(f.phaseTicks/12);
    }

    public static void emit(Sink sink, Frame f, Material only) {
        emit(sink,f,only,f.animationTicks);
    }

    public static void emit(Sink sink, Frame f, Material only,double worldTicks) {
        if(f.phase==Phase.WAITING){RitualRingEffects.emit(sink,f,only);return;}
        double t=f.animationTicks/20, p=smooth(f.phaseTicks/FORM_TICKS);
        boolean leaving=f.phase==Phase.COMPLETE || f.phase==Phase.CANCELLED;
        double reveal=leaving ? clamp(f.revealAtExit) : f.phase==Phase.FORMING ? p : 1;
        double fade=opacity(f);
        if(fade<.001) return;
        double closing=f.phase==Phase.COMPLETE?smooth(f.phaseTicks/COMPLETE_TICKS):0;
        double scale=(.22+.78*reveal)*(1-.32*closing);
        // The fully formed emblem's lower tip is above the matrix, not through its centre.
        V centre=new V(0,.2+RISE*reveal,0);
        if(closing>0 && f.recipient!=null)centre=centre.scale(1-closing).add(f.recipient.scale(closing));
        double impact=RitualOfferingEffects.arrival(f);
        if(only==Material.CREST || only==Material.CREST_HALO)
            RitualSigilEffects.emit(sink,f,only,centre,scale,reveal,fade,worldTicks);
        RitualRingEffects.emit(sink,f,only);
        RitualRadianceEffects.emit(sink,f,only,centre,scale,reveal,fade,t,impact);
        RitualOfferingEffects.emit(sink,f,only,centre,fade);
        if(only!=Material.RIBBON) return;
        if(f.phase==Phase.COMPLETE) {
            // A short inward contraction, not an explosive or permanent aura.
            V end=f.recipient==null?centre:f.recipient;
            billboard(sink,only,end,f.right,f.up,.55*(1-closing),.95*(1-closing),1,.87f,.61f,(float)(fade*.55));
        }
    }

    public static double smooth(double x) { x=clamp(x); return x*x*(3-2*x); }
    private static double clamp(double x) { return Math.max(0,Math.min(1,x)); }
    private RestraintGeometry() {}
}
