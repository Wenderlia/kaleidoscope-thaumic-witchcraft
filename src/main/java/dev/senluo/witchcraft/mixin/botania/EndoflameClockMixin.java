package dev.senluo.witchcraft.mixin.botania;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vazkii.botania.common.block.block_entity.flower.generating.EndoflameBlockEntity;

/** A two-step production phase follows actual flower updates, including accelerated work. */
@Mixin(value = EndoflameBlockEntity.class, remap = false)
public abstract class EndoflameClockMixin {
    @Unique private static final String WITCHCRAFT_PHASE = "WitchcraftEndoflamePhase";
    // Seed once from the native position/time phase, then count every actual work step.
    @Unique private int witchcraft$phase = -1;
    @Unique private long witchcraft$dirtyTick = Long.MIN_VALUE;

    @WrapMethod(method = "tickFlower()V")
    private void witchcraft$workStep(Operation<Void> original) {
        var flower = (EndoflameBlockEntity) (Object) this;
        boolean server = flower.getLevel() instanceof ServerLevel;
        if (server && witchcraft$phase < 0) witchcraft$phase = flower.shouldUpdateThisTick() ? 0 : 1;
        original.call();
        if (server && !flower.isRemoved()) {
            witchcraft$phase ^= 1;
            long tick = flower.getLevel().getGameTime();
            if (witchcraft$dirtyTick != tick) {
                witchcraft$dirtyTick = tick;
                flower.setChanged();
            }
        }
    }

    @WrapOperation(method = "tickFlower()V", at = @At(value = "INVOKE",
            target = "Lvazkii/botania/common/block/block_entity/flower/generating/EndoflameBlockEntity;shouldUpdateThisTick()Z"))
    private boolean witchcraft$productionPhase(EndoflameBlockEntity flower, Operation<Boolean> original) {
        if (!(flower.getLevel() instanceof ServerLevel)) return original.call(flower);
        if (witchcraft$phase < 0) witchcraft$phase = original.call(flower) ? 0 : 1;
        return witchcraft$phase == 0;
    }

    @Inject(method = "saveAdditional(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V", at = @At("TAIL"))
    private void witchcraft$savePhase(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        if (witchcraft$phase >= 0) tag.putByte(WITCHCRAFT_PHASE, (byte) witchcraft$phase);
    }

    @Inject(method = "loadAdditional(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V", at = @At("TAIL"))
    private void witchcraft$loadPhase(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        int phase = tag.contains(WITCHCRAFT_PHASE, net.minecraft.nbt.Tag.TAG_BYTE) ? tag.getByte(WITCHCRAFT_PHASE) : -1;
        witchcraft$phase = phase == 0 || phase == 1 ? phase : -1;
        witchcraft$dirtyTick = Long.MIN_VALUE;
    }
}
