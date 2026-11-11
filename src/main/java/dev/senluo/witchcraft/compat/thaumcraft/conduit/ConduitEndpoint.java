package dev.senluo.witchcraft.compat.thaumcraft.conduit;

import dev.tc4port.thaumcraft.api.aspect.*;
import dev.senluo.witchcraft.compat.thaumcraft.EssentiaStores;
import dev.tc4port.thaumcraft.api.essentia.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** One synchronous capability scope. Never retained in the topology cache. */
final class ConduitEndpoint {
    record Port(BlockPos pipe, Direction direction) {
        Port { pipe = pipe.immutable(); }
        BlockPos position() { return pipe.relative(direction); }
        Direction face() { return direction.getOpposite(); }
    }
    final ServerLevel level;
    final Port port;
    final EssentiaTransport transport;
    private final BlockEntity identity;
    private final BlockState state;
    private ConduitEndpoint(ServerLevel level, Port port, EssentiaTransport transport) {
        this.level = level; this.port = port; this.transport = transport;
        identity = level.getBlockEntity(port.position()); state = level.getBlockState(port.position());
    }
    static ConduitEndpoint resolve(ServerLevel level, Port port) {
        if (!level.hasChunkAt(port.position()) || !level.shouldTickBlocksAt(port.position())) return null;
        var transport = EssentiaApi.transport(level, port.position(), port.face());
        if (transport == null || transport instanceof ConduitEntity) return null;
        transport = transport.transferView();
        if (transport == null || !transport.isConnectable(port.face())) return null;
        return new ConduitEndpoint(level, port, transport);
    }
    boolean current() {
        return level.hasChunkAt(port.position()) && level.shouldTickBlocksAt(port.position())
                && level.getBlockEntity(port.position()) == identity && level.getBlockState(port.position()) == state;
    }
    int pressure() { return Math.max(0, transport.suctionAmount(port.face())); }
    int minimum() { return Math.max(0, transport.minimumSuction()); }
    boolean input() { return transport.canInputFrom(port.face()); }
    boolean output() { return transport.canOutputTo(port.face()); }
    List<AspectId> aspects() {
        var types = new LinkedHashSet<AspectId>();
        var storage = EssentiaStores.at(level, port.position(), port.face());
        if (storage != null) types.addAll(storage.essentiaContents(port.face()).amounts().keySet());
        AspectId selected = transport.extractableAspect(port.face());
        if (selected != null) types.add(selected);
        selected = transport.essentiaType(port.face());
        if (selected != null) types.add(selected);
        types.removeIf(id -> AspectApi.registry().get(id).isEmpty());
        return List.copyOf(types);
    }
    boolean stocked(AspectId aspect) { return transport.availableEssentia(aspect, port.face()) > 0; }
    int receive(AspectId aspect, int request, EssentiaTransferMode mode) {
        if (!current() || !input()) return 0;
        AspectId filter = transport.suctionType(port.face());
        if (filter != null && !filter.equals(aspect)) return 0;
        // A native void jar's add method may accept past capacity; a pipe must not delete that stock.
        request = EssentiaStores.limit(level, port.position(), EssentiaStores.at(level, port.position(), port.face()), port.face(), aspect, request);
        if (request <= 0) return 0;
        int result = checked(transport.addEssentia(aspect, request, port.face(), mode), request);
        // Native buffers deliberately accept only a single unit per call.
        if (result == 0 && !mode.executes() && request > 1)
            result = checked(transport.addEssentia(aspect, 1, port.face(), mode), 1);
        return result;
    }
    int extract(AspectId aspect, int request, EssentiaTransferMode mode) {
        if (!current() || !output()) return 0;
        request = Math.min(request, Math.max(0, transport.availableEssentia(aspect, port.face())));
        if (request <= 0) return 0;
        int result = checked(transport.takeEssentia(aspect, request, port.face(), mode), request);
        if (result == 0 && !mode.executes() && request > 1)
            result = checked(transport.takeEssentia(aspect, 1, port.face(), mode), 1);
        return result;
    }
    private static int checked(int actual, int requested) {
        if (actual < 0 || actual > requested) throw new IllegalStateException("Endpoint returned invalid quantity: " + actual + "/" + requested);
        return actual;
    }
}
