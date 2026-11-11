package dev.senluo.witchcraft.ritual.visual;

import dev.senluo.witchcraft.ritual.visual.RestraintGeometry.*;
import static dev.senluo.witchcraft.ritual.visual.RitualLightGeometry.billboard;

/** One accepted-target receipt, two local flashes, no connecting geometry.
 * Mirrors the paired star placements of Malum acceptSacrificeParticles, with our red-star anchor.
 * Caller supplies receipt age and frozen event positions; this module cannot accept a target. */
public final class RecipientAcceptanceEffects {
    public record View(double age,V star,V recipient,V right,V up,double opacity) {}
    public static final int LIFETIME=25;
    public static void emit(Sink sink,View v) { emit(sink,v,null); }
    public static void emit(Sink sink,View v,Material only) {
        if(v.age<0||v.age>=25||v.opacity<=0||v.recipient==null)return;
        double q=v.age/25;
        double size=q<.5?mix(1,.125,sineInOut(q*2)):mix(.125,0,1-Math.cos((q-.5)*Math.PI));
        double alpha=q<.5?mix(.9,.07,1-Math.cos(q*Math.PI)):mix(.07,0,Math.sqrt(1-Math.pow(1-(q-.5)*2,2)));
        for(int end=0;end<2;end++) {
            V centre=end==0?v.star:v.recipient;
            for(int i=0;i<3;i++) {
                double scale=(end==0?1.30:1.05)*(1+i*.13)*size;
                double strength=alpha*v.opacity*(i==0?.62:.23);
                double angle=i*.81+v.age*(i%2==0?.05:-.065);
                V right=v.right.scale(Math.cos(angle)).add(v.up.scale(Math.sin(angle)));
                V up=v.up.scale(Math.cos(angle)).sub(v.right.scale(Math.sin(angle)));
                star(sink,centre,right,up,scale,strength,only);
                // Malum emits a second star in the horizontal plane at each same endpoint.
                V horizontal=new V(Math.cos(angle),0,Math.sin(angle));
                star(sink,centre,horizontal,new V(-Math.sin(angle),0,Math.cos(angle)),scale,strength*.5,only);
            }
        }
    }
    private static void star(Sink sink,V p,V right,V up,double size,double alpha,Material only) {
        if(only==null || only==Material.GLOW) {
        billboard(sink,Material.GLOW,p,right,up,size*2.8,size*2.8,.60f,.10f,.10f,(float)(alpha*.26));
        billboard(sink,Material.GLOW,p,right,up,size*.30,size*.30,1f,.28f,.34f,(float)alpha);
        }
        if(only==null || only==Material.RIBBON)for(int axis=0;axis<2;axis++)for(int sign:new int[]{-1,1}) {
            V along=(axis==0?up:right).scale(size*(axis==0?1:.72)*sign);
            V side=(axis==0?right:up).scale(size*.10);
            sink.gradientQuad(Material.RIBBON,p.sub(side),p.add(side),p.add(along),p.add(along),
                    1f,.21f,.28f,(float)alpha,.60f,.10f,.10f,0);
        }
    }
    private static double mix(double a,double b,double q){return a+(b-a)*q;}
    private static double sineInOut(double q){return .5-.5*Math.cos(Math.PI*q);}
    private RecipientAcceptanceEffects(){}
}
