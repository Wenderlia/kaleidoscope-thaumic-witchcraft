package dev.senluo.witchcraft.ritual;

import com.mojang.authlib.GameProfile;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.time.TemporalText;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.PlayerMainInvWrapper;

@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID)
public final class TaglockSampling {
    private TaglockSampling() {}

    public static boolean canSampleSelf(Player player, InteractionHand hand) {
        if (player.isSpectator() || !player.isAlive() || !player.isShiftKeyDown()
                || !TaglockSamplingRules.emptyVial(player.getItemInHand(hand))) return false;
        // Item.use is a fallback after target interaction. Also reject failed target clicks.
        if (player.pick(player.blockInteractionRange(), 1.0F, false).getType()
                != net.minecraft.world.phys.HitResult.Type.MISS) return false;
        var eye = player.getEyePosition();
        var delta = player.getViewVector(1.0F).scale(player.entityInteractionRange());
        var end = eye.add(delta);
        return player.level().getEntities(player, player.getBoundingBox().expandTowards(delta).inflate(1),
                entity -> entity.isPickable() && !entity.isSpectator()).stream().noneMatch(entity ->
                entity.getBoundingBox().inflate(entity.getPickRadius()).contains(eye)
                || entity.getBoundingBox().inflate(entity.getPickRadius()).clip(eye, end).isPresent());
    }

    public static void fromSelf(ServerPlayer player, InteractionHand hand) {
        if (!canSampleSelf(player, hand)) return;
        boolean paid = player.isCreative() || player.hurt(player.damageSources().generic(), 1.0F);
        if (paid && player.isAlive()) bind(player, hand, player);
    }

    // Intercept before villager trading/animal interaction; a successful client result ends
    // the normal pipeline here, so the general event is not a second sampling action.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void specific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!TaglockSamplingRules.canAttempt(event.getEntity(), event.getHand(), event.getTarget())) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
        if (event.getEntity() instanceof ServerPlayer player)
            fromEntity(player, event.getHand(), event.getTarget());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void general(PlayerInteractEvent.EntityInteract event) {
        if (!TaglockSamplingRules.canAttempt(event.getEntity(), event.getHand(), event.getTarget())) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
        if (event.getEntity() instanceof ServerPlayer player)
            fromEntity(player, event.getHand(), event.getTarget());
    }

    private static void fromEntity(ServerPlayer player, InteractionHand hand, Entity target) {
        if (!TaglockSamplingRules.emptyVial(player.getItemInHand(hand)) || restricted(player, target)) return;
        if (target instanceof Player && target != player
                && player.getRandom().nextDouble() >= TaglockSamplingRules.playerChance(player, (LivingEntity) target)) {
            message(player, "failed");
            ((Player) target).displayClientMessage(TemporalText.color(Component.translatable(
                    "text.kaleidoscope_witchcraft.taglock.noticed", player.getDisplayName()), TemporalText.PENDING), false);
            return;
        }
        bind(player, hand, target);
    }

    public static boolean isBed(Level level, BlockPos pos, Player player) {
        return level.hasChunkAt(pos) && level.getBlockState(pos).isBed(level, pos, player);
    }

    public static void fromBed(ServerPlayer player, InteractionHand hand, BlockPos pos) {
        ServerLevel level = player.serverLevel();
        if (!TaglockSamplingRules.emptyVial(player.getItemInHand(hand)) || !isBed(level, pos, player)) return;
        if (player.isShiftKeyDown()) {
            bind(player, hand, player);
            return;
        }
        TaglockBeds beds = TaglockBeds.get(level.getServer());
        if (!beds.ready()) {
            message(player, beds.failed() ? "bed_unavailable" : "bed_loading");
            return;
        }
        var candidates = beds.owners(level, pos);
        if (candidates.isEmpty()) {
            message(player, "empty_bed");
            return;
        }
        TaglockBeds.Owner target = candidates.getFirst();
        if (!restricted(player, target.profile())) bind(player, hand, target.sample());
    }

    private static boolean restricted(ServerPlayer collector, Entity target) {
        return target instanceof ServerPlayer other && restricted(collector, other.getGameProfile());
    }

    private static boolean restricted(ServerPlayer collector, GameProfile target) {
        if (collector.getUUID().equals(target.getId())) return false;
        var server = collector.serverLevel().getServer();
        boolean restricted = (RitualConfig.RESTRICT_NON_PVP.get() && !server.isPvpAllowed())
                || (RitualConfig.RESTRICT_STAFF.get() && server.getPlayerList().isOp(target));
        if (restricted) message(collector, "restricted");
        return restricted;
    }

    private static void bind(ServerPlayer player, InteractionHand hand, Entity target) {
        bind(player, hand, TaglockSample.from(target));
        if (target instanceof Mob mob) mob.setPersistenceRequired();
    }

    private static void bind(ServerPlayer player, InteractionHand hand, TaglockSample sample) {
        ItemStack held = player.getItemInHand(hand);
        if (!TaglockSamplingRules.emptyVial(held)) return;
        if (held.getCount() == 1) held.set(RitualContent.TAGLOCK_SAMPLE.get(), sample);
        else {
            ItemStack result = held.copyWithCount(1);
            result.set(RitualContent.TAGLOCK_SAMPLE.get(), sample);
            held.shrink(1);
            // The handler respects component equality and never discards leftovers in creative.
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(new PlayerMainInvWrapper(player.getInventory()), result, false);
            if (!remainder.isEmpty()) player.drop(remainder, false);
        }
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        player.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.5F,
                0.4F / (player.getRandom().nextFloat() * 0.4F + 0.8F));
        player.displayClientMessage(TemporalText.color(Component.translatable(
                "text.kaleidoscope_witchcraft.taglock.collected", sample.displayName()), TemporalText.READY), true);
    }

    private static void message(ServerPlayer player, String key) {
        player.displayClientMessage(TemporalText.color(Component.translatable(
                "text.kaleidoscope_witchcraft.taglock." + key), TemporalText.PENDING), true);
    }

}
