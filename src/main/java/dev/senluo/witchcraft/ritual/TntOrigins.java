package dev.senluo.witchcraft.ritual;

import com.mojang.serialization.Codec;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** TNT has no block entity. Store its placer with the owning chunk, never in a global position scan. */
public final class TntOrigins {
    private static final DeferredRegister<AttachmentType<?>> TYPES = DeferredRegister.create(
            NeoForgeRegistries.ATTACHMENT_TYPES, KaleidoscopeWitchcraft.MOD_ID);
    private static final Supplier<AttachmentType<Map<String, UUID>>> OWNERS = TYPES.register("placed_explosive_owners",
            () -> AttachmentType.<Map<String, UUID>>builder(() -> new HashMap<>())
                    .serialize(Codec.unboundedMap(Codec.STRING, UUIDUtil.CODEC)
                            .xmap(HashMap::new, Map::copyOf)).build());
    private record Context(Level level, Map<BlockPos, UUID> owners) {}
    private static final ThreadLocal<Context> CONTEXT = new ThreadLocal<>();
    public static void register(IEventBus bus) { TYPES.register(bus); }
    public static UUID owner(Level level, BlockPos pos) {
        Context context = CONTEXT.get();
        if (context != null && context.level == level && context.owners.containsKey(pos)) return context.owners.get(pos);
        if (!(level instanceof ServerLevel) || !level.hasChunkAt(pos)) return null;
        var chunk = level.getChunkAt(pos);
        return chunk.hasData(OWNERS.get()) ? chunk.getData(OWNERS.get()).get(Long.toString(pos.asLong())) : null;
    }
    public static void put(Level level, BlockPos pos, UUID owner) {
        if (!(level instanceof ServerLevel) || !level.hasChunkAt(pos)) return;
        LevelChunk chunk = level.getChunkAt(pos);
        if (owner == null && !chunk.hasData(OWNERS.get())) return;
        var data = chunk.getData(OWNERS.get());
        String key = Long.toString(pos.asLong());
        UUID before = owner == null ? data.remove(key) : data.put(key, owner);
        if (!Objects.equals(before, owner)) chunk.setUnsaved(true);
        if (data.isEmpty()) chunk.removeData(OWNERS.get());
    }
    public static boolean carries(BlockState state) { return state.is(Blocks.TNT) || state.is(Blocks.MOVING_PISTON); }
    public static <T> T during(Level level, Map<BlockPos, UUID> owners, Supplier<T> body) {
        Context before = CONTEXT.get(); CONTEXT.set(new Context(level, owners));
        try { return body.get(); }
        finally { if (before == null) CONTEXT.remove(); else CONTEXT.set(before); }
    }
    public static void primed(Entity tnt, BlockPos origin, Entity igniter) {
        if (!(tnt.level() instanceof ServerLevel level)) return;
        UUID placer = owner(level, origin);
        boolean constrained = WorldRestraint.bound(igniter) || placer != null
                && GeasBindings.has(level.getServer(), placer, GeasBindings.RESTRAINT);
        WorldRestraint.capture(tnt, new WorldRestraint.Action(constrained));
    }
    private TntOrigins() {}
}
