package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.ritual.*;
import dev.senluo.witchcraft.ritual.visual.RestraintGeometry;
import dev.senluo.witchcraft.ritual.visual.RestraintGeometry.Phase;
import dev.tc4port.thaumcraft.api.ThaumcraftApi;
import dev.tc4port.thaumcraft.api.event.InfusionEvent;
import dev.tc4port.thaumcraft.api.recipe.InfusionCentralTransformation;
import net.minecraft.world.level.block.entity.BlockEntity;
import dev.tc4port.thaumcraft.api.machine.InfusionMatrixApi;
import dev.tc4port.thaumcraft.api.machine.InfusionRunState;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;

/** Native infusion owns inventory/work/accidents. This adapter owns eligibility, presentation and
 * the post-commit UUID effect. No fake player, offline entity loading, or global TC parameter changes. */
public final class InfusionGeas {
    private static final String KEY = "kaleidoscope_witchcraft:geas_attempt";
    private static final Map<BlockEntity, Runtime> LIVE = new WeakHashMap<>();
    private static final class Runtime {
        final UUID id;
        final GeasRitualVisuals.Emitter emitter;
        final InfusionOfferingVisuals offeringVisuals = new InfusionOfferingVisuals();
        final List<Absorbed> pending = new ArrayList<>();
        long tick = Long.MIN_VALUE;
        Runtime(UUID id, GeasRites.Rite rite) { this.id = id; emitter = new GeasRitualVisuals.Emitter(id, rite.glyph()); }
    }
    private record Absorbed(BlockPos source,int ingredientsBefore) {}
    // Receipts exist only between Output and End within the same synchronous native work pulse.
    private record Receipt(UUID target, ItemStack remainder, BlockEntity matrix,
                           RitualPlane plane, Runtime runtime, UUID attempt, GeasBindings.Transition transition) {}
    private static final Map<ServerLevel, Map<BlockPos, Receipt>> RECEIPTS = new WeakHashMap<>();
    private InfusionGeas() {}

    public static void register() {
        RitualCenterTicks.register();
        InfusionInfluence.register();
        for (var rite : GeasRites.ALL) ThaumcraftApi.registerInfusionCentralTransformation(rite.id(), new InfusionCentralTransformation() {
            @Override public boolean matches(ItemStack input) { return subject(input) != null; }
            @Override public ItemStack transform(ItemStack input, HolderLookup.Provider registries) {
                return GeasRecipePreview.outcome(rite);
            }
            @Override public List<ItemStack> previewInputs(net.minecraft.world.level.Level level, List<ItemStack> ingredients) {
                return List.of(GeasRecipePreview.sample());
            }
            @Override public ItemStack transformOnCompletion(ItemStack input, ServerLevel level) {
                // Native completion never delivers the display token. Output/End consumes the real sample.
                return input.copy();
            }
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, InfusionGeas::start);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, InfusionGeas::output);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, InfusionGeas::absorbed);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, InfusionGeas::end);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.LevelEvent.Unload event) -> {
            if (event.getLevel() instanceof ServerLevel level) {
                var receipts = RECEIPTS.remove(level);
                if (receipts != null) receipts.values().forEach(r -> r.transition().close());
                LIVE.keySet().removeIf(matrix -> matrix.getLevel() == level);
            }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.ChunkEvent.Unload event) -> {
            if (event.getLevel() instanceof ServerLevel level)
                LIVE.keySet().removeIf(matrix -> matrix.getLevel() == level
                        && new net.minecraft.world.level.ChunkPos(matrix.getBlockPos()).equals(event.getChunk().getPos()));
        });
    }
    private static void absorbed(InfusionEvent.ComponentAbsorbed event) {
        var matrix = event.level().getBlockEntity(event.matrix());
        if (GeasRites.find(event.recipeId()) == null || matrix == null) return;
        CompoundTag tag=attempt(matrix);
        if(!valid(tag) || matrix.isRemoved())return;
        Runtime live=runtime(matrix,tag);
        // The public callback precedes pedestal writeback. Defer presentation until the remaining
        // component count proves the native work committed. Never write to the pedestal here.
        var state = InfusionMatrixApi.state(event.level(), event.matrix()).orElse(null);
        if (state != null && live.pending.size()<8)live.pending.add(new Absorbed(event.pedestal().immutable(),state.remainingIngredients()));
    }
    private static void flushAbsorbed(ServerLevel level,BlockEntity matrix,Runtime live) {
        if(live.pending.isEmpty())return;
        var state = InfusionMatrixApi.state(level, matrix.getBlockPos()).orElse(null);
        if (state == null) { live.pending.clear(); return; }
        List<BlockPos> committed=live.pending.stream().filter(p->state.remainingIngredients()<p.ingredientsBefore())
                .map(Absorbed::source).toList();
        live.pending.clear();
        if(!matrix.isRemoved())live.emitter.absorbed(level,matrix.getBlockPos(),committed);
    }
    private static UUID subject(ItemStack input) {
        if (!input.is(RitualContent.TAGLOCK.get())) return null;
        TaglockSample sample = input.get(RitualContent.TAGLOCK_SAMPLE.get());
        return sample != null && sample.supported() && sample.isPlayer() ? sample.subject() : null;
    }
    static boolean hasAttempt(BlockEntity matrix) { return !attempt(matrix).isEmpty(); }
    private static CompoundTag attempt(BlockEntity matrix) {
        return matrix.getPersistentData().getCompound(KEY);
    }
    private static boolean valid(CompoundTag tag) {
        return tag.getInt("Version") == 2
                && tag.hasUUID("Attempt") && tag.hasUUID("Target")
                && tag.contains("Plane", net.minecraft.nbt.Tag.TAG_LONG) && rite(tag) != null && rite(tag).accepts(expected(tag));
    }
    private static GeasRites.Rite rite(CompoundTag tag) {
        return GeasRites.find(net.minecraft.resources.ResourceLocation.tryParse(tag.getString("Recipe")));
    }
    private static BindingPair.State expected(CompoundTag tag) {
        try { return BindingPair.State.valueOf(tag.getString("Expected")); }
        catch (IllegalArgumentException invalid) { return null; }
    }
    private static void releaseReceipt(ServerLevel level, BlockPos matrix) {
        var receipts = RECEIPTS.get(level);
        Receipt old = receipts == null ? null : receipts.remove(matrix);
        if (old != null) old.transition().close();
    }
    private static Runtime runtime(BlockEntity matrix, CompoundTag tag) {
        UUID id = tag.getUUID("Attempt");
        Runtime old = LIVE.get(matrix);
        if (old != null && old.id.equals(id)) return old;
        Runtime fresh = new Runtime(id, rite(tag)); LIVE.put(matrix, fresh); return fresh;
    }
    private static void clear(BlockEntity matrix) {
        matrix.getPersistentData().remove(KEY); matrix.setChanged(); LIVE.remove(matrix);
    }
    private static Optional<RitualPlane> plane(ServerLevel level, BlockEntity matrix, CompoundTag tag) {
        BlockPos pos = BlockPos.of(tag.getLong("Plane")), center = matrix.getBlockPos();
        if (pos.getX() != center.getX() || pos.getZ() != center.getZ()
                || Math.abs((long)pos.getY() - center.getY()) > RitualPlane.HEIGHT_RADIUS) return Optional.empty();
        return InfusionRings.at(level, pos);
    }
    private static void start(InfusionEvent.Start event) {
        var matrix = event.level().getBlockEntity(event.matrix());
        if (event.isCanceled() || matrix == null) return;
        releaseReceipt(event.level(), event.matrix());
        var rite = GeasRites.find(event.recipeId());
        if (rite == null) { clear(matrix); return; }
        UUID target = subject(event.central());
        var plane = InfusionRings.find(event.level(), event.matrix());
        if (target == null || plane.isEmpty() || !GeasBindings.writable(event.level().getServer())) {
            event.setCanceled(true); return;
        }
        var expected = GeasBindings.state(event.level().getServer(), target, rite.pair());
        if (!rite.accepts(expected)) { event.setCanceled(true); return; }
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", 2); tag.putUUID("Attempt", UUID.randomUUID()); tag.putUUID("Target", target);
        tag.putString("Recipe", rite.id().toString()); tag.putString("Expected", expected.name());
        tag.putLong("Plane", plane.get().anchor().asLong()); tag.putInt("VisualTicks", 0);
        tag.putBoolean("AcceptancePending", true);
        matrix.getPersistentData().put(KEY, tag); matrix.setChanged(); LIVE.remove(matrix);
        RitualCenterTicks.track(event.level(), matrix.getBlockPos());
    }
    static boolean allowPulse(BlockEntity matrix, InfusionRunState state) {
        CompoundTag tag = attempt(matrix);
        if (tag.isEmpty()) return true;
        if (!valid(tag) || !(matrix.getLevel() instanceof ServerLevel level)) return false;
        if (plane(level, matrix, tag).isEmpty() || !GeasBindings.writable(level.getServer())
                || GeasBindings.state(level.getServer(), tag.getUUID("Target"), rite(tag).pair()) != expected(tag)) return false;
        // Finish the short assembly before starting native auxiliary-item absorption.
        return !state.remainingEssentia().amounts().isEmpty() || tag.getInt("VisualTicks") >= RestraintGeometry.FORM_TICKS;
    }
    /** One presentation clock per real matrix game tick; accelerated work never multiplies network traffic. */
    public static void tick(BlockEntity matrix) {
        CompoundTag tag = attempt(matrix);
        if (tag.isEmpty() || !(matrix.getLevel() instanceof ServerLevel level)) return;
        var state = InfusionMatrixApi.state(level, matrix.getBlockPos()).orElse(null);
        if (state == null) return;
        if (!state.crafting()) { if (valid(tag)) clear(matrix); return; }
        if (!valid(tag) || !state.active() || !level.hasChunksAt(matrix.getBlockPos().offset(-12, 0, -12), matrix.getBlockPos().offset(12, 0, 12))) return;
        Runtime live = runtime(matrix, tag);
        long now = level.getGameTime();
        if (live.tick == now) return;
        live.tick = now;
        flushAbsorbed(level,matrix,live);
        var plane = plane(level, matrix, tag);
        if (plane.isEmpty() || !GeasBindings.writable(level.getServer())
                || GeasBindings.state(level.getServer(), tag.getUUID("Target"), rite(tag).pair()) != expected(tag)) return;
        if(tag.getBoolean("AcceptancePending"))acceptTarget(level,matrix,tag,live,plane.get());
        if (!state.remainingEssentia().amounts().isEmpty()) {
            int waiting=Math.max(0,Math.min(999_999,tag.getInt("WaitingVisualTicks")));
            live.emitter.publish(level,matrix.getBlockPos(),plane.get(),tag.getUUID("Target"),Phase.WAITING,waiting);
            tag.putInt("WaitingVisualTicks",waiting+1);matrix.setChanged();return;
        }
        int age = Math.max(0, Math.min(999_999, tag.getInt("VisualTicks")));
        live.emitter.publish(level, matrix.getBlockPos(), plane.get(), tag.getUUID("Target"),
                age < RestraintGeometry.FORM_TICKS ? Phase.FORMING : Phase.BINDING,
                age < RestraintGeometry.FORM_TICKS ? age : age - RestraintGeometry.FORM_TICKS,
                () -> live.offeringVisuals.snapshot(level, matrix));
        tag.putInt("VisualTicks", age + 1); matrix.setChanged();
    }
    private static void acceptTarget(ServerLevel level,BlockEntity matrix,CompoundTag tag,Runtime live,RitualPlane plane) {
        var player=level.getServer().getPlayerList().getPlayer(tag.getUUID("Target"));
        if(player==null || player.level()!=level || !player.isAlive() || player.isSpectator())return;
        var footprint=RitualOfferingArea.visualFootprint(plane.anchor(),plane.outermostRing());
        if(!footprint.bounds().contains(player.position()) || !footprint.containsHorizontal(player.position()))return;
        // Persist before the optional packet. Re-entry, reload and a new runtime wrapper cannot replay it.
        tag.putBoolean("AcceptancePending",false);matrix.setChanged();
        live.emitter.accepted(level,matrix.getBlockPos(),player.getUUID(),player.position().add(0,player.getBbHeight()*.65,0));
    }
    private static void output(InfusionEvent.Output event) {
        var rite = GeasRites.find(event.recipeId());
        if (rite == null) return;
        releaseReceipt(event.level(), event.matrix());
        // Failure to validate never consumes the central stack or creates a binding.
        ItemStack input = event.central(); event.setOutput(input);
        var matrix = event.level().getBlockEntity(event.matrix());
        if (matrix == null) return;
        CompoundTag tag = attempt(matrix);
        UUID target = subject(input);
        if (!valid(tag) || !rite.equals(rite(tag)) || target == null || !target.equals(tag.getUUID("Target"))
                || !GeasBindings.writable(event.level().getServer())) return;
        var plane = plane(event.level(), matrix, tag);
        if (plane.isEmpty()) return;
        var transition = GeasBindings.reserve(event.level().getServer(), target, rite, expected(tag));
        if (transition == null) return;
        ItemStack remainder = input.copy(); remainder.shrink(1); event.setOutput(remainder);
        RECEIPTS.computeIfAbsent(event.level(), unused -> new HashMap<>()).put(event.matrix(),
                new Receipt(target, remainder.copy(), matrix, plane.get(), runtime(matrix, tag), tag.getUUID("Attempt"), transition));
    }
    private static void end(InfusionEvent.End event) {
        if (GeasRites.find(event.recipeId()) == null) return;
        var receipts = RECEIPTS.get(event.level());
        Receipt receipt = receipts == null ? null : receipts.remove(event.matrix());
        boolean committed = receipt != null && event.outcome() == InfusionEvent.End.Outcome.COMPLETED
                && ItemStack.matches(receipt.remainder(), event.output());
        if (committed) {
            var change = receipt.transition().commit();
            committed = change == BindingPair.Change.GRANTED || change == BindingPair.Change.RELEASED;
        }
        if (receipt != null) receipt.transition().close();
        BlockEntity matrix = receipt != null ? receipt.matrix()
                : event.level().getBlockEntity(event.matrix());
        if (matrix == null) return;
        CompoundTag tag = attempt(matrix);
        // Clear only this attempt before presentation callbacks. A callback may start a new craft.
        Runtime live = valid(tag) ? runtime(matrix, tag) : null;
        var cancelledPlane = !committed && valid(tag) ? plane(event.level(), matrix, tag) : Optional.<RitualPlane>empty();
        UUID target = valid(tag) ? tag.getUUID("Target") : null;
        if (receipt == null || valid(tag) && receipt.attempt().equals(tag.getUUID("Attempt"))) clear(matrix);
        if (committed) {
            flushAbsorbed(event.level(), receipt.matrix(), receipt.runtime());
            receipt.runtime().emitter.publish(event.level(), event.matrix(), receipt.plane(), receipt.target(), Phase.COMPLETE, 0);
        } else if (live != null && target != null) cancelledPlane.ifPresent(p -> live.emitter
                .publish(event.level(), event.matrix(), p, target, Phase.CANCELLED, 0));
    }
}
