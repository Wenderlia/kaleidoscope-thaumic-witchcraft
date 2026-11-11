package dev.senluo.witchcraft.ritual.visual;

import dev.senluo.witchcraft.ritual.visual.RestraintGeometry.*;
import static dev.senluo.witchcraft.ritual.visual.RestraintGeometry.smooth;
import static dev.senluo.witchcraft.ritual.visual.RitualLightGeometry.billboard;

/** A fixed light envelope and six short ascending streaks. These are presentation samples,
 * not persistent particles: no entity scan, random allocation, or catch-up emission. */
public final class RitualRadianceEffects {
    public static void emit(Sink sink,Frame f,Material only,V crest,double scale,double reveal,
            double fade,double time,double impact) {
        if(only!=Material.GLOW)return;
        double visible=fade*smooth(reveal/.45),response=1+.45*impact;
        if(visible<.001)return;
        V x=new V(1,0,0),z=new V(0,0,1);
        // Light gathers at the matrix, then in a small collar below the suspended seal.
        // Horizontal discs and camera-facing cores have different jobs, not a full-icon backdrop.
        for(int i=0;i<2;i++) {
            V anchor=i==0?new V(0,.18,0):crest.add(f.up().scale(-.35*scale));
            double radius=i==0?1.55:1.10*scale;
            billboard(sink,only,anchor,x,z,radius,radius,1,.76f,.40f,(float)(visible*.26*response));
            billboard(sink,only,anchor,f.right(),f.up(),radius*.65,radius*.65,
                    1,.87f,.58f,(float)(visible*.19*response));
        }
        // Short highlights travel from the matrix toward the seal. Their closed-form ages
        // keep the same six samples at every frame rate; committed offerings strengthen them.
        for(int i=0;i<6;i++) {
            double q=time/1.8+i/6.0;q-=Math.floor(q);
            double envelope=Math.pow(Math.sin(q*Math.PI),1.4)*visible;
            double angle=i*Math.PI/3+time*.38;
            double radius=(.52-.24*q)*scale;
            V anchor=new V(Math.cos(angle)*radius,.30+(crest.y()-.45)*q,Math.sin(angle)*radius);
            double length=(.28+.28*q)*scale,alpha=envelope*(.48+.22*impact);
            billboard(sink,only,anchor,f.right(),f.up(),.13*scale,length,
                    1,.91f,.70f,(float)alpha);
            billboard(sink,only,anchor,f.right(),f.up(),.40*scale,length*1.6,
                    .72f,.57f,1,(float)(alpha*.28));
        }
    }
    private RitualRadianceEffects() {}
}
