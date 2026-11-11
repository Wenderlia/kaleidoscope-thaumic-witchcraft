package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.ritual.*;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class DeepDrilling implements RitualCenters.Behavior {
    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft", "deep_drilling");
    private static final StructuralRitualStore.Definition RITE = new StructuralRitualStore.Definition(
            ID,
            1, 80, new int[]{20,40,60,80}, RitualRings.LARGE, RitualResearch.thaumcraft("DRILLING_RITUALS"));
    private static final CenterOfferings OFFERINGS = new CenterOfferings(RITE, List.of(
            stack -> stack.is(Items.DIAMOND_PICKAXE), stack -> stack.is(Items.CRYING_OBSIDIAN),
            stack -> stack.is(Items.ENDER_PEARL), stack -> stack.is(Items.DEEPSLATE)));
    private DeepDrilling() {}
    private record Active(java.util.UUID ritual, RitualSite site) {}
    private static final java.util.Map<ServerLevel, java.util.Map<BlockPos, List<Active>>> ACTIVE = new java.util.WeakHashMap<>();
    public static void register() {
        RitualCenters.register(new DeepDrilling());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST,
                (net.neoforged.neoforge.event.tick.LevelTickEvent.Pre event) -> {
                    var active = ACTIVE.get(event.getLevel());
                    if (active != null) active.clear();
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.LevelEvent.Unload event) -> {
            ACTIVE.remove(event.getLevel());
            if (event.getLevel() instanceof ServerLevel level) { DeepMineStore.unload(level); MixedMine.unload(level); }
        });
    }
    @Override public StructuralRitualStore.Definition definition() { return RITE; }
    @Override public RitualCenters.Companion companion() {
        return (level, plane) -> RitualCenters.find(level, plane,
                entity -> entity.getBlockState().is(dev.tc4port.thaumcraft.api.ThaumcraftContent.block("arcane_bore")));
    }
    private static boolean target(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos) && level.shouldTickBlocksAt(pos)
                && level.getBlockState(pos).is(dev.tc4port.thaumcraft.api.ThaumcraftContent.block("arcane_bore"));
    }
    @Override public net.minecraft.world.InteractionResult activate(ServerLevel level, RitualSite site, ServerPlayer player) {
        if (!OFFERINGS.matches(level, site)) return net.minecraft.world.InteractionResult.PASS;
        DeepMineStore.get(level); // Capture legacy receipts before a new ritual is created.
        return RitualPlane.centerPositions(site.plane().anchor())
                .anyMatch(pos -> target(level, pos) && level.mayInteract(player, pos))
                && OFFERINGS.start(player, site, new net.minecraft.nbt.CompoundTag())
                ? net.minecraft.world.InteractionResult.CONSUME : net.minecraft.world.InteractionResult.FAIL;
    }

    @Override public void tick(ServerLevel level, RitualSite site, StructuralRitualStore.Handle h) {
        boolean ready = site.plane() != null && RitualPlane.centerPositions(site.plane().anchor()).anyMatch(pos -> target(level, pos));
        if (!OFFERINGS.tick(level, site, h.id(), ready)) { DeepMineStore.get(level).pause(h.id()); return; }
        DeepMineStore.get(level).ensure(h.id());
        var active = ACTIVE.computeIfAbsent(level, unused -> new java.util.HashMap<>());
        var effect = new Active(h.id(), site);
        RitualPlane.centerPositions(site.plane().anchor()).forEach(pos ->
                active.computeIfAbsent(pos, unused -> new java.util.ArrayList<>()).add(effect));
    }
    static DeepMineStore.Mine mine(ServerLevel level, BlockEntity bore) {
        var active = ACTIVE.get(level);
        var effects = active == null ? null : active.get(bore.getBlockPos());
        if (effects != null) for (var effect : effects)
            if (RitualCenters.current(level, effect.site())) return DeepMineStore.get(level).find(effect.ritual());
        return null;
    }
    public static boolean active(ServerLevel level, BlockEntity bore) { return mine(level, bore) != null; }
    @Override public void removed(ServerLevel level, StructuralRitualStore.Handle handle) {
        RitualCenters.Behavior.super.removed(level, handle);
        DeepMineStore.get(level).remove(handle.id());
    }
    @Override public void unavailable(ServerLevel level, StructuralRitualStore.Handle handle) {
        var active = ACTIVE.get(level);
        if (active != null) {
            active.values().forEach(effects -> effects.removeIf(effect -> effect.ritual().equals(handle.id())));
            active.values().removeIf(List::isEmpty);
        }
        DeepMineStore.get(level).pause(handle.id());
    }

}
