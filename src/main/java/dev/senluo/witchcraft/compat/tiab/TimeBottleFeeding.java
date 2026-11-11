package dev.senluo.witchcraft.compat.tiab;

import com.google.common.collect.MapMaker;
import dev.senluo.witchcraft.time.BottleFeedingRates;
import dev.senluo.witchcraft.time.DevelopmentTime;
import dev.senluo.witchcraft.time.FruitEntity;
import dev.senluo.witchcraft.time.FruitBlock;
import dev.senluo.witchcraft.time.LivingBlock;
import dev.senluo.witchcraft.time.LivingEntity;
import dev.senluo.witchcraft.time.Tissue;
import dev.senluo.witchcraft.time.TemporalWorld;
import dev.senluo.witchcraft.time.TemporalText;
import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.mangorage.tiab.common.api.ICommonTimeInABottleAPI;
import org.mangorage.tiab.common.api.impl.ITiabItem;
import org.mangorage.tiab.common.misc.CommonHelper;

/** Loaded only when TIAB is installed. Uses normal item holding, including release packets. */
public final class TimeBottleFeeding {
    private static final int USE_DURATION = 72_000;
    // Entity.equals compares only the network id, which is shared by client/server counterparts.
    // MapMaker weak keys compare object identity and keep the two logical sides independent.
    private static final Map<Player, Session> SESSIONS = new MapMaker().weakKeys().makeMap();

    private static final class Session {
        final WeakReference<BlockEntity> target;
        final ItemStack bottle;
        final InteractionHand hand;
        final int selectedSlot;
        final int ticksPerBottleSecond;
        long lastTick = Long.MIN_VALUE;
        int heldTicks;
        long pendingMilliTu;
        long transferredMilliTu;
        long spentTicks;

        Session(Player player, BlockEntity target, InteractionHand hand) {
            this.target = new WeakReference<>(target);
            this.bottle = player.getItemInHand(hand);
            this.hand = hand;
            this.selectedSlot = player.getInventory().selected;
            this.ticksPerBottleSecond = ICommonTimeInABottleAPI.COMMON_API.get().getConfig().TICKS_CONST();
        }
    }

    private TimeBottleFeeding() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, TimeBottleFeeding::interact);
        NeoForge.EVENT_BUS.addListener(TimeBottleFeeding::start);
        NeoForge.EVENT_BUS.addListener(TimeBottleFeeding::tick);
        NeoForge.EVENT_BUS.addListener((LivingEntityUseItemEvent.Stop event) -> finish(event.getEntity(), null));
        NeoForge.EVENT_BUS.addListener((LivingEntityUseItemEvent.Finish event) -> finish(event.getEntity(), null));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> SESSIONS.remove(event.getEntity()));
        NeoForge.EVENT_BUS.addListener((PlayerTickEvent.Post event) -> {
            if (!event.getEntity().isUsingItem()) finish(event.getEntity(), null);
        });
    }

    private static void interact(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (!(event.getItemStack().getItem() instanceof ITiabItem)) return;
        BlockEntity target = event.getLevel().getBlockEntity(event.getPos());
        if (!(target instanceof LivingEntity) && !(target instanceof FruitEntity)) return;
        if (!player.isShiftKeyDown()) {
            // Development state is authoritative on the server; client copies may be stale.
            if (!event.getLevel().isClientSide) {
                if (player.isSpectator() || !player.mayBuild() || !player.canInteractWithBlock(event.getPos(), 0)
                        || !canFeed(player, target)) {
                    event.setCanceled(true);
                    event.setCancellationResult(InteractionResult.FAIL);
                } else if (target instanceof FruitEntity fruit && !fruit.canGrow()) {
                    event.setCanceled(true);
                    event.setCancellationResult(InteractionResult.CONSUME);
                } else if (target instanceof LivingEntity living && !DevelopmentTime.hasRemaining(living)
                        && !TemporalWorld.get((ServerLevel) event.getLevel()).canGrowRoots(living)) {
                    event.setCanceled(true);
                    event.setCancellationResult(InteractionResult.CONSUME);
                    LivingBlock.tell(player, "no_growth_time");
                }
            }
            return; // TIAB handles its own accelerator entity, multiplier and bottle cost.
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
        if (player.isSpectator() || !player.mayBuild() || !player.canInteractWithBlock(event.getPos(), 0)
                || !event.getLevel().isClientSide && !canFeed(player, target)) {
            event.setCancellationResult(InteractionResult.FAIL);
            return;
        }
        if (player.isUsingItem()) return;
        Session session = new Session(player, target, event.getHand());
        SESSIONS.put(player, session);
        player.startUsingItem(event.getHand());
        if (!player.isUsingItem()) { SESSIONS.remove(player); return; }
        if (!event.getLevel().isClientSide) {
            if (transfer(session, target, 1) == 0 || unavailable(session, target) != null) {
                finish(player, unavailable(session, target));
                player.stopUsingItem();
            } else feedback(player, session, target, null);
        }
    }

    private static void start(LivingEntityUseItemEvent.Start event) {
        if (event.getEntity() instanceof Player player) {
            Session session = SESSIONS.get(player);
            if (session == null) return;
            if (session.hand == event.getHand() && event.getItem().getItem() instanceof ITiabItem
                    && (player.level().isClientSide || event.getItem() == session.bottle)) event.setDuration(USE_DURATION);
            else finish(player, null);
        }
    }

    private static boolean canFeed(Player player, BlockEntity target) {
        return target instanceof LivingEntity living
                ? LivingBlock.canFeed(player.level(), target.getBlockPos(), player, living)
                : target instanceof FruitEntity fruit && FruitBlock.canUse(player.level(), target.getBlockPos(), player, fruit)
                        && fruit.core() != null && LivingBlock.canFeed(player.level(), fruit.core().getBlockPos(), player, fruit.core());
    }

    private static boolean valid(Player player, Session session, BlockEntity target) {
        if (target == null || target.isRemoved() || target.getLevel() != player.level() || !player.isAlive()
                || player.isSpectator() || !player.mayBuild()
                || !player.isShiftKeyDown() || player.getUsedItemHand() != session.hand
                || session.hand == InteractionHand.MAIN_HAND && player.getInventory().selected != session.selectedSlot
                || !(player.getItemInHand(session.hand).getItem() instanceof ITiabItem)
                || !player.level().hasChunkAt(target.getBlockPos())
                || !player.canInteractWithBlock(target.getBlockPos(), 0)) return false;
        if (!player.level().isClientSide && (player.getItemInHand(session.hand) != session.bottle
                || !canFeed(player, target))) return false;
        var eye = player.getEyePosition();
        var end = eye.add(player.getLookAngle().scale(player.blockInteractionRange()));
        var hit = player.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target.getBlockPos());
    }

    private static void tick(LivingEntityUseItemEvent.Tick event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Session session = SESSIONS.get(player);
        if (session == null) return;
        BlockEntity target = session.target.get();
        if (!valid(player, session, target)) {
            finish(player, null);
            player.stopUsingItem();
            event.setCanceled(true);
            return;
        }
        if (player.level().isClientSide) return;
        long tick = player.level().getGameTime();
        if (session.lastTick == tick) return;
        session.lastTick = tick;
        session.pendingMilliTu += BottleFeedingRates.holdMilliTuPerTick(++session.heldTicks);
        int requested = (int) (session.pendingMilliTu / Tissue.TU);
        if (requested == 0) return;
        long given = transfer(session, target, requested);
        session.pendingMilliTu -= given;
        if (given < (long) requested * Tissue.TU || unavailable(session, target) != null) {
            finish(player, unavailable(session, target));
            player.stopUsingItem();
            event.setCanceled(true);
        } else feedback(player, session, target, null);
    }

    private static LivingEntity storageTarget(BlockEntity target) {
        return target instanceof LivingEntity living ? living : target instanceof FruitEntity fruit ? fruit.core() : null;
    }

    private static long room(BlockEntity target) {
        LivingEntity living = storageTarget(target);
        return living == null ? 0 : living.tissue.room();
    }

    private static long stock(BlockEntity target) {
        LivingEntity living = storageTarget(target);
        return living == null ? 0 : living.tissue.stored();
    }

    private static String unavailable(Session session, BlockEntity target) {
        long space = room(target);
        if (space < Tissue.TU) return "feed_full";
        var item = (ITiabItem) session.bottle.getItem();
        return item.getStoredComponent(session.bottle).stored()
                < BottleFeedingRates.costMilliTuTicks(stock(target), Math.min(space, Tissue.TU), session.ticksPerBottleSecond) ? "feed_empty" : null;
    }

    private static long transfer(Session session, BlockEntity target, int requestedTu) {
        var api = ICommonTimeInABottleAPI.COMMON_API.get();
        var stored = ((ITiabItem) session.bottle.getItem()).getStoredComponent(session.bottle);
        long quoteStock = stock(target);
        LivingEntity living = storageTarget(target);
        if (living == null) return 0;
        int wholeTu = (int) Math.min(requestedTu, room(target) / Tissue.TU);
        long amount = (long) BottleFeedingRates.affordableTu(quoteStock, wholeTu, session.ticksPerBottleSecond, stored.stored()) * Tissue.TU;
        if (amount == 0) return 0;
        int cost = Math.toIntExact(BottleFeedingRates.costMilliTuTicks(quoteStock, amount, session.ticksPerBottleSecond));
        var updated = api.createStoredTimeComponent(stored.stored() - cost, stored.total());
        var lore = new ItemLore(List.of(CommonHelper.getStoredTimeTranslated(updated.stored()), CommonHelper.getTotalTimeTranslated(updated.total())));
        if (!living.tissue.credit(amount)) return 0;
        living.received(amount, false);
        living.setChanged();
        session.bottle.set(api.getRegistration().getStoredTime(), updated);
        session.bottle.set(DataComponents.LORE, lore);
        session.transferredMilliTu += amount;
        session.spentTicks += cost;
        return amount;
    }

    private static void finish(net.minecraft.world.entity.LivingEntity entity, String status) {
        if (!(entity instanceof Player player)) return;
        Session session = SESSIONS.remove(player);
        if (session == null || player.level().isClientSide) return;
        BlockEntity target = session.target.get();
        if (target != null) feedback(player, session, target, status);
    }

    private static void feedback(Player player, Session session, BlockEntity target, String status) {
        // Direct fruit feeding is deliberately silent; root feeding retains its existing feedback.
        if (!(target instanceof LivingEntity living)) return;
        String seconds = BigDecimal.valueOf(session.spentTicks).divide(BigDecimal.valueOf(session.ticksPerBottleSecond), 2, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
        var text = TemporalText.message("feed_progress", TemporalText.amount(session.transferredMilliTu, true),
                TemporalText.number(seconds, TemporalText.TIME), TemporalText.amount(living.tissue.stored(), true),
                TemporalText.capacity(living.tissue.capacity(), true));
        if (status != null) text.append(TemporalText.notice(status));
        player.displayClientMessage(text, true);
    }
}
