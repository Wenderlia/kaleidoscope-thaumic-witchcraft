package dev.senluo.witchcraft.compat.tavern;

import com.github.ysbbbbbb.kaleidoscopetavern.blockentity.brew.BarrelBlockEntity;
import dev.senluo.witchcraft.brew.WineData;
import dev.senluo.witchcraft.time.*;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.items.ItemStackHandler;

/** A bounded, reversible input buffer for individually tagged roots. */
public final class BarrelBatch {
    public static final int ROOT_LIMIT = 16, WHEAT = 8, HONEY = 4, OUTPUT = 16;
    private final List<ItemStack> roots = new ArrayList<>();
    private int wheat, honey;
    private CompoundTag future;
    private ItemStackHandler preview;
    private Boolean readableRoots;
    public int roots() { return roots.size(); }
    public int wheat() { return wheat; }
    public int honey() { return honey; }
    public boolean active() { return future != null || wheat > 0 || honey > 0 || !roots.isEmpty(); }
    public boolean ready(BarrelBlockEntity barrel) {
        return future == null && !roots.isEmpty() && wheat == WHEAT && honey == HONEY && water(barrel)
                && readableRoots();
    }
    private boolean readableRoots() {
        if (readableRoots == null) readableRoots = roots.stream().allMatch(stack -> TissueItem.read(stack).supported());
        return readableRoots;
    }
    private static boolean water(BarrelBlockEntity barrel) {
        return barrel.getFluid().getFluidAmount() == 4000 && barrel.getFluid().getFluid().getFluid().isSame(Fluids.WATER);
    }
    public static boolean special(ItemStack stack) { return stack.is(TemporalContent.TISSUE.get()) || stack.is(Items.HONEY_BOTTLE); }
    public boolean add(BarrelBlockEntity barrel, LivingEntity user, ItemStack stack) {
        if (future != null || !barrel.isOpen() || barrel.isBrewing()) return false;
        if (!water(barrel)) { tip(user, "water_first"); return false; }
        boolean root = stack.is(TemporalContent.TISSUE.get()), sweet = stack.is(Items.HONEY_BOTTLE);
        if (!root && !sweet && !stack.is(Items.WHEAT)) return false;
        if (root && !TissueItem.read(stack).supported()) return false;
        if (root && roots.size() >= ROOT_LIMIT || sweet && honey >= HONEY || stack.is(Items.WHEAT) && wheat >= WHEAT) {
            tip(user, "full"); return false;
        }
        int pendingWheat = 0;
        for (int i = 0; i < barrel.getIngredient().getSlots(); i++) {
            ItemStack item = barrel.getIngredient().getStackInSlot(i);
            if (!item.isEmpty() && !item.is(Items.WHEAT)) { tip(user, "other_ingredients"); return false; }
            pendingWheat += item.getCount();
        }
        if (wheat + pendingWheat > WHEAT) { tip(user, "other_ingredients"); return false; }
        if (barrel.getLevel() == null) return false;
        if (barrel.getLevel().isClientSide) return true;
        wheat += pendingWheat;
        for (int i = 0; i < barrel.getIngredient().getSlots(); i++) barrel.getIngredient().setStackInSlot(i, ItemStack.EMPTY);
        if (root) { roots.add(stack.copyWithCount(1)); stack.shrink(1); }
        else if (sweet) { honey++; stack.shrink(1); give(barrel, user, new ItemStack(Items.GLASS_BOTTLE)); }
        else { int used = Math.min(stack.getCount(), WHEAT - wheat); wheat += used; stack.shrink(used); }
        changed(barrel); info(user); return true;
    }
    public boolean take(BarrelBlockEntity barrel, LivingEntity user) {
        if (future != null || !barrel.isOpen() || barrel.isBrewing() || barrel.getLevel() == null) return false;
        if (roots.isEmpty() && wheat == 0) { if (honey > 0) tip(user, "honey_return"); return false; }
        if (barrel.getLevel().isClientSide) return true;
        ItemStack returned;
        if (!roots.isEmpty()) returned = roots.removeLast();
        else { returned = new ItemStack(Items.WHEAT, wheat); wheat = 0; }
        give(barrel, user, returned); changed(barrel); info(user); return true;
    }
    public boolean takeHoney(BarrelBlockEntity barrel, LivingEntity user, ItemStack bottles) {
        if (future != null || honey == 0 || !bottles.is(Items.GLASS_BOTTLE) || !barrel.isOpen() || barrel.isBrewing()) return false;
        if (barrel.getLevel() == null) return false;
        if (barrel.getLevel().isClientSide) return true;
        bottles.shrink(1); honey--; give(barrel, user, new ItemStack(Items.HONEY_BOTTLE)); changed(barrel); info(user); return true;
    }
    public WineData wine() {
        double totalTu = 0, maturity = 0;
        for (ItemStack stack : roots) {
            Tissue tissue = TissueItem.read(stack);
            totalTu += (double)tissue.stored() / Tissue.TU;
            maturity += WineData.maturity(tissue);
        }
        // Time is divided among 16 bottles; maturity describes the actual roots put into this batch.
        return new WineData(totalTu / OUTPUT, roots.isEmpty() ? 0 : maturity / roots.size(), 1);
    }
    public void clear() { roots.clear(); wheat = honey = 0; future = null; preview = null; readableRoots = null; }
    public void dropInputs(BarrelBlockEntity barrel) {
        if (future != null) return;
        for (ItemStack stack : roots) give(barrel, null, stack);
        if (wheat > 0) give(barrel, null, new ItemStack(Items.WHEAT, wheat));
        clear(); // Liquids spill, as with an ordinary broken barrel.
    }
    public void info(LivingEntity user) {
        if (user instanceof Player player && !player.level().isClientSide)
            player.displayClientMessage(TemporalText.color(Component.translatable("message.kaleidoscope_witchcraft.brew.inputs",
                    TemporalText.number(roots.size(), TemporalText.AMOUNT), TemporalText.number(wheat, TemporalText.AMOUNT),
                    TemporalText.number(honey, TemporalText.AMOUNT)), TemporalText.BODY), true);
    }
    public static void tip(LivingEntity user, String key) {
        if (user instanceof Player player && !player.level().isClientSide)
            player.displayClientMessage(TemporalText.color(Component.translatable("message.kaleidoscope_witchcraft.brew." + key), TemporalText.PENDING), true);
    }
    public static void give(BarrelBlockEntity barrel, LivingEntity user, ItemStack stack) {
        if (stack.isEmpty() || barrel.getLevel() == null || barrel.getLevel().isClientSide) return;
        if (user instanceof Player player && player.getInventory().add(stack)) return;
        var pos = barrel.getBlockPos();
        barrel.getLevel().addFreshEntity(new ItemEntity(barrel.getLevel(), pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5, stack));
    }
    private void changed(BarrelBlockEntity barrel) { preview = null; readableRoots = null; barrel.refresh(); }
    public ItemStackHandler preview() {
        if (preview == null) {
            preview = new ItemStackHandler(4);
            for (int i = 0; i < Math.min(3, roots.size()); i++) preview.setStackInSlot(i, roots.get(i).copy());
            if (wheat > 0) preview.setStackInSlot(3, new ItemStack(Items.WHEAT, wheat));
        }
        return preview;
    }
    public CompoundTag save(HolderLookup.Provider provider) {
        if (future != null) return future.copy();
        CompoundTag tag = new CompoundTag(); tag.putInt("Version", 1); tag.putInt("Wheat", wheat); tag.putInt("Honey", honey);
        ListTag list = new ListTag(); for (ItemStack root : roots) list.add(root.save(provider)); tag.put("Roots", list); return tag;
    }
    public void load(HolderLookup.Provider provider, CompoundTag tag) {
        clear();
        if (tag.isEmpty()) return;
        if (!tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 1
                || !tag.contains("Wheat", Tag.TAG_INT) || !tag.contains("Honey", Tag.TAG_INT)
                || tag.getInt("Wheat") < 0 || tag.getInt("Wheat") > WHEAT
                || tag.getInt("Honey") < 0 || tag.getInt("Honey") > HONEY
                || !(tag.get("Roots") instanceof ListTag list) || list.size() > ROOT_LIMIT
                || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) { future = tag.copy(); return; }
        for (int i = 0; i < list.size(); i++) {
            ItemStack stack = ItemStack.parseOptional(provider, list.getCompound(i));
            if (!stack.is(TemporalContent.TISSUE.get()) || stack.getCount() != 1) {
                roots.clear(); future = tag.copy(); return;
            }
            roots.add(stack);
        }
        wheat = tag.getInt("Wheat"); honey = tag.getInt("Honey");
    }
}
