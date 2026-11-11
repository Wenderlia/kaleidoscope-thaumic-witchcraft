package dev.senluo.witchcraft.ritual.visual;

import dev.senluo.witchcraft.ritual.visual.RestraintGeometry.*;

/** Stateless light primitives shared by scene modules. No world access, timing or network ownership. */
public final class RitualLightGeometry {
    private static final V Y=new V(0,1,0);
    public static void billboard(Sink s, Material m, V centre, V right, V up, double width, double height,
            float r,float g,float b,float a) {
        V x=right.scale(width*.5), y=up.scale(height*.5);
        s.quad(m,centre.sub(x).sub(y),centre.add(x).sub(y),centre.add(x).add(y),centre.sub(x).add(y),r,g,b,a);
    }
    private static V curve(V a,V b,double q,double bow) {
        return a.scale(1-q).add(b.scale(q)).add(Y.scale(Math.sin(q*Math.PI)*bow));
    }
    public static void ribbon(Sink s,Frame f,V a,V b,double bow,double width,double opacity,
            float r,float g,float blue,double time,int segments) {
        ribbon(s,f,a,b,bow,width,opacity,r,g,blue,time,segments,0);
    }
    public static void ribbon(Sink s,Frame f,V a,V b,double bow,double width,double opacity,
            float r,float g,float blue,double time,int segments,double violetMix) {
        V direction=b.sub(a);
        if(direction.x()*direction.x()+direction.y()*direction.y()+direction.z()*direction.z()<1e-6)return;
        for(int i=0;i<segments;i++) {
            double u=(double)i/segments,v=(double)(i+1)/segments;
            V p=curve(a,b,u,bow).add(f.right().scale(Math.sin(u*Math.PI)*bow*.65));
            V q=curve(a,b,v,bow).add(f.right().scale(Math.sin(v*Math.PI)*bow*.65));
            // Adjacent segments share the same endpoint tangent and width: no cracks in a wide soft sheet.
            V tangent0=direction.add(Y.scale(Math.cos(u*Math.PI)*Math.PI*bow))
                    .add(f.right().scale(Math.cos(u*Math.PI)*Math.PI*bow*.65));
            V tangent1=direction.add(Y.scale(Math.cos(v*Math.PI)*Math.PI*bow))
                    .add(f.right().scale(Math.cos(v*Math.PI)*Math.PI*bow*.65));
            stripSegment(s,f,p,q,tangent0,tangent1,u,v,width,opacity,r,g,blue,time,violetMix);
        }
    }
    /** A radial quadratic profile rotated around world Y. Camera orientation only faces the sheet;
     * it never changes the path, so equal angular offsets have identical curvature. */
    public static void orbitArc(Sink s,Frame f,double bottomY,double topY,double bottomRadius,
            double topRadius,double angle,double width,double opacity,float r,float g,float blue,
            double time,int segments,double violetMix) {
        double height=topY-bottomY,spread=bottomRadius-topRadius;
        if(height<=1e-6)return;
        V radial=new V(Math.cos(angle),0,Math.sin(angle));
        boolean outward=f.glyph().outwardArcs();
        for(int i=0;i<segments;i++) {
            double u=(double)i/segments,v=(double)(i+1)/segments;
            // Reflect the radial profile across the endpoint chord: concave (1-u)^2 becomes
            // convex 1-u^2. Anchors, rotation, radius bounds and segment count stay identical.
            V p=radial.scale(topRadius+spread*(outward?1-u*u:(1-u)*(1-u))).add(Y.scale(bottomY+height*u));
            V q=radial.scale(topRadius+spread*(outward?1-v*v:(1-v)*(1-v))).add(Y.scale(bottomY+height*v));
            V tangent0=radial.scale(-2*spread*(outward?u:1-u)).add(Y.scale(height));
            V tangent1=radial.scale(-2*spread*(outward?v:1-v)).add(Y.scale(height));
            stripSegment(s,f,p,q,tangent0,tangent1,u,v,width,opacity,r,g,blue,time,violetMix);
        }
    }
    private static void stripSegment(Sink s,Frame f,V p,V q,V tangent0,V tangent1,double u,double v,
            double width,double opacity,float r,float g,float blue,double time,double violetMix) {
            V side0=tangent0.cross(f.camera().sub(p)).unit(),side1=tangent1.cross(f.camera().sub(q)).unit();
            double w0=width*Math.pow(Math.sin(Math.PI*u),.45)*.5,w1=width*Math.pow(Math.sin(Math.PI*v),.45)*.5;
            // Evaluate on shared endpoints, not segment midpoints. GPU interpolation keeps
            // density and colour continuous without more quads, particles or texture samples.
            double mix0=violetMix*(.5+.5*Math.sin(u*2*Math.PI-time*.7));
            double mix1=violetMix*(.5+.5*Math.sin(v*2*Math.PI-time*.7));
            s.gradientQuad(Material.RIBBON,p.sub(side0.scale(w0)),p.add(side0.scale(w0)),q.add(side1.scale(w1)),q.sub(side1.scale(w1)),
                    (float)(r+(.64-r)*mix0),(float)(g+(.53-g)*mix0),(float)(blue+(.92-blue)*mix0),(float)(opacity*density(u,time)),
                    (float)(r+(.64-r)*mix1),(float)(g+(.53-g)*mix1),(float)(blue+(.92-blue)*mix1),(float)(opacity*density(v,time)));
    }
    private static double density(double position,double time) {
        double wave=.5+.5*Math.cos(position*Math.PI*4-time*2.8);
        return .42+.58*wave*wave;
    }
    private RitualLightGeometry() {}
}
