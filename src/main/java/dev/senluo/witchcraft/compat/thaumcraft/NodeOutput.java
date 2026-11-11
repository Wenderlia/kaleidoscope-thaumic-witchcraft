package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.tc4port.thaumcraft.api.ThaumcraftContent;
import dev.tc4port.thaumcraft.api.aspect.AspectApi;
import dev.tc4port.thaumcraft.api.aspect.VisAction;
import dev.tc4port.thaumcraft.api.aspect.VisChannel;
import dev.tc4port.thaumcraft.api.node.NodeApi;
import dev.tc4port.thaumcraft.api.node.VisCapabilities;
import dev.tc4port.thaumcraft.api.node.VisSource;
import java.math.BigInteger;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** The native source owns the latest tick; the attachment holds only superseded output. */
public final class NodeOutput implements VisSource {
    private static final DeferredRegister<AttachmentType<?>> DATA = DeferredRegister.create(
            NeoForgeRegistries.ATTACHMENT_TYPES, KaleidoscopeWitchcraft.MOD_ID);
    private static final java.util.function.Supplier<AttachmentType<NodeOutput>> OUTPUT = DATA.register(
            "energized_output", () -> AttachmentType.<NodeOutput>builder(holder -> new NodeOutput((BlockEntity) holder)).build());
    private final BlockEntity node;
    private final VisSource nativeSource;
    private Map<VisChannel, BigInteger> reserve = Map.of();
    private long nodeGeneration, aspectGeneration;

    private NodeOutput(BlockEntity node) {
        this.node = node;
        this.nativeSource = (VisSource) node;
    }

    public static void register(IEventBus bus) {
        DATA.register(bus);
        // NeoForge selects the first non-null provider. TC registers its wireless source at NORMAL.
        bus.addListener(EventPriority.HIGH, (RegisterCapabilitiesEvent event) ->
                event.registerBlock(VisCapabilities.SOURCE, (level, pos, state, entity, side) ->
                        side == null && entity instanceof VisSource ? entity.getData(OUTPUT) : null,
                        ThaumcraftContent.block("energized_aura_node")));
    }

    static void clear(BlockEntity node) {
        if (node.hasData(OUTPUT)) node.getData(OUTPUT).reserve = Map.of();
    }

    static void publish(BlockEntity node, EnumMap<VisChannel, BigInteger> extra) {
        NodeOutput output = node.getData(OUTPUT);
        output.reserve = extra;
        output.nodeGeneration = NodeApi.registry().generation();
        output.aspectGeneration = AspectApi.registry().generation();
    }

    @Override public boolean canSupplyVis() { return nativeSource.canSupplyVis(); }
    @Override public BlockPos networkEndpoint() { return nativeSource.networkEndpoint(); }
    @Override public Object simulationIdentity() { return nativeSource.simulationIdentity(); }

    @Override public int takeVis(VisChannel channel, int amount, VisAction action, int reserved) {
        if (reserve.isEmpty()) return nativeSource.takeVis(channel, amount, action, reserved);
        if (channel == null || action == null || amount <= 0 || reserved < 0 || !canSupplyVis()
                || !(node.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) return 0;
        BigInteger extra = reserve.get(channel);
        if (extra == null || nodeGeneration != NodeApi.registry().generation()
                || aspectGeneration != AspectApi.registry().generation())
            return nativeSource.takeVis(channel, amount, action, reserved);
        if (action == VisAction.SIMULATE) {
            int nativeBalance = nativeSource.takeVis(channel, Integer.MAX_VALUE, action, 0);
            return extra.add(BigInteger.valueOf((long) nativeBalance - reserved))
                    .max(BigInteger.ZERO).min(BigInteger.valueOf(amount)).intValueExact();
        }
        int nativePaid = nativeSource.takeVis(channel, amount, action, 0);
        int additional = extra.min(BigInteger.valueOf(amount - nativePaid)).intValueExact();
        if (additional > 0) {
            BigInteger left = extra.subtract(BigInteger.valueOf(additional));
            if (left.signum() == 0) reserve.remove(channel);
            else reserve.put(channel, left);
        }
        return nativePaid + additional;
    }
}
