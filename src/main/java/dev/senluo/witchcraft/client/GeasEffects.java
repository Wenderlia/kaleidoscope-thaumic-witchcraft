package dev.senluo.witchcraft.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.ritual.GeasRitualVisuals;
import dev.senluo.witchcraft.ritual.RitualOfferingArea;
import dev.senluo.witchcraft.ritual.visual.RestraintGeometry;
import dev.senluo.witchcraft.ritual.visual.GeasGlyph;
import dev.senluo.witchcraft.ritual.visual.RestraintGeometry.*;
import dev.senluo.witchcraft.ritual.visual.RitualOfferingEffects;
import dev.senluo.witchcraft.ritual.visual.RitualOrbitLights;
import dev.senluo.witchcraft.ritual.visual.RecipientAcceptanceEffects;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.*;
import org.joml.Vector3f;

/** Disposable stage snapshots. No texture, bloom, attachment, or tick is applied to the recipient entity. */
@EventBusSubscriber(modid=KaleidoscopeWitchcraft.MOD_ID,value=Dist.CLIENT)
public final class GeasEffects {
    private static final int MAX_TRACKED=32, MAX_VISIBLE=8;
    private static final Map<UUID,Effect> EFFECTS=new LinkedHashMap<>();
    private static final Map<UUID,Long> FINISHED=new LinkedHashMap<>();
    private static final ArrayList<Effect> VISIBLE=new ArrayList<>(MAX_TRACKED);
    private static final Map<UUID,GeasRitualVisuals.Accepted> ACCEPTED=new LinkedHashMap<>();
    private static final Map<UUID,Long> ACCEPTED_SEEN=new LinkedHashMap<>();
    private static final ArrayList<GeasRitualVisuals.Accepted> VISIBLE_FLASHES=new ArrayList<>(MAX_TRACKED);
    private record DrawGroup(Material material,GeasGlyph glyph,RenderType type) {}
    private static final List<DrawGroup> GROUPS=groups();
    private static final WorldEffectPass PASS=new WorldEffectPass("geas binding", WorldEffectPass.Composite.SCREEN,
            GROUPS.stream().map(DrawGroup::type).toArray(RenderType[]::new));
    private static final WorldEffectPass DIRECT=new WorldEffectPass("geas sigil and spirits",WorldEffectPass.Composite.DIRECT,
            GeasRenderTypes.materials());
    private static final List<DrawGroup> LIGHT_PARTS=List.of(
            new DrawGroup(Material.RING_BODY,null,GeasRenderTypes.RING_BODY),
            new DrawGroup(Material.RING_HALO,null,GeasRenderTypes.RING_HALO),
            new DrawGroup(Material.RING_MIST,null,GeasRenderTypes.RING_MIST),
            new DrawGroup(Material.ORBIT_LONG,null,GeasRenderTypes.ORBIT_LONG),
            new DrawGroup(Material.ORBIT_SHORT,null,GeasRenderTypes.ORBIT_SHORT),
            new DrawGroup(Material.ORBIT_HEAD,null,GeasRenderTypes.ORBIT_HEAD));
    private static List<DrawGroup> groups() {
        var result=new ArrayList<DrawGroup>();
        // Existing envelopes keep their compositor; glyphs and spirit tails use direct materials.
        for(Material material:List.of(Material.RIBBON,Material.GLOW)) {
            result.add(new DrawGroup(material,null,
                    EffectMaterial.make(material.name().toLowerCase(java.util.Locale.ROOT),true)));
        }
        return List.copyOf(result);
    }
    private static ClientLevel level;

    private static final class Effect {
        GeasRitualVisuals.State state;
        final Vec3 origin;
        RitualOfferingArea footprint;
        final long born;
        long received;
        double exitReveal=1;
        Frame prepared;
        Layout layout;
        final RitualOrbitLights lights;
        final ArrayList<TimedPulse> pulses=new ArrayList<>(4);
        long lastBurst;
        RitualHum hum;
        Effect(GeasRitualVisuals.State s,long now){
            state=s;origin=Vec3.atCenterOf(s.matrix());born=received=now;refreshFootprint();
            lights=new RitualOrbitLights(s.session().getLeastSignificantBits());
            lights.tick(now,0,layout,!GeasRitualVisuals.terminal(s.phase()));
        }
        void refreshFootprint(){
            footprint=RitualOfferingArea.visualFootprint(state.plane(),state.ring());
            layout=new Layout(state.plane().getY()+1.025-origin.y,2*state.ring()+1);
        }
        double age(double now){return state.phaseTicks()+Math.max(0,now-received);}
    }
    private record TimedPulse(V source,long tick) {}
    private static final class RitualHum extends AbstractTickableSoundInstance {
        final Effect effect;
        RitualHum(Effect effect) {
            super(SoundEvents.BEACON_AMBIENT,SoundSource.BLOCKS,RandomSource.create());this.effect=effect;
            x=effect.origin.x;y=effect.origin.y;z=effect.origin.z;looping=true;delay=0;volume=.05f;pitch=.65f;
        }
        @Override public void tick() {
            if(level==null || EFFECTS.get(effect.state.session())!=effect || GeasRitualVisuals.terminal(effect.state.phase())){stop();return;}
            double progress=Math.min(1,effect.age(level.getGameTime())/80);
            volume=(float)(.05+.07*progress);pitch=(float)(.65+.12*progress);
        }
        void finish(){stop();}
    }
    private static void cue(Effect effect,SoundEvent sound,float volume,float pitch) {
        if(level!=null && Minecraft.getInstance().player!=null
                && Minecraft.getInstance().player.position().distanceToSqr(effect.origin)<=32*32)
            level.playLocalSound(effect.origin.x,effect.origin.y,effect.origin.z,sound,SoundSource.BLOCKS,volume,pitch,false);
    }
    private static void dispose(Effect effect){if(effect.hum!=null)effect.hum.finish();effect.pulses.clear();effect.lights.clear();}

    @EventBusSubscriber(modid=KaleidoscopeWitchcraft.MOD_ID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
    public static final class Setup {
        @SubscribeEvent public static void setup(FMLClientSetupEvent event){event.enqueueWork(()->{
            GeasRitualVisuals.install(GeasEffects::receive);GeasRitualVisuals.installBursts(GeasEffects::receiveBurst);
            GeasRitualVisuals.installAccepted(GeasEffects::receiveAcceptance);
        });}
        @SubscribeEvent public static void reload(RegisterClientReloadListenersEvent event){
            event.registerReloadListener((ResourceManagerReloadListener)resources->Minecraft.getInstance().execute(GeasEffects::clear));
        }
    }

    private static void receive(GeasRitualVisuals.State s){
        ClientLevel current=Minecraft.getInstance().level;changeLevel(current);
        if(current==null || !current.dimension().location().equals(s.dimension()) || !current.hasChunkAt(s.matrix()))return;
        long now=current.getGameTime();
        if(FINISHED.containsKey(s.session()))return;
        Effect effect=EFFECTS.get(s.session());
        boolean terminal=GeasRitualVisuals.terminal(s.phase());
        if(terminal)ACCEPTED.remove(s.session());
        // A terminal event alone must not replay a ritual for late joiners/reloaded clients.
        if(terminal && effect==null)return;
        if(effect!=null) {
            if(s.stamp()<effect.state.stamp() || !s.matrix().equals(effect.state.matrix())
                    || s.glyph()!=effect.state.glyph())return;
            if(terminal)effect.exitReveal=effect.state.phase()==Phase.WAITING?0:effect.state.phase()==Phase.FORMING
                    ?RestraintGeometry.smooth(effect.age(now)/RestraintGeometry.FORM_TICKS):1;
            boolean forming=effect.state.phase()==Phase.WAITING && s.phase()==Phase.FORMING;
            effect.state=s;effect.received=now;effect.refreshFootprint();
            if(forming && s.phaseTicks()<8)cue(effect,SoundEvents.ENCHANTMENT_TABLE_USE,.32f,.72f);
        } else {
            if(EFFECTS.size()>=MAX_TRACKED)dispose(EFFECTS.remove(EFFECTS.keySet().iterator().next()));
            effect=new Effect(s,now);EFFECTS.put(s.session(),effect);
            if(s.phase()==Phase.FORMING && s.phaseTicks()<8)cue(effect,SoundEvents.ENCHANTMENT_TABLE_USE,.32f,.72f);
        }
        if(!terminal && s.phase()!=Phase.WAITING && effect.hum==null
                && EFFECTS.values().stream().filter(e->e.hum!=null).count()<MAX_VISIBLE
                && Minecraft.getInstance().player!=null && Minecraft.getInstance().player.position().distanceToSqr(effect.origin)<=32*32) {
            effect.hum=new RitualHum(effect);Minecraft.getInstance().getSoundManager().play(effect.hum);
        }
        if(terminal){
            if(effect.hum!=null)effect.hum.finish();
            if(s.phase()==Phase.COMPLETE)cue(effect,SoundEvents.RESPAWN_ANCHOR_CHARGE,.38f,1.35f);
            FINISHED.put(s.session(),now);
            while(FINISHED.size()>64)FINISHED.remove(FINISHED.keySet().iterator().next());
        }
    }
    private static void receiveBurst(GeasRitualVisuals.Burst burst) {
        ClientLevel current=Minecraft.getInstance().level;changeLevel(current);
        if(current==null || !current.dimension().location().equals(burst.dimension()))return;
        Effect effect=EFFECTS.get(burst.session());long now=current.getGameTime();
        if(effect==null || !effect.state.matrix().equals(burst.matrix()) || GeasRitualVisuals.terminal(effect.state.phase())
                || burst.sequence()<=effect.lastBurst || now-burst.stamp()>RitualOfferingEffects.LIFETIME || burst.stamp()>now+20)return;
        effect.lastBurst=burst.sequence();
        for(var pos:burst.sources()) {
            if(effect.pulses.size()==4)effect.pulses.remove(0);
            effect.pulses.add(new TimedPulse(vector(Vec3.atBottomCenterOf(pos).add(0,1.35,0).subtract(effect.origin)),now));
        }
        cue(effect,SoundEvents.AMETHYST_BLOCK_CHIME,.23f,.85f);
    }
    private static void receiveAcceptance(GeasRitualVisuals.Accepted receipt) {
        ClientLevel current=Minecraft.getInstance().level;changeLevel(current);
        if(current==null || !current.dimension().location().equals(receipt.dimension()) || !current.hasChunkAt(receipt.matrix()))return;
        long now=current.getGameTime();
        if(FINISHED.containsKey(receipt.session()) || ACCEPTED_SEEN.containsKey(receipt.session())
                || now-receipt.stamp()>=RecipientAcceptanceEffects.LIFETIME || receipt.stamp()>now+20)return;
        if(ACCEPTED.size()>=MAX_TRACKED)ACCEPTED.remove(ACCEPTED.keySet().iterator().next());
        ACCEPTED.put(receipt.session(),receipt);ACCEPTED_SEEN.put(receipt.session(),now);
        while(ACCEPTED_SEEN.size()>64)ACCEPTED_SEEN.remove(ACCEPTED_SEEN.keySet().iterator().next());
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        ClientLevel current=Minecraft.getInstance().level;changeLevel(current);
        if(current==null || Minecraft.getInstance().isPaused())return;
        long now=current.getGameTime();
        EFFECTS.values().removeIf(e->{
            e.pulses.removeIf(p->now<p.tick() || now-p.tick()>=RitualOfferingEffects.LIFETIME);
            boolean expired=!current.hasChunkAt(e.state.matrix()) || !current.getBlockState(e.state.matrix()).hasBlockEntity()
                || now<e.received || now-e.received>=GeasRitualVisuals.LEASE_TICKS
                || (GeasRitualVisuals.terminal(e.state.phase()) && e.age(now)>=
                    (e.state.phase()==Phase.COMPLETE?RestraintGeometry.COMPLETE_TICKS:RestraintGeometry.CANCEL_TICKS));
            if(expired)dispose(e);
            else e.lights.tick(now,now-e.born,e.layout,!GeasRitualVisuals.terminal(e.state.phase()));
            return expired;
        });
        FINISHED.values().removeIf(stamp->now<stamp || now-stamp>200);
        ACCEPTED.values().removeIf(p->now-p.stamp()>=RecipientAcceptanceEffects.LIFETIME || now<p.stamp()-20
                || !current.hasChunkAt(p.matrix()) || !current.getBlockState(p.matrix()).hasBlockEntity());
        ACCEPTED_SEEN.values().removeIf(stamp->now<stamp || now-stamp>200);
    }

    @SubscribeEvent public static void render(RenderLevelStageEvent event){
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_LEVEL)return;
        ClientLevel current=Minecraft.getInstance().level;changeLevel(current);
        if(current==null || EFFECTS.isEmpty() && ACCEPTED.isEmpty())return;
        float partial=event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double now=current.getGameTime()+partial;
        Vec3 camera=event.getCamera().getPosition();
        V right=vector(new Vector3f(1,0,0).rotate(event.getCamera().rotation()));
        V up=vector(new Vector3f(0,1,0).rotate(event.getCamera().rotation()));
        VISIBLE.clear();
        for(Effect effect:EFFECTS.values()) {
            if(effect.origin.distanceToSqr(camera)>48*48 || !current.hasChunkAt(effect.state.matrix()))continue;
            if(!event.getFrustum().isVisible(new AABB(effect.origin.add(-11,-11,-11),effect.origin.add(11,6,11))))continue;
            V recipient=null;
            Player player=current.getPlayerByUUID(effect.state.recipient());
            // Foot position uses the exact ring footprint, including marked edge blocks, at the interpolated frame.
            if(player!=null && player.isAlive() && !player.isSpectator()) {
                Vec3 feet=player.getPosition(partial);
                if(effect.footprint.bounds().contains(feet) && effect.footprint.containsHorizontal(feet)) {
                    Vec3 chest=feet.add(0,player.getBbHeight()*.65,0);
                    chest=chest.add(camera.subtract(chest).normalize().scale(player.getBbWidth()*.6));
                    recipient=vector(chest.subtract(effect.origin));
                }
            }
            effect.prepared=new Frame(effect.state.phase(),effect.age(now),now-effect.born,right,up,
                    vector(camera.subtract(effect.origin)),recipient,effect.exitReveal,effect.layout,
                    effect.pulses.stream().map(p->new OfferingPulse(p.source(),now-p.tick())).toList(),effect.state.glyph());
            VISIBLE.add(effect);
        }
        if(VISIBLE.isEmpty()){renderAcceptance(event,camera,right,up,now);return;}
        VISIBLE.sort(Comparator.comparingDouble(e->e.origin.distanceToSqr(camera)));
        int count=Math.min(MAX_VISIBLE,VISIBLE.size());
        // Ordinary transparent body first. Flush each far-to-near session, even across glyph textures.
        DIRECT.render(event,buffers->{
            for(int i=count-1;i>=0;i--) {
                Effect effect=VISIBLE.get(i);RenderType type=GeasRenderTypes.shade(effect.state.glyph());
                RestraintGeometry.emit(sink(buffers.getBuffer(type),effect.origin.subtract(camera)),effect.prepared,Material.CREST_HALO,now);
                buffers.endBatch(type);
            }
        });
        PASS.render(event,buffers->{
            for(DrawGroup group:GROUPS) {
                VertexConsumer vertices=null;
                for(int i=count-1;i>=0;i--) {
                    Effect effect=VISIBLE.get(i);
                    if(group.glyph()!=null && effect.state.glyph()!=group.glyph())continue;
                    if(vertices==null)vertices=buffers.getBuffer(group.type());
                    RestraintGeometry.emit(sink(vertices,effect.origin.subtract(camera)),effect.prepared,group.material(),now);
                }
                if(vertices!=null)buffers.endBatch(group.type());
            }
        });
        // Shared trajectory records feed long tail, short tail and head; glyph light is submitted last.
        DIRECT.render(event,buffers->{
            for(DrawGroup part:LIGHT_PARTS) {
                VertexConsumer vertices=buffers.getBuffer(part.type());
                for(int i=count-1;i>=0;i--) {
                    Effect effect=VISIBLE.get(i);
                    Sink sink=sink(vertices,effect.origin.subtract(camera));
                    effect.lights.emit(sink,effect.prepared,now,part.material());
                    dev.senluo.witchcraft.ritual.visual.RitualRingEffects.emit(sink,effect.prepared,part.material());
                }
                buffers.endBatch(part.type());
            }
            for(GeasGlyph glyph:GeasGlyph.values()) {
                RenderType type=GeasRenderTypes.light(glyph);VertexConsumer vertices=null;
                for(int i=count-1;i>=0;i--) {
                    Effect effect=VISIBLE.get(i);if(effect.state.glyph()!=glyph)continue;
                    if(vertices==null)vertices=buffers.getBuffer(type);
                    RestraintGeometry.emit(sink(vertices,effect.origin.subtract(camera)),effect.prepared,Material.CREST,now);
                }
                if(vertices!=null)buffers.endBatch(type);
            }
        });
        renderAcceptance(event,camera,right,up,now);
        VISIBLE.clear();
    }

    private static void renderAcceptance(RenderLevelStageEvent event,Vec3 camera,V right,V up,double now) {
        VISIBLE_FLASHES.clear();
        for(var receipt:ACCEPTED.values()) {
            Vec3 origin=Vec3.atCenterOf(receipt.matrix());
            if(origin.distanceToSqr(camera)>48*48 || !event.getFrustum().isVisible(new AABB(origin.add(-11,-14,-11),origin.add(11,6,11))))continue;
            VISIBLE_FLASHES.add(receipt);
        }
        if(VISIBLE_FLASHES.isEmpty())return;
        VISIBLE_FLASHES.sort(Comparator.comparingDouble(p->Vec3.atCenterOf(p.matrix()).distanceToSqr(camera)));
        int count=Math.min(MAX_VISIBLE,VISIBLE_FLASHES.size());
        DIRECT.render(event,buffers->{
            for(Material material:List.of(Material.RIBBON,Material.GLOW)) {
                RenderType type=material==Material.RIBBON?GeasRenderTypes.ACCEPT_RAY:GeasRenderTypes.ORBIT_HEAD;
                VertexConsumer vertices=buffers.getBuffer(type);
                for(int i=0;i<count;i++) {
                    var receipt=VISIBLE_FLASHES.get(i);Vec3 origin=Vec3.atCenterOf(receipt.matrix());
                    var view=new RecipientAcceptanceEffects.View(now-receipt.stamp(),new V(0,1.9,0),
                            vector(receipt.position().subtract(origin)),right,up,1);
                    RecipientAcceptanceEffects.emit(sink(vertices,origin.subtract(camera)),view,material);
                }
                buffers.endBatch(type);
            }
        });
        VISIBLE_FLASHES.clear();
    }

    private static Sink sink(VertexConsumer target,Vec3 offset) {
        return (m,a,b,c,d,u0,v0,u1,v1,r0,g0,b0,alpha0,r1,g1,b1,alpha1)->{
            vertex(target,a,offset,u0,v0,r0,g0,b0,alpha0);vertex(target,b,offset,u1,v0,r0,g0,b0,alpha0);
            vertex(target,c,offset,u1,v1,r1,g1,b1,alpha1);vertex(target,d,offset,u0,v1,r1,g1,b1,alpha1);
        };
    }

    private static V vector(Vec3 v){return new V(v.x,v.y,v.z);}
    private static V vector(Vector3f v){return new V(v.x,v.y,v.z);}
    private static void vertex(VertexConsumer out,V p,Vec3 offset,float u,float v,float r,float g,float b,float a){
        out.addVertex((float)(p.x()+offset.x),(float)(p.y()+offset.y),(float)(p.z()+offset.z))
                .setColor(r,g,b,a).setUv(u,v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(0,1,0);
    }
    private static final class EffectMaterial extends RenderType {
        private EffectMaterial(){super("geas_factory",DefaultVertexFormat.NEW_ENTITY,VertexFormat.Mode.QUADS,1536,false,true,()->{},()->{});}
        static RenderType make(String name,boolean blur){
            return create("geas_"+name+(blur?"_soft":"_core"),DefaultVertexFormat.NEW_ENTITY,VertexFormat.Mode.QUADS,1536,false,true,
                    CompositeState.builder().setShaderState(RENDERTYPE_EYES_SHADER)
                    .setTextureState(new TextureStateShard(ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID,
                            "textures/effect/restraint/"+name+".png"),blur,false))
                    .setTransparencyState(LIGHTNING_TRANSPARENCY).setCullState(NO_CULL)
                    .setDepthTestState(LEQUAL_DEPTH_TEST).setWriteMaskState(COLOR_WRITE).createCompositeState(false));
        }
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){changeLevel(null);}
    private static void changeLevel(ClientLevel current){if(level!=current){clear();level=current;}}
    private static void clear(){EFFECTS.values().forEach(GeasEffects::dispose);EFFECTS.clear();FINISHED.clear();VISIBLE.clear();
        ACCEPTED.clear();ACCEPTED_SEEN.clear();VISIBLE_FLASHES.clear();PASS.release();DIRECT.release();}
    private GeasEffects(){}
}
