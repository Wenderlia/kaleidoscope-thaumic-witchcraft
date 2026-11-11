package dev.senluo.witchcraft.mixin.tavern;

import com.github.ysbbbbbb.kaleidoscopetavern.blockentity.brew.BarrelBlockEntity;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.brew.WineData;
import dev.senluo.witchcraft.compat.tavern.*;
import dev.senluo.witchcraft.time.TemporalContent;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(value=BarrelBlockEntity.class, remap=false)
public abstract class WineBarrelMixin implements WineBarrel {
    @Shadow @Final private ItemStackHandler output;
    @Shadow private int brewLevel;
    @Shadow private int brewTime;
    @Shadow private ResourceLocation recipeId;
    @Unique private final BarrelBatch witchcraft$batch = new BarrelBatch();
    @Unique private ItemStack witchcraft$extracted = ItemStack.EMPTY;
    @Unique private int witchcraft$extraTicks;
    /** Extra calls have their own persisted phase; native world time and recipes stay untouched. */
    @Unique private long witchcraft$workTime(Level level) {
        if (!dev.senluo.witchcraft.time.ExtraTicks.active()) return level.getGameTime();
        witchcraft$extraTicks = (witchcraft$extraTicks + 1) % 97;
        witchcraft$self().setChanged();
        return witchcraft$extraTicks - (witchcraft$self().hashCode() % 97 + 97);
    }
    @WrapOperation(method="tick(Lnet/minecraft/world/level/Level;)V", at=@At(value="INVOKE", target="Lnet/minecraft/world/level/Level;getGameTime()J"))
    private long witchcraft$extraClock(Level level, Operation<Long> original) {
        return dev.senluo.witchcraft.time.ExtraTicks.active() ? witchcraft$workTime(level) : original.call(level);
    }
    @Override public BarrelBatch witchcraft$batch() { return witchcraft$batch; }
    @Unique private BarrelBlockEntity witchcraft$self() { return (BarrelBlockEntity)(Object)this; }
    @Inject(method="addIngredient(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)Z", at=@At("HEAD"), cancellable=true)
    private void witchcraft$add(LivingEntity user, ItemStack stack, CallbackInfoReturnable<Boolean> ci) {
        if (BarrelBatch.special(stack) || witchcraft$batch.active()) ci.setReturnValue(witchcraft$batch.add(witchcraft$self(), user, stack));
    }
    @Inject(method="addFluid(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)Z", at=@At("HEAD"), cancellable=true)
    private void witchcraft$fluid(LivingEntity user, ItemStack stack, CallbackInfoReturnable<Boolean> ci) {
        if (stack.is(Items.HONEY_BOTTLE)) ci.setReturnValue(witchcraft$batch.add(witchcraft$self(), user, stack));
        else if (witchcraft$batch.active()) ci.setReturnValue(false);
    }
    @Inject(method="removeFluid(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)Z", at=@At("HEAD"), cancellable=true)
    private void witchcraft$takeHoney(LivingEntity user, ItemStack stack, CallbackInfoReturnable<Boolean> ci) {
        if (witchcraft$batch.active()) ci.setReturnValue(witchcraft$batch.takeHoney(witchcraft$self(), user, stack));
    }
    @Inject(method="removeIngredient(Lnet/minecraft/world/entity/LivingEntity;)Z", at=@At("HEAD"), cancellable=true)
    private void witchcraft$remove(LivingEntity user, CallbackInfoReturnable<Boolean> ci) {
        if (witchcraft$batch.active()) ci.setReturnValue(witchcraft$batch.take(witchcraft$self(), user));
    }
    @Inject(method="closeLid(Lnet/minecraft/world/entity/LivingEntity;)Z", at=@At("HEAD"), cancellable=true)
    private void witchcraft$ready(LivingEntity user, CallbackInfoReturnable<Boolean> ci) {
        if (witchcraft$batch.active() && !witchcraft$batch.ready(witchcraft$self())) {
            BarrelBatch.tip(user, "incomplete"); ci.setReturnValue(false);
        }
    }
    @Inject(method="tick(Lnet/minecraft/world/level/Level;)V", at=@At("HEAD"), cancellable=true)
    private void witchcraft$brew(Level level, CallbackInfo ci) {
        var self = witchcraft$self();
        if (!witchcraft$batch.active() || self.isBrewing()) return;
        ci.cancel();
        if (level.isClientSide || self.isOpen() || !witchcraft$batch.ready(self)) return;
        if ((witchcraft$workTime(level) + self.hashCode() % 97 + 97) % 97 != 0) return;
        ItemStack result = new ItemStack(TemporalContent.LIUJIN_VODKA.get(), BarrelBatch.OUTPUT);
        witchcraft$batch.wine().write(result);
        output.setStackInSlot(0, result);
        recipeId = ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft", "barrel/liujin_vodka");
        brewLevel = 1; brewTime = self.getBrewTimeForLevel();
        self.clearItemsAndFluid(); self.refresh();
    }
    @Inject(method="extractOneOutput(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;", at=@At("HEAD"), cancellable=true)
    private void witchcraft$bottle(ItemStack ignored, CallbackInfoReturnable<ItemStack> ci) {
        if (!output.getStackInSlot(0).is(TemporalContent.LIUJIN_VODKA.get())) return;
        ItemStack result = output.extractItem(0, 1, false);
        WineData data = WineData.read(result);
        if (data != null) data.aged(brewLevel).write(result);
        witchcraft$extracted = result.copy();
        witchcraft$self().refresh(); ci.setReturnValue(result);
    }
    @WrapOperation(method="placeBottleResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lcom/github/ysbbbbbb/kaleidoscopetavern/item/BottleBlockItem;)V", at=@At(value="INVOKE", target="Lcom/github/ysbbbbbb/kaleidoscopetavern/item/BottleBlockItem;getFilledStack(I)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack witchcraft$placedData(com.github.ysbbbbbb.kaleidoscopetavern.item.BottleBlockItem item, int quality, Operation<ItemStack> original) {
        if (item == TemporalContent.LIUJIN_VODKA.get() && !witchcraft$extracted.isEmpty()) {
            ItemStack result = witchcraft$extracted; witchcraft$extracted = ItemStack.EMPTY; return result;
        }
        return original.call(item, quality);
    }
    @Inject(method="placeBottleResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lcom/github/ysbbbbbb/kaleidoscopetavern/item/BottleBlockItem;)V", at=@At("TAIL"))
    private void witchcraft$syncBottle(Level level, net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.state.BlockState old,
            com.github.ysbbbbbb.kaleidoscopetavern.item.BottleBlockItem item, CallbackInfo ci) {
        if (item == TemporalContent.LIUJIN_VODKA.get() && level.getBlockEntity(pos) instanceof com.github.ysbbbbbb.kaleidoscopetavern.blockentity.brew.DrinkBlockEntity be) be.refresh();
    }
    @Inject(method="clearItemsAndFluid()V", at=@At("TAIL"))
    private void witchcraft$clear(CallbackInfo ci) { witchcraft$batch.clear(); }
    @Inject(method="tipBrewInfo(Lnet/minecraft/world/entity/LivingEntity;)V", at=@At("HEAD"), cancellable=true)
    private void witchcraft$info(LivingEntity user, CallbackInfo ci) {
        if (witchcraft$batch.active()) { witchcraft$batch.info(user); ci.cancel(); }
    }
    @Inject(method="saveAdditional(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V", at=@At("TAIL"))
    private void witchcraft$save(CompoundTag tag, HolderLookup.Provider provider, CallbackInfo ci) {
        tag.putInt("WitchcraftExtraTicks", witchcraft$extraTicks);
        if (witchcraft$batch.active()) tag.put("WitchcraftBatch", witchcraft$batch.save(provider));
    }
    @Inject(method="loadAdditional(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V", at=@At("TAIL"))
    private void witchcraft$load(CompoundTag tag, HolderLookup.Provider provider, CallbackInfo ci) {
        witchcraft$extraTicks = Math.clamp(tag.getInt("WitchcraftExtraTicks"), 0, 96);
        witchcraft$batch.load(provider, tag.getCompound("WitchcraftBatch"));
    }
    @Override public void witchcraft$dropContents() {
        var self = witchcraft$self(); witchcraft$batch.dropInputs(self);
        if (output.getStackInSlot(0).is(TemporalContent.LIUJIN_VODKA.get())) {
            ItemStack stack = output.extractItem(0, 16, false);
            WineData data = WineData.read(stack); if (data != null) data.aged(brewLevel).write(stack);
            BarrelBatch.give(self, null, stack);
        }
    }
}
