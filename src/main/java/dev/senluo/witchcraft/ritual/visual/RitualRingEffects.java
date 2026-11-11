package dev.senluo.witchcraft.ritual.visual;

import dev.senluo.witchcraft.ritual.visual.RestraintGeometry.*;
import static dev.senluo.witchcraft.ritual.visual.RestraintGeometry.smooth;
import static dev.senluo.witchcraft.ritual.visual.RitualLightGeometry.billboard;

/** Three narrow light volumes: intersecting soft slices, continuous aureoles and local gap mist.
 * O(3 * 4 * segments * 3), segments <= 24; no scans, particle entities or unbounded history.
 * Mist samples its frozen emission pose, then drifts and fades. It never follows a live gap. */
public final class RitualRingEffects {
    private static final double TAU=Math.PI*2,SPAN=1.27;
    private static final int SLICES=3,MAX_SEGMENTS=24,MIST_PERIOD=8,MIST_LIFE=16;
    private record Ring(double y,double radius,double spin,double width,double alpha) {}

    public static void emit(Sink sink,Frame f,Material only) {
        if(only!=Material.RING_BODY && only!=Material.RING_HALO && only!=Material.RING_MIST)return;
        double opacity=RestraintGeometry.opacity(f)*smooth(f.animationTicks()/16);
        if(opacity<.001)return;
        for(int ring=0;ring<3;ring++) {
            Ring pose=pose(f.layout(),ring,f.animationTicks());
            if(only==Material.RING_MIST)mist(sink,f,ring,opacity);
            else band(sink,f,pose,only,opacity);
        }
    }

    private static Ring pose(Layout layout,int ring,double ticks) {
        double lower=Math.max(.8,layout.groundRadius()*.97),upper=Math.min(1.95,lower*.72);
        if(ring==0)return new Ring(layout.groundY()+.14,lower,ticks*.0075,.19,.82);
        if(ring==1)return new Ring(.15,upper,-ticks*.009+.40,.15,.76);
        double q=(ticks+22)%100/100;
        return new Ring(layout.groundY()+.30+2.20*q,lower*(.82-.37*q),-ticks*.0065+Math.PI/4,
                .17,.62*Math.sin(Math.PI*q));
    }

    private static void band(Sink sink,Frame f,Ring ring,Material material,double opacity) {
        if(ring.alpha()*opacity<.001)return;
        int segments=Math.clamp((int)Math.ceil(SPAN*ring.radius()/.24),8,MAX_SEGMENTS);
        int layers=material==Material.RING_BODY?SLICES:1;
        for(int arc=0;arc<4;arc++)for(int layer=0;layer<layers;layer++) {
            double begin=ring.spin()+arc*TAU/4;
            V[] previous=edges(f,ring,begin,material,layer);
            for(int segment=1;segment<=segments;segment++) {
                double q=segment/(double)segments,before=(segment-1)/(double)segments;
                V[] next=edges(f,ring,begin+SPAN*q,material,layer);
                int c0=color(arc,before),c1=color(arc,q);
                // Body density is strongest at its common core. It is not a lit opaque tube.
                float strength=(float)(opacity*ring.alpha()*(material==Material.RING_HALO?.24:layer==0?.33:.15));
                float shade=material==Material.RING_HALO?.82f:1;
                sink.texturedQuad(material,previous[0],previous[1],next[1],next[0],0,(float)before,1,(float)q,
                        channel(c0,16)*shade,channel(c0,8)*shade,channel(c0,0)*shade,strength,
                        channel(c1,16)*shade,channel(c1,8)*shade,channel(c1,0)*shade,strength);
                previous=next;
            }
        }
    }
    private static V[] edges(Frame f,Ring ring,double angle,Material material,int layer) {
        V radial=new V(Math.cos(angle),0,Math.sin(angle));
        V centre=radial.scale(ring.radius()).add(new V(0,ring.y(),0));
        V side;
        if(material==Material.RING_HALO) {
            V tangent=new V(-Math.sin(angle),0,Math.cos(angle));
            side=tangent.cross(f.camera().sub(centre)).unit().scale(ring.width()*2.0);
        } else {
            // Three intersecting Gaussian slices fill a flattened elliptical light volume.
            // The cross-section has actual height; every boundary fades instead of closing with a cap.
            double cross=layer*Math.PI/SLICES;
            side=radial.scale(Math.cos(cross)*ring.width()*.5)
                    .add(new V(0,Math.sin(cross)*ring.width()*.19,0));
        }
        return new V[]{centre.sub(side),centre.add(side)};
    }

    private static void mist(Sink sink,Frame f,int ring,double opacity) {
        double time=f.animationTicks();
        boolean leaving=f.phase()==Phase.COMPLETE||f.phase()==Phase.CANCELLED;
        double stop=leaving?time-f.phaseTicks():Double.POSITIVE_INFINITY;
        for(int arc=0;arc<4;arc++)for(int end=0;end<2;end++)for(int older=0;older<2;older++) {
            int phase=(ring*5+arc*3+end*2)%MIST_PERIOD;
            double born=Math.floor((time-phase)/MIST_PERIOD)*MIST_PERIOD+phase-older*MIST_PERIOD;
            if(born<0||born>=stop)continue;
            double age=time-born,q=age/MIST_LIFE;
            if(q<0||q>=1)continue;
            Ring source=pose(f.layout(),ring,born);
            double angle=source.spin()+arc*TAU/4+end*SPAN+(end==0?1:-1)*SPAN*.025;
            V radial=new V(Math.cos(angle),0,Math.sin(angle));
            V intoGap=new V(-Math.sin(angle),0,Math.cos(angle)).scale(end==0?-1:1);
            // Frozen source, short motion into the gap and outward; no filament, jet or attached curl.
            V centre=radial.scale(source.radius()).add(new V(0,source.y(),0))
                    .add(intoGap.scale(source.width()*.40*q)).add(radial.scale(source.width()*.20*q))
                    .add(new V(0,source.width()*.24*q,0));
            // Low-density cloud + weaker bloom share birth, motion and lifetime, as in
            // Malum's SpiritCrucible -> SpiritLightSpecs. The gap emitter is our adaptation.
            double size=source.width()*(.95+1.05*smooth(q));
            double alpha=opacity*source.alpha()*.29*smooth(age/3)*Math.pow(1-q,2);
            int c=color(arc,end);
            double tx=intoGap.x()*f.right().x()+intoGap.y()*f.right().y()+intoGap.z()*f.right().z();
            double ty=intoGap.x()*f.up().x()+intoGap.y()*f.up().y()+intoGap.z()*f.up().z();
            V along=f.right().scale(tx).add(f.up().scale(ty)).unit();
            V across=f.up().scale(tx).sub(f.right().scale(ty)).unit();
            billboard(sink,Material.RING_MIST,centre,along,across,size*1.25,size*.85,
                    channel(c,16),channel(c,8),channel(c,0),(float)alpha);
        }
    }
    private static int color(int arc,double along) {
        int shadow=0x887C9D,light=arc%2==0?0xBCEDFC:0xF7C8B7;
        double mix=.55+.45*Math.pow(Math.abs(2*along-1),3);
        int r=(int)Math.round(((shadow>>16)&255)*(1-mix)+((light>>16)&255)*mix);
        int g=(int)Math.round(((shadow>>8)&255)*(1-mix)+((light>>8)&255)*mix);
        int b=(int)Math.round((shadow&255)*(1-mix)+(light&255)*mix);
        return (r<<16)|(g<<8)|b;
    }
    private static float channel(int color,int shift){return ((color>>shift)&255)/255f;}
    private RitualRingEffects(){}
}
