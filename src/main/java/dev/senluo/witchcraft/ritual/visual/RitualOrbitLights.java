package dev.senluo.witchcraft.ritual.visual;

import dev.senluo.witchcraft.ritual.visual.RestraintGeometry.*;
import static dev.senluo.witchcraft.ritual.visual.RitualLightGeometry.billboard;

/** Selected soft spirit heads. The caller owns session ticks, anchors and disposal.
 * P=20, H=31: O(P) sampling, O(P*H) drawing, fixed primitive history; no world queries.
 * Rendering interpolates recorded positions only. It never reconstructs a missed tick. */
public final class RitualOrbitLights {
    public static final int LOWER_COUNT=12, UPPER_COUNT=8, COUNT=LOWER_COUNT+UPPER_COUNT;
    public static final int SHORT_TICKS=5, LONG_TICKS=30, CAPACITY=LONG_TICKS+1;
    private static final int WORK=CAPACITY+1;
    private static final double TAU=Math.PI*2;
    private static final int[] PRIMARY={0xBCEDFC,0xF7A9A9,0xF0E8E8};
    private static final int[] SECONDARY={0x706187,0x887C9D,0x514763};
    private final double seed;
    private final double[] historyX=new double[COUNT*CAPACITY],historyY=new double[COUNT*CAPACITY],historyZ=new double[COUNT*CAPACITY];
    // One point's mesh scratch is reused for both tails and all heads, not allocated per draw.
    private final double[] x=new double[WORK],y=new double[WORK],z=new double[WORK],fraction=new double[WORK];
    private final V[] left=new V[WORK],right=new V[WORK];
    private final float[] red=new float[WORK],green=new float[WORK],blue=new float[WORK],alpha=new float[WORK];
    private long latest=Long.MIN_VALUE;
    private int cursor=-1,size;
    private double groundY=Double.NaN,groundRadius=Double.NaN;

    public RitualOrbitLights(long seed) { this.seed=(seed & 0xffffL)*(TAU/65536); }

    public void tick(long tick,double animationTicks,Layout layout,boolean active) {
        if(!active || tick==latest)return;
        if((latest!=Long.MIN_VALUE && tick!=latest+1)
                || groundY!=layout.groundY() || groundRadius!=layout.groundRadius())clear();
        groundY=layout.groundY();groundRadius=layout.groundRadius();
        cursor=(cursor+1)%CAPACITY;size=Math.min(CAPACITY,size+1);latest=tick;
        double lower=Math.max(.8,groundRadius*.97),upper=Math.min(1.95,lower*.72);
        for(int i=0;i<COUNT;i++) {
            boolean bottom=i<LOWER_COUNT;
            int local=bottom?i:i-LOWER_COUNT,count=bottom?LOWER_COUNT:UPPER_COUNT;
            // Each point owns phase, speed and direction. Neither plane consumes the ring's spin.
            double speed=(bottom?.018:.026)+(local%(bottom?5:4))*(bottom?.0026:.0032);
            double direction=(local%3==1?-1:1)*(bottom?1:-1);
            double angle=seed+local*TAU/count+.19*Math.sin(local*1.618+seed)+animationTicks*speed*direction;
            double radius=(bottom?lower:upper)+.045*Math.sin(local*2.399+seed);
            int at=i*CAPACITY+cursor;
            historyX[at]=Math.cos(angle)*radius;
            historyZ[at]=Math.sin(angle)*radius;
            historyY[at]=(bottom?groundY+.20:.27)+(bottom?.055:.09)*Math.sin(angle+local*.71);
        }
    }

    public void clear() {
        latest=Long.MIN_VALUE;cursor=-1;size=0;groundY=groundRadius=Double.NaN;
        java.util.Arrays.fill(left,null);java.util.Arrays.fill(right,null);
    }

    public void emit(Sink sink,Frame frame,double worldTime,Material material) {
        if(size==0)return;
        double opacity=RestraintGeometry.opacity(frame)*RestraintGeometry.smooth(frame.animationTicks()/16);
        if(opacity<.001)return;
        // Match the client's previous/current tick interpolation for both heads and trails.
        double displayTime=worldTime-1;
        if(material==Material.ORBIT_HEAD) {
            if(frame.phase()==Phase.COMPLETE || frame.phase()==Phase.CANCELLED)return;
            for(int i=0;i<COUNT;i++)head(sink,frame,i,displayTime,opacity);
        } else if(material==Material.ORBIT_SHORT || material==Material.ORBIT_LONG) {
            boolean longTail=material==Material.ORBIT_LONG;
            int lifetime=longTail?LONG_TICKS:SHORT_TICKS;
            double start=Math.max(latest-size+1,displayTime-lifetime),end=Math.min(latest,displayTime);
            if(end-start<1e-6)return;
            for(int i=0;i<COUNT;i++)trail(sink,frame,i,start,end,opacity,longTail,material);
        }
    }

    private void head(Sink sink,Frame f,int point,double time,double opacity) {
        position(point,time,0);
        V p=new V(x[0],y[0],z[0]);
        int primary=PRIMARY[point%PRIMARY.length],secondary=SECONDARY[point%SECONDARY.length];
        double pulse=1+.12*Math.sin(f.animationTicks()*.065+point*1.618+seed);
        double size=(point<LOWER_COUNT?.15:.13)*pulse;
        float r=channel(primary,16),g=channel(primary,8),b=channel(primary,0);
        billboard(sink,Material.ORBIT_HEAD,p,f.right(),f.up(),size*2.8,size*2.8,
                channel(secondary,16),channel(secondary,8),channel(secondary,0),(float)(opacity*.20));
        billboard(sink,Material.ORBIT_HEAD,p,f.right(),f.up(),size*1.25,size*1.45,r,g,b,(float)(opacity*.60));
        billboard(sink,Material.ORBIT_HEAD,p,f.right(),f.up(),size*.48,size*.58,1,.98f,.94f,(float)(opacity*.80));
        billboard(sink,Material.ORBIT_HEAD,p,f.right(),f.up(),size*1.5,size*.16,r,g,b,(float)(opacity*.45));
        billboard(sink,Material.ORBIT_HEAD,p,f.right(),f.up(),size*.15,size*1.05,r,g,b,(float)(opacity*.40));
    }

    private void trail(Sink sink,Frame f,int point,double start,double end,double opacity,boolean longTail,Material material) {
        int count=0;
        position(point,start,count);fraction[count++]=0;
        for(double t=Math.floor(start)+1;t<end-1e-6;t++) {
            position(point,t,count);fraction[count++]=(t-start)/(end-start);
        }
        position(point,end,count);fraction[count++]=1;
        int from=(longTail?PRIMARY:SECONDARY)[point%PRIMARY.length];
        int to=(longTail?SECONDARY:PRIMARY)[point%PRIMARY.length];
        double width=(point<LOWER_COUNT?.23:.20)*(longTail?.6:1);
        double strength=.9*opacity*(longTail?.2:1);
        double previousSideX=f.right().x(),previousSideY=f.right().y(),previousSideZ=f.right().z();
        for(int k=0;k<count;k++) {
            int before=Math.max(0,k-1),after=Math.min(count-1,k+1);
            double dx=x[after]-x[before],dy=y[after]-y[before],dz=z[after]-z[before];
            double tx=dx*f.right().x()+dy*f.right().y()+dz*f.right().z();
            double ty=dx*f.up().x()+dy*f.up().y()+dz*f.up().z();
            double length=Math.sqrt(tx*tx+ty*ty);
            if(length>1e-8) {
                previousSideX=(-ty*f.right().x()+tx*f.up().x())/length;
                previousSideY=(-ty*f.right().y()+tx*f.up().y())/length;
                previousSideZ=(-ty*f.right().z()+tx*f.up().z())/length;
            }
            double hx=previousSideX*width*.5,hy=previousSideY*width*.5,hz=previousSideZ*width*.5;
            left[k]=new V(x[k]-hx,y[k]-hy,z[k]-hz);right[k]=new V(x[k]+hx,y[k]+hy,z[k]+hz);
            double q=fraction[k],mix=1-Math.cos(Math.min(1,q*2)*Math.PI*.5);
            red[k]=lerp(from,to,16,mix);green[k]=lerp(from,to,8,mix);blue[k]=lerp(from,to,0,mix);
            alpha[k]=(float)(strength*q);
        }
        for(int k=1;k<count;k++)sink.texturedQuad(material,left[k-1],right[k-1],right[k],left[k],
                0,(float)fraction[k-1],1,(float)fraction[k],
                red[k-1],green[k-1],blue[k-1],alpha[k-1],red[k],green[k],blue[k],alpha[k]);
    }

    private void position(int point,double time,int target) {
        double behind=Math.max(0,Math.min(size-1,latest-time));
        int near=(int)behind,far=Math.min(size-1,near+1);
        double fraction=behind-near;
        int a=point*CAPACITY+Math.floorMod(cursor-near,CAPACITY);
        int b=point*CAPACITY+Math.floorMod(cursor-far,CAPACITY);
        x[target]=historyX[a]+(historyX[b]-historyX[a])*fraction;
        y[target]=historyY[a]+(historyY[b]-historyY[a])*fraction;
        z[target]=historyZ[a]+(historyZ[b]-historyZ[a])*fraction;
    }
    private static float channel(int color,int shift){return ((color>>shift)&255)/255f;}
    private static float lerp(int a,int b,int shift,double fraction) {
        float start=channel(a,shift);return (float)(start+(channel(b,shift)-start)*fraction);
    }
}
