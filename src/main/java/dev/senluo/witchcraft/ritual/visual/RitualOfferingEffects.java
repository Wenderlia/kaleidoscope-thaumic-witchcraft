package dev.senluo.witchcraft.ritual.visual;

import dev.senluo.witchcraft.ritual.visual.RestraintGeometry.*;
import static dev.senluo.witchcraft.ritual.visual.RitualLightGeometry.*;

/** Bounded post-consumption light packets. All positions and ages are supplied by the event adapter. */
public final class RitualOfferingEffects {
    public static final int LIFETIME=30;
    public static double arrival(Frame frame) {
        double result=0;
        for(OfferingPulse pulse:frame.pulses())
            result=Math.max(result,Math.max(0,1-Math.abs(pulse.age()-24)/6));
        return result;
    }
    public static void emit(Sink sink,Frame frame,Material material,V crest,double fade) {
        if(material==Material.RADIAL) {
            for(OfferingPulse pulse:frame.pulses()) {
                // One brief horizontal response when the committed offering reaches the matrix.
                double arrival=(pulse.age()-12)/10;
                if(arrival<0 || arrival>1)continue;
                double radius=.32+.36*RestraintGeometry.smooth(arrival);
                billboard(sink,material,new V(0,.14,0),new V(1,0,0),new V(0,0,1),radius*2,radius*2,
                        1,.91f,.73f,(float)(fade*Math.sin(arrival*Math.PI)*.52));
            }
            return;
        }
        if(material!=Material.GLOW)return;
        for(OfferingPulse pulse:frame.pulses()) {
            for(int i=0;i<4;i++) {
                double age=pulse.age()-i*2;
                if(age<0 || age>=24)continue;
                double p=age/24;
                V matrix=new V(0,.14,0);
                V point;
                if(age<14) {
                    double travel=RestraintGeometry.smooth(age/14);
                    point=pulse.source().scale(1-travel).add(matrix.scale(travel))
                            .add(new V(0,Math.sin(age/14*Math.PI)*.35,0));
                } else {
                    double travel=RestraintGeometry.smooth((age-14)/10);
                    point=matrix.scale(1-travel).add(crest.scale(travel));
                }
                double alpha=fade*Math.sin(p*Math.PI)*(.8-i*.1);
                billboard(sink,material,point,frame.right(),frame.up(),.50,.50,1,.89f,.70f,(float)(alpha*.42));
                billboard(sink,material,point,frame.right(),frame.up(),.11,.11,1,.98f,.85f,(float)alpha);
            }
        }
    }
    private RitualOfferingEffects(){}
}
