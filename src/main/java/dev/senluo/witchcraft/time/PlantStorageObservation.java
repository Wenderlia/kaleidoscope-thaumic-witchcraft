package dev.senluo.witchcraft.time;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** A targeted, read-only observation; never broadcasts tissue inventories or alters persistence. */
public final class PlantStorageObservation {
    private static Consumer<Snapshot> receiver = snapshot -> {};

    private PlantStorageObservation() {}

    public record Snapshot(ResourceLocation dimension, BlockPos pos, long stored, long capacity)
            implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                KaleidoscopeWitchcraft.MOD_ID, "plant_storage"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.of(
                (buffer, value) -> {
                    buffer.writeResourceLocation(value.dimension());
                    buffer.writeBlockPos(value.pos());
                    buffer.writeLong(value.stored());
                    buffer.writeLong(value.capacity());
                }, buffer -> new Snapshot(buffer.readResourceLocation(), buffer.readBlockPos(),
                        buffer.readLong(), buffer.readLong()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void install(Consumer<Snapshot> listener) {
        receiver = java.util.Objects.requireNonNull(listener);
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(KaleidoscopeWitchcraft.NETWORK_VERSION).playToClient(Snapshot.TYPE, Snapshot.CODEC,
                (snapshot, context) -> context.enqueueWork(() -> receiver.accept(snapshot)));
    }

    public static boolean holdingClock(net.minecraft.world.entity.player.Player player) {
        return player.getMainHandItem().is(Items.CLOCK) || player.getOffhandItem().is(Items.CLOCK);
    }

    public static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.tickCount % 5 != 0
                || !holdingClock(player) || !player.isAlive() || player.isSpectator()) return;
        var level = player.serverLevel();
        // An extended-reach mod must not turn observation into an unbounded raycast or chunk load.
        double range = Math.min(32.0, player.blockInteractionRange());
        if (!Double.isFinite(range) || range <= 0) return;
        BlockGetter loaded = new BlockGetter() {
            @Override public BlockEntity getBlockEntity(BlockPos pos) {
                return level.hasChunkAt(pos) ? level.getBlockEntity(pos) : null;
            }
            @Override public BlockState getBlockState(BlockPos pos) {
                return level.hasChunkAt(pos) ? level.getBlockState(pos) : Blocks.AIR.defaultBlockState();
            }
            @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            @Override public int getHeight() { return level.getHeight(); }
            @Override public int getMinBuildHeight() { return level.getMinBuildHeight(); }
        };
        var start = player.getEyePosition();
        var hit = loaded.clip(new ClipContext(start, start.add(player.getLookAngle().scale(range)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK
                || !(loaded.getBlockEntity(hit.getBlockPos()) instanceof LivingEntity plant)
                || !plant.tissue.supported()) return;
        var storage = TimeStorage.of(plant.tissue);
        PacketDistributor.sendToPlayer(player, new Snapshot(level.dimension().location(),
                plant.getBlockPos(), storage.quantity(), storage.capacity()));
    }
}
