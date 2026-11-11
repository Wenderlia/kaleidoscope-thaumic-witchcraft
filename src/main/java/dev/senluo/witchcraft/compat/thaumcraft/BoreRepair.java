package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.ritual.RingBonus;
import dev.senluo.witchcraft.ritual.RitualPlane;
import dev.tc4port.thaumcraft.api.aspect.*;
import dev.tc4port.thaumcraft.api.node.VisNetworkApi;
import java.util.Arrays;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.util.INBTSerializable;

/** An additive paid service: never advances the bore's native repair clock or reserve. */
public final class BoreRepair implements INBTSerializable<CompoundTag> {
    private static final RingBonus RATE = RingBonus.multiplicative(1.6, 2, 2.5, 5);
    private static final TagKey<Item> TOOLS = ItemTags.create(ResourceLocation.fromNamespaceAndPath(
            KaleidoscopeWitchcraft.MOD_ID, "bore_warp_repair_tools"));
    private static final VisChannel[] CHANNELS = VisChannel.values();
    private static final RepairPrice PRICES = new RepairPrice(); // server thread; shared immutable graph expansions
    private final Ledger ledger = new Ledger();

    void resetClock() { ledger.resetClock(); }


    static void afterNative(ServerLevel level, BlockEntity bore, Container inventory, int mask) {
        ItemStack tool = inventory.getItem(1);
        if (!live(level, bore) || !tool.is(TOOLS) || !tool.isDamaged()) {
            BoreRings.state(bore).resetRepairClock();
            return;
        }
        // The ticker refreshed the ring mask; revalidate it again only at payment boundaries.
        if (mask == 0) {
            BoreRings.state(bore).resetRepairClock();
            return;
        }
        BoreRepair service = BoreRings.state(bore).account(bore).repair;
        int extra = (int) Math.round((RATE.resolve(1, mask) - 1) * 1000);
        int interval = (int) HostParameters.value("equipment/void_tools/repair_interval");
        int durability = (int) HostParameters.value("equipment/void_tools/repair_durability");
        int due = service.ledger.advance(extra, interval, durability, tool.getDamageValue());
        if (due == 0) return;
        // Requote the actual stack only when work is due. Native repair already ran this update.
        ItemStack quoted = tool.copy();
        var registry = AspectApi.registry();
        int[] price = PRICES.quote(registry, AspectQueryApi.item(quoted));
        if (price == null || !current(level, bore, inventory, tool, quoted, mask)) return;
        for (var channel : CHANNELS) {
            int i = channel.index();
            int request = service.ledger.missing(price, due, i);
            if (request == 0) continue;
            int received = VisNetworkApi.drain(level, bore.getBlockPos(), channel, request, VisAction.EXECUTE);
            if (received > 0) {
                service.ledger.deposit(i, received);
                bore.setChanged(); // Partial payments remain real saved CV, not a failed-work debt.
            }
            if (registry.generation() != AspectApi.registry().generation()
                    || !current(level, bore, inventory, tool, quoted, mask)) return;
        }
        int repaired = service.ledger.affordable(price, due);
        if (repaired == 0) return;
        service.ledger.pay(price, repaired);
        int before = tool.getDamageValue();
        try {
            tool.setDamageValue(before - repaired);
        } finally {
            int actual = Math.clamp(before - tool.getDamageValue(), 0, repaired);
            service.ledger.refund(price, repaired - actual);
            bore.setChanged();
            if (actual > 0 && live(level, bore)) {
                inventory.setChanged();
                level.sendBlockUpdated(bore.getBlockPos(), bore.getBlockState(), bore.getBlockState(), 2);
            }
        }
    }

    private static boolean live(ServerLevel level, BlockEntity bore) {
        return !bore.isRemoved() && bore.getLevel() == level && level.hasChunkAt(bore.getBlockPos())
                && level.getBlockEntity(bore.getBlockPos()) == bore;
    }

    private static boolean current(ServerLevel level, BlockEntity bore, Container inventory,
                                   ItemStack tool, ItemStack quoted, int mask) {
        return live(level, bore) && inventory.getItem(1) == tool && ItemStack.matches(tool, quoted)
                && tool.is(TOOLS) && RitualPlane.find(level, bore.getBlockPos())
                .map(p -> p.rings().completeMask()).orElse(0) == mask;
    }

    @Override public CompoundTag serializeNBT(HolderLookup.Provider lookup) {
        CompoundTag result = new CompoundTag();
        result.putIntArray("Centivis", ledger.balance.clone());
        return result;
    }

    @Override public void deserializeNBT(HolderLookup.Provider lookup, CompoundTag tag) {
        ledger.restore(tag.getIntArray("Centivis"));
    }

    /** Constant-size account; integer phase is transient and never catches up missed updates. */
    static final class Ledger {
        private final int[] balance = new int[6];
        private long phase;

        void resetClock() { phase = 0; }

        int advance(int extraPermille, int interval, int durability, int damage) {
            if (extraPermille <= 0 || interval <= 0 || durability <= 0 || damage <= 0) {
                resetClock(); return 0;
            }
            long period = (long) interval * 1000;
            phase += (long) extraPermille * durability;
            int due = (int) Math.min(damage, phase / period);
            phase %= period;
            return due;
        }

        int missing(int[] price, int count, int channel) {
            long target = Math.min(Integer.MAX_VALUE, (long) price[channel] * count);
            return (int) Math.max(0, target - balance[channel]);
        }

        void deposit(int channel, int received) {
            balance[channel] = (int) Math.min(Integer.MAX_VALUE, balance[channel] + (long) Math.max(0, received));
        }

        int affordable(int[] price, int limit) {
            for (int i = 0; i < balance.length; i++) if (price[i] > 0) limit = Math.min(limit, balance[i] / price[i]);
            return limit;
        }

        void pay(int[] price, int count) {
            if (count < 0 || affordable(price, count) != count) throw new IllegalArgumentException("Unfunded repair");
            for (int i = 0; i < balance.length; i++) balance[i] -= price[i] * count;
        }

        void refund(int[] price, int count) {
            for (int i = 0; i < balance.length; i++) deposit(i, price[i] * count);
        }

        void restore(int[] saved) {
            Arrays.fill(balance, 0);
            for (int i = 0; i < Math.min(balance.length, saved.length); i++) balance[i] = Math.max(0, saved[i]);
            resetClock();
        }
    }
}
