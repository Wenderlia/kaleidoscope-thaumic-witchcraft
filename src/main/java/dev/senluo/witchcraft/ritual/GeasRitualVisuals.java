package dev.senluo.witchcraft.ritual;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.ritual.visual.RestraintGeometry.Phase;
import dev.senluo.witchcraft.ritual.visual.GeasGlyph;
import java.util.Objects;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Supplier;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Optional post-commit presentation port. Does not start a ritual, consume a fetich, or grant a binding.
 * The ritual owner holds one Emitter per attempt and supplies its authoritative stage age.
 * Unloading stops renewals; visuals expire without offline progress or replayed success. */
public final class GeasRitualVisuals {
    public static final int RENEW_TICKS=10, LEASE_TICKS=40;
    private static Consumer<State> receiver=ignored->{};
    private static Consumer<Burst> burstReceiver=ignored->{};
    private static Consumer<Accepted> acceptedReceiver=ignored->{};
    private static final UUID NO_RECIPIENT=new UUID(0,0);

    public record State(UUID session,ResourceLocation dimension,BlockPos matrix,BlockPos plane,int ring,
            UUID recipient,GeasGlyph glyph,Phase phase,int phaseTicks,long stamp,List<BlockPos> offerings) implements CustomPacketPayload {
        public State {
            Objects.requireNonNull(session); Objects.requireNonNull(dimension); Objects.requireNonNull(recipient);
            Objects.requireNonNull(phase); Objects.requireNonNull(glyph);
            matrix=Objects.requireNonNull(matrix).immutable(); plane=Objects.requireNonNull(plane).immutable();
            Objects.requireNonNull(offerings);
            offerings=offerings.stream().map(BlockPos::immutable).distinct().toList();
            if(offerings.size()>8)throw new IllegalArgumentException("Too many offering anchors");
            for(BlockPos pos:offerings)if(Math.abs((long)pos.getX()-matrix.getX())>8
                    || Math.abs((long)pos.getZ()-matrix.getZ())>8
                    || pos.getY()>=matrix.getY() || (long)matrix.getY()-pos.getY()>10)
                throw new IllegalArgumentException("Invalid offering anchor");
            if(ring<1 || ring>3 || phaseTicks<0 || phaseTicks>1_000_000 || stamp<0
                    || matrix.getX()!=plane.getX() || matrix.getZ()!=plane.getZ()
                    || Math.abs((long)matrix.getY()-plane.getY())>RitualPlane.HEIGHT_RADIUS)
                throw new IllegalArgumentException("Invalid geas visual state");
        }
        public static final Type<State> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(
                KaleidoscopeWitchcraft.MOD_ID,"geas_visual"));
        public static final StreamCodec<RegistryFriendlyByteBuf,State> CODEC=StreamCodec.of((buf,s)->{
            buf.writeUUID(s.session); buf.writeResourceLocation(s.dimension); buf.writeBlockPos(s.matrix);
            buf.writeBlockPos(s.plane); buf.writeByte(s.ring); buf.writeUUID(s.recipient);
            buf.writeEnum(s.glyph); buf.writeEnum(s.phase); buf.writeVarInt(s.phaseTicks); buf.writeVarLong(s.stamp);
            buf.writeVarInt(s.offerings.size());for(BlockPos pos:s.offerings)buf.writeBlockPos(pos);
        },buf->new State(buf.readUUID(),buf.readResourceLocation(),buf.readBlockPos(),buf.readBlockPos(),
                buf.readUnsignedByte(),buf.readUUID(),buf.readEnum(GeasGlyph.class),buf.readEnum(Phase.class),buf.readVarInt(),buf.readVarLong(),readOfferings(buf)));
        private static List<BlockPos> readOfferings(RegistryFriendlyByteBuf buf) {
            int count=buf.readVarInt();if(count<0 || count>8)throw new IllegalArgumentException("Too many offering anchors");
            var result=new ArrayList<BlockPos>(count);for(int i=0;i<count;i++)result.add(buf.readBlockPos());
            return result;
        }
        @Override public Type<State> type(){return TYPE;}
    }

    public static final class Emitter {
        private final UUID attempt;
        private final GeasGlyph glyph;
        private long sent=Long.MIN_VALUE;
        private Phase previous;
        private boolean ended;
        private long sequence;
        public Emitter(UUID attempt,GeasGlyph glyph){
            this.attempt=Objects.requireNonNull(attempt);this.glyph=Objects.requireNonNull(glyph);
        }

        /** The host confirms a running attempt and the designated player inside its footprint. */
        public void accepted(ServerLevel level,BlockPos matrix,UUID target,net.minecraft.world.phys.Vec3 position) {
            if(ended)return;
            try {
                PacketDistributor.sendToPlayersNear(level,null,matrix.getX()+.5,matrix.getY()+.5,matrix.getZ()+.5,48,
                        new Accepted(attempt,level.dimension().location(),matrix,target,level.getGameTime(),position));
            } catch(RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(KaleidoscopeWitchcraft.MOD_ID)
                        .warn("Unable to present geas target acceptance for {}",attempt,failure);
            }
        }

        /** One bounded batch after actual component commits, never inferred from inventory scanning. */
        public void absorbed(ServerLevel level,BlockPos matrix,List<BlockPos> sources) {
            if(ended || sources.isEmpty() || !level.hasChunkAt(matrix))return;
            try {
                Burst burst=new Burst(attempt,level.dimension().location(),matrix,++sequence,level.getGameTime(),sources);
                PacketDistributor.sendToPlayersNear(level,null,matrix.getX()+.5,matrix.getY()+.5,matrix.getZ()+.5,48,burst);
            } catch(RuntimeException failure) {
                ended=true;
                org.slf4j.LoggerFactory.getLogger(KaleidoscopeWitchcraft.MOD_ID)
                        .warn("Geas absorption presentation disabled for attempt {}",attempt,failure);
            }
        }

        /** Call after the stage's authoritative work. A held/paused phase should stop renewals.
         * WAITING/FORMING/BINDING may renew each tick; transmission remains at most twice per second.
         * COMPLETE/CANCELLED are terminal, sent once. Never use COMPLETE to grant gameplay state. */
        public void publish(ServerLevel level,BlockPos matrix,RitualPlane plane,UUID designated,
                Phase phase,int phaseTicks) {
            publish(level,matrix,plane,designated,phase,phaseTicks,List::of);
        }
        public void publish(ServerLevel level,BlockPos matrix,RitualPlane plane,UUID designated,
                Phase phase,int phaseTicks,Supplier<List<BlockPos>> offerings) {
            if(ended || !level.hasChunkAt(matrix) || plane.outermostRing()==0)return;
            long now=level.getGameTime();
            if(previous==phase && now>=sent && now-sent<RENEW_TICKS)return;
            // Only optional network presentation is isolated from already-committed ritual work.
            try {
                State state=new State(attempt,level.dimension().location(),matrix,plane.anchor(),plane.outermostRing(),
                        designated==null?NO_RECIPIENT:designated,glyph,phase,phaseTicks,now,offerings.get());
                sent=now; previous=phase; ended=terminal(phase);
                PacketDistributor.sendToPlayersNear(level,null,matrix.getX()+.5,matrix.getY()+.5,matrix.getZ()+.5,48,state);
            } catch(RuntimeException failure) {
                ended=true;
                org.slf4j.LoggerFactory.getLogger(KaleidoscopeWitchcraft.MOD_ID)
                        .warn("Geas presentation disabled for attempt {}",attempt,failure);
            }
        }
    }
    public record Burst(UUID session,ResourceLocation dimension,BlockPos matrix,long sequence,long stamp,
            List<BlockPos> sources) implements CustomPacketPayload {
        public Burst {
            Objects.requireNonNull(session);Objects.requireNonNull(dimension);matrix=matrix.immutable();
            sources=sources.stream().map(BlockPos::immutable).toList();
            if(sequence<1 || stamp<0 || sources.isEmpty() || sources.size()>8)throw new IllegalArgumentException("Invalid absorption batch");
            for(BlockPos p:sources)if(Math.abs((long)p.getX()-matrix.getX())>8 || Math.abs((long)p.getZ()-matrix.getZ())>8
                    || p.getY()>=matrix.getY() || (long)matrix.getY()-p.getY()>10)throw new IllegalArgumentException("Invalid absorption source");
        }
        public static final Type<Burst> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID,"geas_absorbed"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Burst> CODEC=StreamCodec.of((b,s)->{
            b.writeUUID(s.session);b.writeResourceLocation(s.dimension);b.writeBlockPos(s.matrix);
            b.writeVarLong(s.sequence);b.writeVarLong(s.stamp);b.writeVarInt(s.sources.size());
            for(BlockPos p:s.sources)b.writeBlockPos(p);
        },b->new Burst(b.readUUID(),b.readResourceLocation(),b.readBlockPos(),b.readVarLong(),b.readVarLong(),State.readOfferings(b)));
        @Override public Type<Burst> type(){return TYPE;}
    }
    public static boolean terminal(Phase phase){return phase==Phase.COMPLETE || phase==Phase.CANCELLED;}
    public record Accepted(UUID session,ResourceLocation dimension,BlockPos matrix,UUID recipient,long stamp,
            net.minecraft.world.phys.Vec3 position) implements CustomPacketPayload {
        public Accepted {
            Objects.requireNonNull(session);Objects.requireNonNull(dimension);Objects.requireNonNull(recipient);
            matrix=matrix.immutable();Objects.requireNonNull(position);
            if(stamp<0 || !Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)
                    || Math.abs(position.x-matrix.getX())>11 || Math.abs(position.z-matrix.getZ())>11
                    || Math.abs(position.y-matrix.getY())>14)throw new IllegalArgumentException("Invalid acceptance position");
        }
        public static final Type<Accepted> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID,"geas_target_accepted"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Accepted> CODEC=StreamCodec.of((b,s)->{
            b.writeUUID(s.session);b.writeResourceLocation(s.dimension);b.writeBlockPos(s.matrix);b.writeUUID(s.recipient);
            b.writeVarLong(s.stamp);b.writeDouble(s.position.x);b.writeDouble(s.position.y);b.writeDouble(s.position.z);
        },b->new Accepted(b.readUUID(),b.readResourceLocation(),b.readBlockPos(),b.readUUID(),b.readVarLong(),
                new net.minecraft.world.phys.Vec3(b.readDouble(),b.readDouble(),b.readDouble())));
        @Override public Type<Accepted> type(){return TYPE;}
    }
    public static void install(Consumer<State> listener){receiver=Objects.requireNonNull(listener);}
    public static void installBursts(Consumer<Burst> listener){burstReceiver=Objects.requireNonNull(listener);}
    public static void installAccepted(Consumer<Accepted> listener){acceptedReceiver=Objects.requireNonNull(listener);}
    public static void register(RegisterPayloadHandlersEvent event){
        event.registrar(KaleidoscopeWitchcraft.NETWORK_VERSION).playToClient(State.TYPE,State.CODEC,
                (packet,context)->context.enqueueWork(()->receiver.accept(packet)));
        event.registrar(KaleidoscopeWitchcraft.NETWORK_VERSION).playToClient(Burst.TYPE,Burst.CODEC,
                (packet,context)->context.enqueueWork(()->burstReceiver.accept(packet)));
        event.registrar(KaleidoscopeWitchcraft.NETWORK_VERSION).playToClient(Accepted.TYPE,Accepted.CODEC,
                (packet,context)->context.enqueueWork(()->acceptedReceiver.accept(packet)));
    }
    private GeasRitualVisuals(){}
}
