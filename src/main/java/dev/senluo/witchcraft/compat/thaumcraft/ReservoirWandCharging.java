package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.reservoir.*;
import dev.tc4port.thaumcraft.api.aspect.*;
import dev.tc4port.thaumcraft.api.vis.VisTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import java.util.*;
import java.util.function.Predicate;

/** Installed converter policy. Wand inspection and mutation remain owned by TC's public API. */
final class ReservoirWandCharging {
    private static final long MICROVIS_PER_CENTIVIS=10_000;
    // Mixed grades keep separate rule caches; alternating slots must not evict each other.
    private final Map<ConversionKey,ReservoirConversion> conversions=new HashMap<>();
    private record ConversionKey(ReservoirProfile host,ReservoirTransducerItem.Grade grade) {}
    private final Map<ServerPlayer,Schedule[]> schedules=new WeakHashMap<>();
    private static final class Schedule {
        ItemStack bag; ReservoirData.View.Slot stored; long seen,source,output;
    }
    private record Source(int cell, String aspect, Map<ResourceLocation, Long> quote) {}

    private static final class Target {
        final int slot;
        final ItemStack stack;
        final Set<ResourceLocation> declined = new HashSet<>();
        Map<ResourceLocation, Integer> room;
        long received;

        Target(int slot, ItemStack stack, Map<ResourceLocation, Integer> room) {
            this.slot = slot;
            this.stack = stack;
            this.room = room;
        }
    }

    private static final class Delivery {
        final Map<AspectId, Long> moved = new HashMap<>();
        int cursor = -1;
        ResourceLocation after;

        void record(Target target, ReservoirAllocation.Share share, Map<AspectId, Integer> actual) {
            ResourceLocation last = null;
            for (var id : share.order) {
                var aspect = AspectId.of(id);
                int amount = actual.getOrDefault(aspect, 0);
                if (amount <= 0) continue;
                target.received += amount;
                moved.merge(aspect, (long)amount, Long::sum);
                last = id;
            }
            if (last == null) return;
            int tailAmount = share.tail == null ? 0 : share.request.getOrDefault(share.tail, 0);
            boolean receivedTail = tailAmount > 0 && actual.getOrDefault(AspectId.of(share.tail), 0) >= tailAmount;
            if (cursor < 0 || receivedTail) {
                cursor = target.slot;
                after = share.last != null && actual.getOrDefault(AspectId.of(share.last), 0) > 0 ? share.last : last;
            }
        }
    }

    void charge(ServerPlayer player, ItemStack bag, int slot, ItemStack device) {
        var stored = ReservoirData.view(bag).stored(slot, player.registryAccess());
        if (stored.matches(device)) charge(player, bag, slot, stored);
    }

    void charge(ServerPlayer player, ItemStack bag, int slot, ReservoirData.View.Slot stored) {
        int index = slot - ReservoirData.FIRST_PLUGIN_SLOT;
        if (index < 0 || index >= ReservoirProfile.of(bag).plugins || !ReservoirData.supported(bag)
                || !(stored.item() instanceof ReservoirTransducerItem item) || !stored.singleSupported()) return;
        long now = player.serverLevel().getGameTime();
        Schedule[] clocks = schedules.computeIfAbsent(player, p -> new Schedule[ReservoirProfile.SILVERWOOD.plugins]);
        Schedule clock = clocks[index];
        if (clock == null) clocks[index] = clock = new Schedule();
        if (clock.bag != bag || clock.stored != stored || now < clock.seen || now - clock.seen > 1) {
            var rate = item.throughput();
            clock.bag = bag;
            clock.stored = stored;
            clock.source = now + rate.sourceTicks();
            clock.output = now + rate.deliveryTicks();
        }
        clock.seen = now;
        if (now < clock.source && now < clock.output) return;
        ItemStack device = stored.copy();
        try {
            chargeOnce(player, bag, slot, device, clock, item.throughput(), now);
            vent(player, bag, slot, device);
        } finally {
            var current = ReservoirData.view(bag).stored(slot, player.registryAccess());
            clock.stored = current.matches(device) ? current : null;
        }
    }

    private void chargeOnce(ServerPlayer player,ItemStack bag,int slot,ItemStack device,Schedule clock,
                            ReservoirTransducerItem.Throughput rate,long now) {
        boolean source=now>=clock.source,output=now>=clock.output;
        if(source)clock.source=now+rate.sourceTicks();
        if(output)clock.output=now+rate.deliveryTicks();
        if(!installed(player,bag,slot,device))return;
        // Flush paid balances first, making space before conversion. Delivery is not rate-limited.
        // A conversion pulse also flushes, even when configured clocks do not align.
        boolean changed=flush(player,bag,slot,device,now);
        if(source && installed(player,bag,slot,device)
                && convert(player,bag,slot,device)){
            // Persist the paid batch before calling the host, including when output is not due.
            if(!ReservoirData.view(bag).stored(slot,player.registryAccess()).matches(device))
                ReservoirData.slot(bag,slot,device,player.registryAccess());
            // Refresh room after the first transfer, then deliver this batch in the same game tick.
            try{
                changed|=flush(player,bag,slot,device,now);
            }finally{
                if(installed(player,bag,slot,device)){
                    retainBuffer(device,rate.bufferMicrovis());
                    ReservoirData.slot(bag,slot,device,player.registryAccess());
                    player.getInventory().setChanged();
                }
            }
        }
        if(changed && installed(player,bag,slot,device)){
            var rules=ReservoirProfile.of(bag).conversion(((ReservoirTransducerItem)device.getItem()).rules());
            if(rules.emitsGas()){
                ReservoirEmission.charged(device,rules.ventEvery());
                ReservoirData.slot(bag,slot,device,player.registryAccess());
            }
        }
    }

    private static boolean flush(ServerPlayer player, ItemStack bag, int slot, ItemStack device, long now) {
        if (!installed(player, bag, slot, device)) return false;
        var stock = wholeBalances(device);
        if (stock.isEmpty()) return false;
        var delivery = new Delivery();
        var data = ReservoirData.view(device);
        ResourceLocation after = ResourceLocation.tryParse(data.string("output_after"));
        visitTiers(player, data.integer("target_cursor"),
                room -> room.entrySet().stream().anyMatch(e -> e.getValue() > 0 && stock.containsKey(e.getKey())),
                tier -> deliver(player, bag, slot, device, tier, after, delivery));
        if (delivery.moved.isEmpty()) return false;
        if (!installed(player, bag, slot, device)) return true;
        long red = 0, green = 0, blue = 0, total = 0;
        for (var entry : delivery.moved.entrySet()) {
            long amount = entry.getValue();
            int color = AspectApi.registry().get(entry.getKey()).map(AspectDefinition::color).orElse(0xFFFFFF);
            total += amount;
            red += (color >> 16 & 255) * amount;
            green += (color >> 8 & 255) * amount;
            blue += (color & 255) * amount;
        }
        int color = (int)(red / total) << 16 | (int)(green / total) << 8 | (int)(blue / total);
        ReservoirData.edit(device, tag -> {
            tag.putInt("target_cursor", (delivery.cursor + 1) % Inventory.INVENTORY_SIZE);
            tag.putString("output_after", delivery.after.toString());
            tag.putInt("light_color", color);
            tag.putLong("light_tick", now);
        });
        ReservoirData.slot(bag, slot, device, player.registryAccess());
        player.getInventory().setChanged();
        return true;
    }

    private static Map<ResourceLocation, Long> wholeBalances(ItemStack device) {
        var stock = ReservoirData.balances(device);
        stock.replaceAll((id, amount) -> amount / MICROVIS_PER_CENTIVIS);
        stock.values().removeIf(amount -> amount == 0);
        return stock;
    }

    private boolean convert(ServerPlayer player, ItemStack bag, int slot, ItemStack device) {
        List<Source> sources = sources(bag, device);
        if (sources.isEmpty()) return false;
        var balances = ReservoirData.balances(device);
        var producible = new HashSet<ResourceLocation>();
        for (Source source : sources) producible.addAll(source.quote.keySet());
        return visitTiers(player, ReservoirData.view(device).integer("target_cursor"),
                room -> room.entrySet().stream().anyMatch(e -> e.getValue() > 0 && producible.contains(e.getKey())),
                tier -> installed(player, bag, slot, device) && convert(bag, device, tier, sources, balances));
    }

    private List<Source> sources(ItemStack bag,ItemStack device){
        var item=(ReservoirTransducerItem)device.getItem();var host=ReservoirProfile.of(bag);var rules=host.conversion(item.rules());
        var conversion=conversions.computeIfAbsent(new ConversionKey(host,item.grade()),unused->new ReservoirConversion());
        var registry = AspectApi.registry();
        conversion.configure(VisTypes.item(device), rules.primalMicrovis(), rules.retainedBasisPoints());
        int cursor=Math.floorMod(ReservoirData.view(device).integer("source_cursor"),ReservoirData.cells(bag));
        var data=ReservoirData.read(bag);List<Source> result=new ArrayList<>(ReservoirData.cells(bag));
        for(int n=0;n<ReservoirData.cells(bag);n++){
            int cell=(cursor+n)%ReservoirData.cells(bag);var stored=data.getCompound("cell_"+cell);
            if(stored.getInt("amount")<=0)continue;
            String aspect=stored.getString("aspect");var location=ResourceLocation.tryParse(aspect);
            if(location!=null)conversion.quote(registry, AspectId.of(location)).filter(q -> !q.isEmpty()).ifPresent(q -> result.add(new Source(cell,aspect,q)));
        }
        return result;
    }

    private static boolean visitTiers(ServerPlayer player, int cursor, Predicate<Map<ResourceLocation, Integer>> useful,
                                      Predicate<List<Target>> attempt) {
        var inventory = player.getInventory();
        Set<ItemStack> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Target main = inspect(inventory, inventory.selected, seen, useful);
        if (main != null && attempt.test(List.of(main))) return true;
        Target off = inspect(inventory, Inventory.SLOT_OFFHAND, seen, useful);
        if (off != null && attempt.test(List.of(off))) return true;
        var tier = new ArrayList<Target>();
        int start = Math.floorMod(cursor, Inventory.INVENTORY_SIZE);
        for (int n = 0; n < Inventory.INVENTORY_SIZE; n++) {
            int slot = (start + n) % Inventory.INVENTORY_SIZE;
            if (slot == inventory.selected) continue;
            Target target = inspect(inventory, slot, seen, useful);
            if (target != null) tier.add(target);
        }
        return !tier.isEmpty() && attempt.test(tier);
    }

    private static Target inspect(Inventory inventory, int slot, Set<ItemStack> seen, Predicate<Map<ResourceLocation, Integer>> useful) {
        if (slot < 0 || slot >= inventory.getContainerSize()) return null;
        ItemStack stack = inventory.getItem(slot);
        if (stack.isEmpty() || !seen.add(stack)) return null;
        var room = room(stack);
        return useful.test(room) ? new Target(slot, stack, room) : null;
    }

    private static Map<ResourceLocation, Integer> room(ItemStack stack) {
        var storage = WandCharging.storage(stack);
        if (storage == null) return Map.of();
        var view = storage.snapshot();
        var room = new LinkedHashMap<ResourceLocation, Integer>();
        view.capacityCentivis().forEach((id, capacity) -> {
            int remaining = capacity - view.currentCentivis().getOrDefault(id, 0);
            room.put(id.value(), Math.max(0, remaining));
        });
        return room;
    }

    private static boolean convert(ItemStack bag, ItemStack device, List<Target> targets, List<Source> sources,
                                   Map<ResourceLocation, Long> balances) {
        var need = new HashMap<ResourceLocation, Long>();
        for (Target target : targets) target.room.forEach((id, room) ->
                need.merge(id, (long)room * MICROVIS_PER_CENTIVIS, Long::sum));
        need.replaceAll((id, amount) -> Math.max(0, amount - balances.getOrDefault(id, 0L)));
        for (int pass = 0; pass < 2; pass++) for (Source source : sources) {
            boolean eligible = pass == 0 ? source.quote.entrySet().stream().allMatch(entry ->
                    need.getOrDefault(entry.getKey(), 0L) >= entry.getValue())
                    : source.quote.keySet().stream().anyMatch(id -> need.getOrDefault(id, 0L) > 0);
            if (!eligible || ReservoirData.withdrawFromCell(bag, source.cell, source.aspect, 1) != 1) continue;
            ReservoirData.credit(device, source.quote);
            ReservoirData.edit(device, d -> d.putInt("source_cursor", (source.cell + 1) % ReservoirData.cells(bag)));
            return true;
        }
        return false;
    }

    private static void retainBuffer(ItemStack device, long capacity) {
        var stock = ReservoirData.balances(device);
        var split = ReservoirAllocation.Split.of(stock.values(), capacity);
        if (split.share() == Long.MAX_VALUE) return;
        // Keep small balances and fractional tails. Deterministic ties survive NBT map reordering.
        long remainder = split.remainder();
        var overflow = new LinkedHashMap<ResourceLocation, Long>();
        for (var id : new TreeSet<>(stock.keySet())) {
            long amount = stock.get(id);
            if (amount <= split.share()) continue;
            long kept = split.share();
            if (remainder > 0) { kept++; remainder--; }
            if (amount > kept) overflow.put(id, amount - kept);
        }
        ReservoirData.debit(device, overflow);
    }

    private static boolean installed(ServerPlayer player,ItemStack bag,int slot,ItemStack device){
        return player.isAlive() && !player.isSpectator() && ReservoirEquipment.worn(player)==bag
                && ReservoirData.supported(bag)
                && ReservoirData.view(bag).stored(slot,player.registryAccess()).matches(device);
    }

    private static boolean deliver(ServerPlayer player, ItemStack bag, int slot, ItemStack device, List<Target> targets,
                                   ResourceLocation after, Delivery delivery) {
        while (true) {
            var stock = wholeBalances(device);
            if (stock.isEmpty() || !installed(player, bag, slot, device)) break;
            var shares = new ArrayList<ReservoirAllocation.Share>(targets.size());
            for (var target : targets) {
                var open = new LinkedHashMap<>(target.room);
                open.keySet().removeAll(target.declined);
                shares.add(new ReservoirAllocation.Share(open, target.received, after));
            }
            ReservoirAllocation.plan(shares, stock);
            boolean retry = false;
            for (int i = 0; i < targets.size(); i++) {
                var target = targets.get(i);
                var share = shares.get(i);
                if (share.request.isEmpty()) continue;
                if (!installed(player, bag, slot, device)) return !delivery.moved.isEmpty();
                var actual = transfer(player, bag, slot, device, target, share);
                delivery.record(target, share, actual);
                for (var entry : share.request.entrySet()) {
                    if (actual.getOrDefault(AspectId.of(entry.getKey()), 0) < entry.getValue())
                        retry |= target.declined.add(entry.getKey());
                }
            }
            // A hand concentrates on its own receipt. A zero receipt lets visitTiers open the next tier.
            if (targets.size() == 1 || !retry || !installed(player, bag, slot, device)) break;
            for (var target : targets) target.room = player.getInventory().getItem(target.slot) == target.stack
                    ? room(target.stack) : Map.of();
        }
        return !delivery.moved.isEmpty();
    }

    private static Map<AspectId, Integer> transfer(ServerPlayer player, ItemStack bag, int slot, ItemStack device,
                                                   Target target, ReservoirAllocation.Share share) {
        if (player.getInventory().getItem(target.slot) != target.stack) return Map.of();
        var storage = WandCharging.storage(target.stack);
        if (storage == null) return Map.of();
        var live = storage.snapshot();
        var balances = ReservoirData.balances(device);
        var request = new LinkedHashMap<AspectId, Integer>();
        for (var id : share.order) {
            var aspect = AspectId.of(id);
            int free = live.capacityCentivis().getOrDefault(aspect, 0) - live.currentCentivis().getOrDefault(aspect, 0);
            int amount = (int)Math.min(Math.min(share.request.getOrDefault(id, 0), free),
                    balances.getOrDefault(id, 0L) / MICROVIS_PER_CENTIVIS);
            if (amount > 0) request.put(aspect, amount);
        }
        if (request.isEmpty()) return Map.of();
        var result = WandCharging.insert(storage, player, VisCost.ofCentivis(request), plan -> {
            if (!installed(player, bag, slot, device) || player.getInventory().getItem(target.slot) != target.stack) return false;
            var charge = new LinkedHashMap<ResourceLocation, Long>();
            plan.settledCentivis().forEach((id, amount) -> {
                if (amount > 0) charge.put(id.value(), (long)amount * MICROVIS_PER_CENTIVIS);
            });
            if (charge.isEmpty() || !ReservoirData.debit(device, charge)) return false;
            ReservoirData.slot(bag, slot, device, player.registryAccess());
            player.getInventory().setChanged();
            return true;
        });
        return result.mutated() ? result.plan().deltasCentivis() : Map.of();
    }

    private static void vent(ServerPlayer player,ItemStack bag,int slot,ItemStack device){
        if(!installed(player,bag,slot,device) || !ReservoirEmission.pending(device))return;
        var outlet=ReservoirFluxVenting.outlet(player);if(outlet.isEmpty())return;
        // Publish the reservation before the host's world callbacks. Never overwrite a changed slot.
        ReservoirEmission.reserve(device);ReservoirData.slot(bag,slot,device,player.registryAccess());
        player.getInventory().setChanged();
        boolean emitted=false;
        try{emitted=ReservoirFluxVenting.emit(player,outlet.get());}
        finally{
            if(!emitted && installed(player,bag,slot,device)){
                ReservoirEmission.refund(device);ReservoirData.slot(bag,slot,device,player.registryAccess());
                player.getInventory().setChanged();
            }
        }
    }
}
