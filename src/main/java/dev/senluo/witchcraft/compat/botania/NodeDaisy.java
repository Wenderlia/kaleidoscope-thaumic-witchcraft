package dev.senluo.witchcraft.compat.botania;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.tc4port.thaumcraft.api.aspect.AspectId;
import dev.tc4port.thaumcraft.api.node.NodeApi;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import vazkii.botania.api.recipe.PureDaisyRecipe;
import vazkii.botania.common.crafting.BotaniaRecipeTypes;
import vazkii.botania.common.crafting.BlockStateRecipe;
import vazkii.botania.client.fx.WispParticleData;

/** Optional TC/Botania boundary. Native recipe matching, output selection, property copying,
 * pre/success functions and block-change events stay with Botania. No fake flower or forced chunks. */
public final class NodeDaisy {
    private static final AspectId PLANT = AspectId.parse("herba"), MAGIC = AspectId.parse("praecantatio");
    private static final int WIDTH = 7, CELLS = WIDTH * WIDTH * WIDTH, CENTER = CELLS / 2;
    // One native Pure Daisy visits each of its eight neighbours once every eight game ticks.
    private static final int NATIVE_VISIT_TICKS = 8, DISCOVER_PER_TICK = 43;
    private static final DeferredRegister<AttachmentType<?>> DATA = DeferredRegister.create(
            NeoForgeRegistries.ATTACHMENT_TYPES, KaleidoscopeWitchcraft.MOD_ID);
    private static final Supplier<AttachmentType<Progress>> WORK = DATA.register("node_daisy_work",
            () -> AttachmentType.builder(Progress::new).serialize(Progress.CODEC).build());
    private record Recipes(List<RecipeHolder<PureDaisyRecipe>> ordered,
            Map<ResourceLocation, RecipeHolder<PureDaisyRecipe>> byId) {
        static Recipes read(RecipeManager manager) {
            var ordered = List.copyOf(manager.getAllRecipesFor(BotaniaRecipeTypes.PURE_DAISY_TYPE));
            Map<ResourceLocation, RecipeHolder<PureDaisyRecipe>> byId = new HashMap<>();
            for (var recipe : ordered) byId.putIfAbsent(recipe.id(), recipe);
            return new Recipes(ordered, Map.copyOf(byId));
        }
    }
    private static final Map<RecipeManager, Recipes> RECIPES = new WeakHashMap<>();
    private static int generation;
    private static boolean enabled;
    // NBT compound keys must be strings; an INT map key cannot round-trip through NbtOps.
    private static final Codec<Integer> CELL_KEY = Codec.STRING.comapFlatMap(value -> {
        try {
            int cell = Integer.parseInt(value);
            return cell >= 0 && cell < CELLS ? DataResult.success(cell) : DataResult.error(() -> "Invalid daisy cell");
        } catch (NumberFormatException invalid) { return DataResult.error(() -> "Invalid daisy cell"); }
    }, Object::toString);
    private static final Codec<Long> WORK_UNITS = Codec.LONG.validate(value -> value >= 0
            ? DataResult.success(value) : DataResult.error(() -> "Negative daisy work"));

    private record Pending(BlockState input, ResourceLocation recipe, int duration, long work) {
        static final Codec<Pending> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockState.CODEC.fieldOf("input").forGetter(Pending::input),
                ResourceLocation.CODEC.fieldOf("recipe").forGetter(Pending::recipe),
                Codec.intRange(1, Integer.MAX_VALUE).fieldOf("duration").forGetter(Pending::duration),
                WORK_UNITS.fieldOf("work").forGetter(Pending::work)).apply(i, Pending::new));
    }
    private static final class Progress {
        static final Codec<Progress> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockPos.CODEC.optionalFieldOf("origin").forGetter((Progress p) -> p.origin),
                UUIDUtil.CODEC.optionalFieldOf("node").forGetter((Progress p) -> p.node),
                Codec.unboundedMap(CELL_KEY, Pending.CODEC).fieldOf("pending")
                        .forGetter((Progress p) -> p.pending)).apply(i, Progress::new));
        Optional<BlockPos> origin;
        Optional<UUID> node;
        final Map<Integer, Pending> pending;
        final Map<Integer, RecipeHolder<PureDaisyRecipe>> resolved = new HashMap<>();
        long lastTick = Long.MIN_VALUE;
        int cursor, knownGeneration = -1;
        Progress() { this(Optional.empty(), Optional.empty(), Map.of()); }
        Progress(Optional<BlockPos> origin, Optional<UUID> node, Map<Integer, Pending> pending) {
            this.origin = origin; this.node = node; this.pending = new HashMap<>(pending);
        }
        void clear() { pending.clear(); resolved.clear(); }
    }
    private NodeDaisy() {}

    public static void register(IEventBus bus) {
        DATA.register(bus);
        // Only invoked when both mods are present. Fail the optional bridge once for an older API,
        // not at every node tick; do not catch errors thrown by actual recipe code.
        try {
            PureDaisyRecipe.class.getMethod("matches", net.minecraft.world.level.Level.class, BlockPos.class, BlockState.class);
            BlockStateRecipe.class.getMethod("replaceBlock", BlockPos.class,
                    vazkii.botania.api.recipe.BlockStateRecipe.class, BlockState.class, ServerLevel.class, Runnable.class);
            enabled = true;
        } catch (ReflectiveOperationException | LinkageError unsupported) {
            org.slf4j.LoggerFactory.getLogger(NodeDaisy.class).warn("Node/Pure Daisy bridge unavailable: {}", unsupported.toString());
        }
        NeoForge.EVENT_BUS.addListener((TagsUpdatedEvent event) -> {
            if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
                RECIPES.clear(); generation++;
            }
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> RECIPES.clear());
    }

    public static void tick(ServerLevel level, BlockEntity node) {
        if (!enabled || node.isRemoved() || !level.shouldTickBlocksAt(node.getBlockPos())) return;
        var view = NodeApi.view(level, node.getBlockPos());
        if (view == null) return;
        var state = view.nodeState();
        int rate = Math.min(state.currentVis().amount(PLANT), state.currentVis().amount(MAGIC));
        if (rate <= 0) return; // No aspect debit, and no stored catch-up time while absent.
        Progress progress = node.getData(WORK);
        long now = level.getGameTime();
        if (progress.lastTick == now) return; // One scan/update batch; node ritual work is applied to its counter below.
        progress.lastTick = now;
        BlockPos origin = node.getBlockPos();
        var workRate = dev.senluo.witchcraft.compat.thaumcraft.NodePromotion.influence(level, origin).multiplier()
                .multiply(java.math.BigInteger.valueOf(rate));
        if (!progress.origin.equals(Optional.of(origin)) || !progress.node.equals(state.instanceId())
                || progress.knownGeneration != -1 && progress.knownGeneration != generation) {
            progress.clear();
            node.setChanged(); // Persist invalidation even when no replacement recipe is found.
        }
        progress.origin = Optional.of(origin.immutable()); progress.node = state.instanceId();
        progress.knownGeneration = generation;
        Recipes catalog = RECIPES.computeIfAbsent(level.getRecipeManager(), Recipes::read);
        List<RecipeHolder<PureDaisyRecipe>> recipes = catalog.ordered;
        if (recipes.isEmpty()) { if (!progress.pending.isEmpty()) { progress.clear(); node.setChanged(); } return; }

        // Idle cells are discovered in bounded slices; only matching blocks become per-tick work.
        for (int i = 0; i < DISCOVER_PER_TICK; i++) {
            int cell = progress.cursor; progress.cursor = (cell + 1) % CELLS;
            if (cell == CENTER || progress.pending.containsKey(cell)) continue;
            BlockPos pos = position(origin, cell);
            if (!loaded(level, pos)) continue;
            BlockState input = level.getBlockState(pos);
            if (input.isAir()) continue;
            for (var recipe : recipes) if (recipe.value().matches(level, pos, input)) {
                progress.pending.put(cell, new Pending(input, recipe.id(), Math.max(1, recipe.value().getTime()), 0));
                progress.resolved.put(cell, recipe); break;
            }
        }
        if (progress.pending.isEmpty()) return;
        node.setChanged(); // Persist work, never send per-tick node state packets.
        for (int cell : List.copyOf(progress.pending.keySet())) {
            if (node.isRemoved() || node.getData(WORK) != progress || progress.knownGeneration != generation) return;
            Pending pending = progress.pending.get(cell);
            BlockPos pos = position(origin, cell);
            if (cell == CENTER || !loaded(level, pos)) { forget(progress, cell); continue; }
            var recipe = progress.resolved.get(cell);
            if (recipe == null) {
                recipe = catalog.byId.get(pending.recipe);
                if (recipe != null) progress.resolved.put(cell, recipe);
            }
            if (recipe == null || level.getBlockState(pos) != pending.input
                    || Math.max(1, recipe.value().getTime()) != pending.duration
                    || !recipe.value().matches(level, pos, pending.input)) { forget(progress, cell); continue; }
            long required = (long)pending.duration * NATIVE_VISIT_TICKS;
            // Saturate at this recipe's remaining work, not at an arbitrary ritual multiplier.
            long work = Math.min(pending.work, required) + workRate.min(java.math.BigInteger.valueOf(
                    Math.max(0, required - pending.work))).longValueExact();
            if (work < required) {
                progress.pending.put(cell, new Pending(pending.input, pending.recipe, pending.duration, work));
                continue;
            }
            // Spend before foreign recipe functions; a same-tick recursive tick cannot replay it.
            forget(progress, cell);
            if (!valid(level, node, progress) || level.getBlockState(pos) != pending.input) return;
            BlockState output = recipe.value().getOutput().pick(level.getRandom());
            if (output == null) continue;
            if (recipe.value().isCopyInputProperties()) output = output.getBlock().withPropertiesOf(pending.input);
            BlockStateRecipe.replaceBlock(pos, recipe.value(), output, level, () ->
                    level.sendParticles(WispParticleData.wisp(.15F, 1, 1, 1),
                            pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, 3, .25, .25, .25, .01));
        }
    }
    private static boolean valid(ServerLevel level, BlockEntity node, Progress progress) {
        if (node.isRemoved() || !level.hasChunkAt(node.getBlockPos())) return false;
        var view = NodeApi.view(level, node.getBlockPos());
        if (view == null) return false;
        var state = view.nodeState();
        return state.currentVis().amount(PLANT) > 0 && state.currentVis().amount(MAGIC) > 0
                && level.getBlockEntity(node.getBlockPos()) == node && node.getData(WORK) == progress
                && progress.node.equals(state.instanceId());
    }
    private static boolean loaded(ServerLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.hasChunkAt(pos) && level.shouldTickBlocksAt(pos);
    }
    private static BlockPos position(BlockPos origin, int cell) {
        return origin.offset(cell % WIDTH - 3, cell / WIDTH % WIDTH - 3, cell / (WIDTH * WIDTH) - 3);
    }
    private static void forget(Progress progress, int cell) { progress.pending.remove(cell); progress.resolved.remove(cell); }
}
