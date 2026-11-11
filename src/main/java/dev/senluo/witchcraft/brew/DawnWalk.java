package dev.senluo.witchcraft.brew;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.damagesource.DamageType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.INBTSerializable;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;

/** Dawn's finite budget lives with the player; only movement eligibility is synchronized. */
public final class DawnWalk {
    private static final DeferredRegister<AttachmentType<?>> TYPES = DeferredRegister.create(
            NeoForgeRegistries.ATTACHMENT_TYPES, KaleidoscopeWitchcraft.MOD_ID);
    private static final Supplier<AttachmentType<State>> STATE = TYPES.register("dawn_walk",
            () -> AttachmentType.serializable(State::new).build());
    private static final Supplier<AttachmentType<Integer>> QUALITY = TYPES.register("dawn_walk_quality",
            () -> AttachmentType.builder(() -> 0).sync(ByteBufCodecs.VAR_INT).build());
    private static final ResourceLocation STEP = id("dawn_step"), FALL = id("dawn_fall");
    private static final TagKey<Block> THORNS = TagKey.create(Registries.BLOCK, id("dew_thorns"));
    private static final TagKey<DamageType> THORN_DAMAGE = TagKey.create(Registries.DAMAGE_TYPE, id("dew_thorns"));
    private DawnWalk() {}
    private static ResourceLocation id(String name) { return ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID, name); }

    public static void register(IEventBus bus) {
        TYPES.register(bus);
        NeoForge.EVENT_BUS.addListener(DawnWalk::tick);
        NeoForge.EVENT_BUS.addListener(DawnWalk::spawn);
        NeoForge.EVENT_BUS.addListener(DawnWalk::trample);
        NeoForge.EVENT_BUS.addListener(DawnWalk::damage);
    }
    public static void drink(ServerPlayer player, int quality) {
        if (quality < 2 || quality > 6) return;
        State state = player.getData(STATE.get());
        if (state.unsupported != null) return;
        state.quality = Math.max(state.quality, quality);
        state.remaining = VintageEffects.align(player, AmberVintage.DAWN,
                Math.max(state.remaining, VintageEffects.duration(AmberVintage.DAWN, quality)));
        state.credits = Math.max(state.credits, quality < 4 ? 0 : (quality - 2) * 6);
        sync(player, state.quality);
        player.serverLevel().playSound(null, player.blockPosition(), SoundEvents.POINTED_DRIPSTONE_DRIP_WATER,
                SoundSource.PLAYERS, .5F, 1.2F);
    }
    private static int quality(Entity entity) {
        if (!entity.isAlive() || entity.isSpectator()) return 0;
        if (entity.level().isClientSide) return entity.hasData(QUALITY.get()) ? entity.getData(QUALITY.get()) : 0;
        if (!entity.hasData(STATE.get())) return 0;
        State state = entity.getData(STATE.get());
        return state.remaining > 0 && state.unsupported == null ? state.quality : 0;
    }
    public static boolean ignoresThorns(Entity entity, BlockState block) { return quality(entity) >= 2 && block.is(THORNS); }
    public static double minimumStepHeight(Entity entity) { return quality(entity) >= 4 ? 1.1 : 0; }
    private static void sync(ServerPlayer player, int quality) {
        if ((!player.hasData(QUALITY.get()) && quality != 0)
                || player.hasData(QUALITY.get()) && player.getData(QUALITY.get()) != quality)
            player.setData(QUALITY.get(), quality);
    }
    private static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !player.hasData(STATE.get())) return;
        State state = player.getData(STATE.get());
        if (state.unsupported != null) return;
        if (!player.isAlive() || state.remaining <= 0) {
            sync(player, 0);
            bonus(player.getAttribute(Attributes.STEP_HEIGHT), STEP, 0);
            bonus(player.getAttribute(Attributes.SAFE_FALL_DISTANCE), FALL, 0);
            player.removeData(STATE.get());
            return;
        }
        if (dev.senluo.witchcraft.ritual.OwnershipRitual.frozen(player)) return;
        int quality = quality(player);
        sync(player, quality);
        StepHeightFloor.apply(player.getAttribute(Attributes.STEP_HEIGHT), STEP, quality >= 4 ? 1.1 : 0);
        bonus(player.getAttribute(Attributes.SAFE_FALL_DISTANCE), FALL, quality >= 4 ? 2 : 0);
        if (state.cooldown > 0) state.cooldown--;
        // One local lookup per second, only after entering a different block. No chunk scans.
        if (quality >= 2 && player.tickCount % 20 == 0 && !player.isPassenger()) {
            BlockPos feet = player.blockPosition();
            if (feet.asLong() != state.lastPosition) {
                state.lastPosition = feet.asLong();
                player.serverLevel().getProfiler().push("witchcraft_dawn_cultivation");
                try { DawnCultivation.walk(player, feet, state); }
                finally { player.serverLevel().getProfiler().pop(); }
            }
        }
        state.remaining--;
    }
    private static void bonus(AttributeInstance attribute, ResourceLocation id, double amount) {
        if (attribute == null) return;
        var previous = attribute.getModifier(id);
        if (previous != null && Math.abs(previous.amount() - amount) < 1e-8 || previous == null && amount == 0) return;
        attribute.removeModifier(id);
        if (amount > 0) attribute.addTransientModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_VALUE));
    }
    private static void trample(BlockEvent.FarmlandTrampleEvent event) {
        if (quality(event.getEntity()) >= 2) event.setCanceled(true);
    }
    private static void damage(LivingIncomingDamageEvent event) {
        if (quality(event.getEntity()) >= 2 && event.getSource().is(THORN_DAMAGE)) event.setCanceled(true);
    }
    private static void spawn(MobSpawnEvent.PositionCheck event) {
        if (event.getSpawnType() != MobSpawnType.NATURAL
                || event.getEntity().getType().getCategory() != MobCategory.MONSTER) return;
        for (ServerPlayer player : event.getLevel().getLevel().players()) {
            int quality = quality(player);
            int radius = quality >= 6 ? 64 : quality >= 4 ? 48 : quality >= 2 ? 36 : 0;
            if (radius > 0 && player.distanceToSqr(event.getEntity()) < radius * radius) {
                event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
                return;
            }
        }
    }
    static final class State implements INBTSerializable<CompoundTag> {
        int remaining, quality, credits, cooldown;
        long lastPosition = Long.MIN_VALUE;
        CompoundTag unsupported;
        @Override public CompoundTag serializeNBT(HolderLookup.Provider provider) {
            if (unsupported != null) return unsupported.copy();
            CompoundTag tag = new CompoundTag();
            tag.putInt("Version", 1); tag.putInt("Remaining", remaining); tag.putInt("Quality", quality);
            tag.putInt("Credits", credits); tag.putInt("Cooldown", cooldown);
            return tag;
        }
        @Override public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
            remaining = quality = credits = cooldown = 0; lastPosition = Long.MIN_VALUE; unsupported = null;
            if (tag.getInt("Version") != 1) { unsupported = tag.copy(); return; }
            remaining = Math.max(tag.getInt("Remaining"), 0);
            quality = Math.clamp(tag.getInt("Quality"), 0, 6);
            credits = Math.clamp(tag.getInt("Credits"), 0, 24);
            cooldown = Math.clamp(tag.getInt("Cooldown"), 0, 100);
        }
    }
}
