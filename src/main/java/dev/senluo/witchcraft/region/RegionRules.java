package dev.senluo.witchcraft.region;

import java.util.*;
import java.util.function.BiPredicate;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.level.*;

/** Platform lifetime and condition adapters. Rules are local overlays, never global gamerule mutations. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID)
public final class RegionRules {
    public static final ResourceLocation NO_MOB_BLOCK_CHANGES = id("no_mob_block_changes");
    public static final ResourceLocation NO_EXPLOSION_BLOCK_CHANGES = id("no_explosion_block_changes");
    private static final Map<ServerLevel, RegionRuleStore> LOADED = new WeakHashMap<>();
    private static final Map<ResourceLocation, BiPredicate<ServerLevel, RegionRuleStore.Condition>> CONDITIONS = new HashMap<>();
    public static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID, path); }
    public static void registerCondition(ResourceLocation kind, BiPredicate<ServerLevel, RegionRuleStore.Condition> check) {
        if (CONDITIONS.putIfAbsent(kind, Objects.requireNonNull(check)) != null) throw new IllegalStateException("Duplicate region condition");
    }
    public static RegionRuleStore store(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Region rules belong to server thread");
        RegionRuleStore store = LOADED.get(level);
        if (store == null) {
            store = level.getDataStorage().computeIfAbsent(RegionRuleStore.FACTORY, "witchcraft_region_rules");
            LOADED.put(level, store);
        }
        return store;
    }
    public static boolean applies(ServerLevel level, BlockPos pos, ResourceLocation rule) {
        return store(level).applies(rule, pos, c -> {
            var check = CONDITIONS.get(c.kind());
            return check != null && check.test(level, c);
        });
    }
    public static void blockChanged(ServerLevel level, BlockPos pos) {
        RegionRuleStore store = LOADED.get(level);
        if (store != null) store.blockChanged(pos);
    }
    /** Attachment-only chalk edits and chunk lifetime changes use the coarse dependency index. */
    public static void chunkChanged(ServerLevel level, long chunk) {
        RegionRuleStore store = LOADED.get(level);
        if (store != null) store.chunkChanged(chunk);
    }
    @SubscribeEvent public static void loaded(LevelEvent.Load event) { if (event.getLevel() instanceof ServerLevel level) store(level); }
    @SubscribeEvent public static void unloaded(LevelEvent.Unload event) { if (event.getLevel() instanceof ServerLevel level) LOADED.remove(level); }
    @SubscribeEvent public static void loaded(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) chunkChanged(level, event.getChunk().getPos().toLong());
    }
    @SubscribeEvent public static void unloaded(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) chunkChanged(level, event.getChunk().getPos().toLong());
    }
    @SubscribeEvent public static void tags(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) LOADED.values().forEach(RegionRuleStore::rulesChanged);
    }
    private RegionRules() {}
}
