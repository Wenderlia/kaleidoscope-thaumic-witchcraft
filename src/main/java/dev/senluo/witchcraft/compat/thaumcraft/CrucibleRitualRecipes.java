package dev.senluo.witchcraft.compat.thaumcraft;

import net.minecraft.world.level.block.entity.BlockEntity;
import dev.senluo.witchcraft.ritual.*;
import dev.tc4port.thaumcraft.api.aspect.AspectApi;
import dev.tc4port.thaumcraft.api.aspect.AspectId;
import dev.tc4port.thaumcraft.api.machine.CrucibleApi;
import dev.tc4port.thaumcraft.api.ThaumcraftContent;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Concrete TC vessel recipes; lifecycle, ring allocation and flow arithmetic stay in their own modules. */
enum CrucibleRitualRecipes {
    STEAM(StructuralRitualStore.STEAM, List.of(
            new Offering(mc("coal"), true), new Offering(tc("silverwood_log"), true), new Offering(tc("quicksilver"), false))),
    SUCTION(StructuralRitualStore.SUCTION, List.of(
            new Offering(tc("quicksilver"), true), new Offering(mc("feather"), true),
            new Offering(tc("silverwood_log"), true), new Offering(mc("gold_ingot"), false)));

    static final AspectId VACUOS = AspectId.parse("vacuos"), ORDO = AspectId.parse("ordo"),
            HERBA = AspectId.parse("herba"), POTENTIA = AspectId.parse("potentia");
    private static final TagKey<Item> TUBES = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(
            "kaleidoscope_witchcraft", "essentia_tubes"));
    record Offering(ResourceLocation item, boolean consumed) {}
    final StructuralRitualStore.Definition definition;
    final List<Offering> offerings;
    CrucibleRitualRecipes(StructuralRitualStore.Definition definition, List<Offering> offerings) {
        this.definition = definition; this.offerings = offerings;
    }
    static Optional<CrucibleRitualRecipes> from(StructuralRitualStore.Definition definition) {
        for (var recipe : values()) if (recipe.definition.kind().equals(definition.kind())) return Optional.of(recipe);
        return Optional.empty();
    }
    static Optional<CrucibleRitualRecipes> triggeredBy(ItemStack stack) {
        for (var recipe : values()) if (recipe.trigger(stack)) return Optional.of(recipe);
        return Optional.empty();
    }
    boolean trigger(ItemStack stack) {
        return this == STEAM ? is(stack, tc("nitor")) : stack.is(TUBES)
                || stack.getItem() instanceof BlockItem block && ThaumcraftContent.blockEntity("essentia_tube").isValid(block.getBlock().defaultBlockState());
    }
    static boolean is(ItemStack stack, ResourceLocation id) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id);
    }
    static ResourceLocation tc(String path) { return ResourceLocation.fromNamespaceAndPath("thaumcraft", path); }
    static ResourceLocation mc(String path) { return ResourceLocation.withDefaultNamespace(path); }

    Optional<CompoundTag> quote(BlockEntity pot, UUID trigger) {
        var view = EssentiaStores.crucible(pot).orElse(null);
        if (view == null) return Optional.empty();
        var contents = view.essentiaContents(null);
        CompoundTag terms = new CompoundTag();
        terms.putInt("Format", 4); terms.putBoolean("Armed", true); terms.putUUID("Trigger", trigger);
        if (this == STEAM) {
            int radius = RitualStability.steamRadius(contents.amount(VACUOS), contents.amount(ORDO));
            if (radius < 1) return Optional.empty();
            SteamCharge charge = SteamCharge.quote(radius, contents.amount(POTENTIA));
            terms.putInt("Radius", radius); terms.putInt("EnergyGroups", charge.energyGroups());
        } else {
            SuctionCharge charge = SuctionCharge.quote(contents.amount(VACUOS), contents.amount(ORDO));
            terms.putInt("FlowGroups", charge.flowGroups()); terms.putInt("RangeGroups", charge.rangeGroups());
        }
        return Optional.of(terms);
    }
    boolean valid(CompoundTag terms) {
        if (!terms.contains("Format", Tag.TAG_INT) || !terms.getBoolean("Armed") || !terms.hasUUID("Trigger")) return false;
        int version = terms.getInt("Format");
        if (version != 4 || terms.getBoolean("Nodes")) return false;
        if (this == SUCTION) return groups(terms, "FlowGroups", SuctionCharge.MAX_GROUPS)
                && groups(terms, "RangeGroups", SuctionCharge.MAX_GROUPS);
        return terms.contains("Radius", Tag.TAG_INT)
                && terms.getInt("Radius") >= 1 && terms.getInt("Radius") <= 32
                && groups(terms, "EnergyGroups", SteamCharge.MAX_ENERGY_GROUPS);
    }
    private static boolean groups(CompoundTag terms, String key, int maximum) {
        return terms.contains(key, Tag.TAG_INT) && terms.getInt(key) >= 0 && terms.getInt(key) <= maximum;
    }
    SteamCharge steam(CompoundTag terms) {
        return new SteamCharge(terms.getInt("Radius"), terms.getInt("EnergyGroups"));
    }
    SuctionCharge suction(CompoundTag terms) {
        return new SuctionCharge(terms.getInt("FlowGroups"), terms.getInt("RangeGroups"));
    }
    boolean canPay(BlockEntity pot, CompoundTag terms) {
        var view = EssentiaStores.crucible(pot).orElse(null);
        if (view == null) return false;
        var contents = view.essentiaContents(null);
        if (!valid(terms) || EssentiaStores.total(contents) > view.stableCapacity()) return false;
        return this == STEAM ? steam(terms).canPay(contents.amount(VACUOS), contents.amount(ORDO), contents.amount(POTENTIA))
                : suction(terms).canPay(contents.amount(VACUOS), contents.amount(ORDO));
    }
    /** Preflight the whole bill; the public native steps run without intervening callbacks on this thread. */
    boolean pay(BlockEntity pot, CompoundTag terms) {
        if (!(pot.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()
                || !level.hasChunkAt(pot.getBlockPos()) || level.getBlockEntity(pot.getBlockPos()) != pot
                || !canPay(pot, terms)) return false;
        var costs = new LinkedHashMap<AspectId, Integer>();
        if (this == STEAM) {
            var charge = steam(terms);
            costs.put(VACUOS, charge.rangeCost());
            costs.put(ORDO, charge.rangeCost());
            costs.put(POTENTIA, charge.energyCost());
        } else {
            var charge = suction(terms);
            costs.put(VACUOS, charge.flowCost());
            costs.put(ORDO, charge.rangeCost());
        }
        costs.values().removeIf(amount -> amount == 0);
        if (costs.keySet().stream().anyMatch(aspect -> AspectApi.registry().get(aspect).isEmpty())) return false;
        for (var cost : costs.entrySet()) {
            if (!CrucibleApi.removeAspect(level, pot.getBlockPos(), cost.getKey(), cost.getValue()))
                throw new IllegalStateException("Prevalidated native crucible became unavailable during payment");
        }
        SteamRituals.refreshHerba(pot);
        if (!CrucibleApi.changedAndSync(level, pot.getBlockPos()))
            throw new IllegalStateException("Paid native crucible became unavailable before synchronization");
        return true;
    }
}
