package dev.senluo.witchcraft.ritual;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Machine update -> admitted native harvest -> native durability. No host-private method or player UUID. */
public final class BoreToolWear {
    private static final ThreadLocal<BoreToolWear> ACTIVE = new ThreadLocal<>();
    private final BlockEntity owner;
    private final Container inventory;
    private final int slot;
    private final String actorName;
    private final IntSupplier divisor;
    private ServerLevel harvestLevel;
    private BlockPos target;
    private BooleanSupplier permitted, startPermitted;
    private ServerPlayer miner;
    private ItemStack miningTool;
    private boolean directLoss, itemTick, running;

    public BoreToolWear(BlockEntity owner, Container inventory, int slot, String actorName, IntSupplier divisor) {
        this.owner = owner;
        this.inventory = inventory;
        this.slot = slot;
        this.actorName = actorName;
        this.divisor = divisor;
    }

    /** Reuse one scope per machine; nested machines restore their caller even after failure. */
    public void during(Runnable action) {
        BoreToolWear previous = ACTIVE.get();
        // The host's busy guard owns same-machine reentry; do not overwrite its live harvest.
        if (running) { action.run(); return; }
        running = true;
        ACTIVE.set(this);
        directLoss = true;
        try { action.run(); }
        finally {
            harvestLevel = null;
            target = null;
            permitted = null;
            startPermitted = null;
            miner = null;
            miningTool = null;
            directLoss = itemTick = running = false;
            if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
        }
    }

    /** The event authorizes context only. Its final cancellation is read at native execution. */
    public static void expectHarvest(Object owner, ServerLevel level, BlockPos target, BooleanSupplier permitted) {
        BoreToolWear scope = ACTIVE.get();
        if (scope == null || scope.owner != owner) return;
        scope.directLoss = false;
        scope.harvestLevel = level;
        scope.target = target;
        scope.permitted = permitted;
        scope.startPermitted = null;
        scope.miner = null;
        scope.miningTool = null;
    }

    /** Scanning the next column closes the previous target's direct, vanished-block fee. */
    public static void nextTarget(Object owner) {
        BoreToolWear scope = ACTIVE.get();
        if (scope == null || scope.owner != owner) return;
        scope.directLoss = false;
        scope.harvestLevel = null;
        scope.target = null;
        scope.permitted = null;
        scope.startPermitted = null;
        scope.miner = null;
        scope.miningTool = null;
    }

    public static boolean active() { return ACTIVE.get() != null; }

    /** Native LeftClickBlock precedes enchantment hit, block attack and destroyBlock callbacks. */
    public static void startHarvest(ServerPlayer player, BlockPos pos, BooleanSupplier startPermitted) {
        BoreToolWear scope = ACTIVE.get();
        if (scope == null || scope.itemTick || scope.owner.isRemoved() || !scope.actor(player)
                || scope.miner != null || scope.permitted == null || player.serverLevel() != scope.harvestLevel
                || !pos.equals(scope.target) || !scope.permitted.getAsBoolean()) return;
        // The copy is now in the real actor's hand. Never mistake Harvest.tool()'s display copy for it.
        scope.miner = player;
        scope.miningTool = player.getMainHandItem();
        scope.startPermitted = startPermitted;
    }

    /** Inventory/self-maintenance callbacks are not mining, even inside the same machine update. */
    public static void inventoryTick(Runnable action) {
        BoreToolWear scope = ACTIVE.get();
        if (scope == null) { action.run(); return; }
        boolean previous = scope.itemTick;
        scope.itemTick = true;
        try { action.run(); } finally { scope.itemTick = previous; }
    }

    private boolean actor(ServerPlayer player) {
        return player.isFakePlayer() && actorName.equals(player.getGameProfile().getName());
    }

    public static int apply(LivingEntity bearer, ItemStack stack, int loss, RandomSource random) {
        BoreToolWear scope = ACTIVE.get();
        if (scope == null || loss <= 0 || scope.itemTick || scope.owner.isRemoved()
                || !(bearer instanceof ServerPlayer player) || !scope.actor(player)) return loss;
        boolean nativeTool = scope.miner == player && scope.permitted.getAsBoolean() && scope.startPermitted.getAsBoolean()
                && (stack == scope.miningTool || stack == player.getMainHandItem());
        // A vanished target skips Harvest and destroyBlock; TC charges its installed pick directly.
        boolean vanishedTool = scope.miner == null && scope.directLoss && stack == scope.inventory.getItem(scope.slot);
        if (!nativeTool && !vanishedTool) return loss;
        int divisor = scope.divisor.getAsInt();
        if (divisor <= 1) return loss;
        int remainder = loss % divisor;
        return loss / divisor + (remainder > 0 && random.nextInt(divisor) < remainder ? 1 : 0);
    }
}
