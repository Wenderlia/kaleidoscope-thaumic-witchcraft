package dev.senluo.witchcraft.ritual.visual;

import dev.senluo.witchcraft.ritual.visual.RestraintGeometry.*;
import static dev.senluo.witchcraft.ritual.visual.RitualLightGeometry.billboard;

/** Malum 1.9.0.369 SoulBrazier: five transparent and five late additive quads.
 * Each frame resets the ordered primary/secondary cursor. Copies are not temporal ghosts.
 * The caller owns stage/anchor/size; the material owns nearest UV distortion and blending. */
public final class RitualSigilEffects {
    // The approved local palette, shared by runtime and preview. Full RGBA remains in the texture.
    private static final int[] PRIMARY={0xD4C4C4,0xF0E8E8,0xDEFCFF,0xFCFFD6};
    private static final int[] SECONDARY={0x514763,0x706187,0x3D2F4D,0x887C9D};
    public static final double GAIN=.50;

    public static void emit(Sink sink,Frame frame,Material material,V centre,double scale,
            double reveal,double fade,double worldTicks) {
        layer(sink,material,centre,frame.right(),frame.up(),RestraintGeometry.WIDTH*scale,
                .9*GAIN*fade*reveal,worldTicks,0,5);
    }

    /** A subrange lets the existing preview split main light and echoes without a second algorithm. */
    public static void layer(Sink sink,Material material,V centre,V right,V up,double size,double alpha,
            double worldTicks,int first,int end) {
        if(alpha<.001)return;
        boolean shade=material==Material.CREST_HALO;
        if(!shade && material!=Material.CREST)return;
        V normal=right.cross(up).unit();
        double phase=((worldTicks%160)/160)*6.28;
        for(int pass=first;pass<end;pass++) {
            V position=centre;
            if(pass>0) {
                int i=pass-1;
                double angle=i*1.57+phase,distance=size*.04;
                position=centre.add(right.scale(Math.cos(angle)*distance)).add(up.scale(Math.sin(angle)*distance));
                if(shade)position=position.add(normal.scale(-.02*i));
            }
            // Native cursor: reserve primary 0, consume primary 1..5, then secondary 6..9.
            int color=shade?PRIMARY[(pass+1)%PRIMARY.length]
                    :pass==0?PRIMARY[0]:SECONDARY[(pass+5)%SECONDARY.length];
            float gain=shade?.32f:1;
            float opacity=(float)(alpha*(!shade && pass==0?.8:.4));
            billboard(sink,material,position,right,up,size,size,
                    ((color>>16)&255)/255f*gain,((color>>8)&255)/255f*gain,(color&255)/255f*gain,opacity);
        }
    }
    private RitualSigilEffects(){}
}
