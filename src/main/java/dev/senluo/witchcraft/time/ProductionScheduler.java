package dev.senluo.witchcraft.time;

import dev.senluo.witchcraft.compat.CropGrowth;
import java.math.BigInteger;
import java.util.*;
import net.minecraft.core.BlockPos;

/** Quotes current work, shares real stock, then commits native work. No unpaid simulation debt. */
public final class ProductionScheduler {
    private record Key(int kind, UUID identity) {}
    private interface Job {
        Key key();
        long demand();
        long smallestPayment();
        long run(long allowance);
    }
    private final TemporalWorld world;
    private final Map<FruitEntity, Integer> fruitTicks = new IdentityHashMap<>();
    private final Map<UUID, UUID> leadSlots = new HashMap<>();
    // Fractions reserve existing stock only. They are not balances, income or saved work.
    private final Map<Colony, Map<Key, Long>> reservations = new WeakHashMap<>();
    private int cursor;
    private int cropCalls;
    private final CropGrowthFeedback cropFeedback = new CropGrowthFeedback();
    private final CropSupplyClock cropClock = new CropSupplyClock();

    public ProductionScheduler(TemporalWorld world) { this.world = world; }
    public void offer(FruitEntity fruit, int elapsed) {
        if (elapsed > 0) fruitTicks.merge(fruit, elapsed, Math::max);
    }
    // Topology changes invalidate group reservations, not observations owned by unchanged plants.
    public void invalidate() { reservations.clear(); cropClock.topologyChanged(); }
    void forget(Colony group) {
        reservations.remove(group);
        for (LivingEntity member : group.members) cropClock.topologyChanged(member.tissue.identity);
    }
    public void cropChanged(BlockPos pos, net.minecraft.world.level.block.state.BlockState before,
            net.minecraft.world.level.block.state.BlockState after) {
        cropFeedback.changed(pos, before, after);
        // Advancing an age is not a new plant. Replacement/harvest cannot carry old timing credit.
        if (before.getBlock() != after.getBlock() || CropGrowth.progress(after) < CropGrowth.progress(before))
            cropClock.changed(pos);
    }
    public void chunkChanged(long chunk) { cropFeedback.chunkChanged(chunk); cropClock.chunkChanged(chunk); }

    public void flush(List<LivingEntity> producers) {
        cropCalls = 0;
        if(producers.isEmpty() && fruitTicks.isEmpty()){
            leadSlots.clear(); reservations.clear(); cropClock.retainSources(Set.of());
            return;
        }
        cropClock.retainSources(producers.stream().map(source -> source.tissue.identity).collect(java.util.stream.Collectors.toSet()));
        Map<Colony, List<Job>> primary = new LinkedHashMap<>();
        Map<Colony, List<FruitJob>> secondary = new IdentityHashMap<>();
        // All quotes precede execution. Share one discovery per group in this flush only;
        // native work still rechecks eligibility immediately before each debit/call.
        Map<Colony, List<BlockPos>> machineOffers = new IdentityHashMap<>();
        Map<UUID, List<FruitJob>> perTree = new TreeMap<>();
        for (var entry : fruitTicks.entrySet()) {
            FruitEntity fruit = entry.getKey();
            LivingEntity core = fruit.core();
            if (core == null || fruit.isRemoved() || !fruit.supported() || fruit.grade >= fruit.limit()) continue;
            Colony group = world.colony(core);
            if (group.productionMode() != SupplyMode.PAUSED)
                perTree.computeIfAbsent(core.tissue.identity, unused -> new ArrayList<>()).add(new FruitJob(fruit, entry.getValue(), group));
        }
        fruitTicks.clear();
        leadSlots.keySet().retainAll(perTree.keySet());
        for (var entry : perTree.entrySet()) {
            List<FruitJob> jobs = entry.getValue();
            jobs.sort(Comparator.comparingInt((FruitJob job) -> job.fruit.grade).reversed()
                    .thenComparing(Comparator.comparingDouble((FruitJob job) -> job.fruit.stageProgress()).reversed())
                    .thenComparing(job -> job.fruit.sideIdentity));
            UUID selected = leadSlots.get(entry.getKey());
            FruitJob lead = jobs.stream().filter(job -> job.fruit.sideIdentity.equals(selected)).findFirst().orElse(jobs.getFirst());
            leadSlots.put(entry.getKey(), lead.fruit.sideIdentity);
            primary.computeIfAbsent(lead.group, unused -> new ArrayList<>()).add(lead);
            for (FruitJob job : jobs) if (job != lead) secondary.computeIfAbsent(job.group, unused -> new ArrayList<>()).add(job);
        }
        for (LivingEntity source : producers) {
            Colony group = world.colony(source);
            int modeRate = SupplyMode.rate(group.productionMode());
            if (modeRate == 0) continue;
            List<Job> jobs = primary.computeIfAbsent(group, unused -> new ArrayList<>());
            CropJob crops = new CropJob(source, group, modeRate);
            if (!crops.steps.isEmpty()) jobs.add(crops);
            if (!source.amber()) {
                List<BlockPos> targets = machineOffers.computeIfAbsent(group, colony -> world.machines.get(colony).stream()
                        .filter(pos -> MachineWork.eligible(world.level, pos)).toList());
                MachineJob machines = new MachineJob(source, group, modeRate, targets);
                if (!machines.targets.isEmpty()) jobs.add(machines);
            }
        }
        Set<Colony> current = Collections.newSetFromMap(new IdentityHashMap<>());
        current.addAll(primary.keySet());
        reservations.keySet().retainAll(current);
        var groups = new ArrayList<>(primary.entrySet());
        for (int n = 0; n < groups.size(); n++) {
            var entry = groups.get(Math.floorMod(cursor + n, groups.size()));
            settle(entry.getKey(), entry.getValue(), secondary.getOrDefault(entry.getKey(), List.of()));
        }
        cursor = cursor == Integer.MAX_VALUE ? 0 : cursor + 1;
    }

    private boolean valid(LivingEntity source, Colony group) {
        return !source.isRemoved() && world.node(source.getBlockPos()) == source && world.colony(source) == group
                && group.productionMode() != SupplyMode.PAUSED;
    }

    private void settle(Colony group, List<Job> jobs, List<FruitJob> others) {
        Map<Key, Long> old = reservations.getOrDefault(group, Map.of());
        long[] needs = new long[jobs.size()], grants = new long[jobs.size()];
        for (int i = 0; i < jobs.size(); i++) {
            needs[i] = jobs.get(i).demand();
            grants[i] = Math.min(needs[i], old.getOrDefault(jobs.get(i).key(), 0L));
        }
        long available = FruitFeeding.available(group);
        long held = sum(grants);
        if (held > available) grants = proportional(grants, available, cursor);
        long[] remaining = new long[needs.length];
        for (int i = 0; i < needs.length; i++) remaining[i] = needs[i] - grants[i];
        long[] fresh = proportional(remaining, Math.max(0, available - sum(grants)), cursor);
        for (int i = 0; i < grants.length; i++) grants[i] += fresh[i];
        Map<Key, Long> next = new HashMap<>();
        for (int n = 0; n < jobs.size(); n++) {
            int i = Math.floorMod(cursor + n, jobs.size());
            Job job = jobs.get(i);
            long paid = job.run(grants[i]);
            // At most one indivisible work step may hold credit; surplus is free for other fruit.
            long carry = Math.min(Math.max(0, grants[i] - paid), Math.max(0, job.smallestPayment() - 1));
            if (carry > 0) next.put(job.key(), carry);
        }
        reservations.put(group, next);
        long reserved = next.values().stream().mapToLong(Long::longValue).sum();
        for (int n = 0; n < others.size(); n++) {
            FruitJob job = others.get(Math.floorMod(cursor + n, others.size()));
            job.run(Math.max(0, FruitFeeding.available(group) - reserved));
        }
    }

    /** Exact integer shares, rotating the sub-mTU remainder; never allocates more than real stock. */
    static long[] proportional(long[] requested, long available, int cursor) {
        long total = sum(requested);
        if (total <= available) return requested.clone();
        long[] result = new long[requested.length];
        if (total == 0 || available <= 0) return result;
        BigInteger denominator = BigInteger.valueOf(total), money = BigInteger.valueOf(available);
        long assigned = 0;
        for (int i = 0; i < result.length; i++) {
            result[i] = BigInteger.valueOf(requested[i]).multiply(money).divide(denominator).longValueExact();
            assigned += result[i];
        }
        for (int n = 0; assigned < available && n < result.length; n++) {
            int i = Math.floorMod(cursor + n, result.length);
            if (result[i] < requested[i]) { result[i]++; assigned++; }
        }
        return result;
    }
    private static long sum(long[] values) {
        long total = 0;
        for (long value : values) total = Math.addExact(total, value);
        return total;
    }

    private final class FruitJob implements Job {
        final FruitEntity fruit;
        final int elapsed;
        final Colony group;
        final long quote;
        FruitJob(FruitEntity fruit, int elapsed, Colony group) {
            this.fruit = fruit; this.elapsed = elapsed; this.group = group; this.quote = fruit.demand(elapsed);
        }
        public Key key() { return new Key(0, fruit.sideIdentity); }
        public long demand() { return quote; }
        public long smallestPayment() { return fruit.grade < fruit.limit() ? fruit.smallestPayment() : 0; }
        public long run(long allowance) { return fruit.advance(elapsed, allowance, group); }
    }

    private final class MachineJob implements Job {
        final LivingEntity source;
        final Colony group;
        final List<BlockPos> targets;
        final int steps;
        final long price;
        MachineJob(LivingEntity source, Colony group, int modeRate, List<BlockPos> targets) {
            this.source = source; this.group = group;
            this.targets = targets;
            steps = MachineWork.rate(source) * modeRate / 20;
            price = MachineWork.price(source);
        }
        public Key key() { return new Key(2, source.tissue.identity); }
        public long demand() { return steps * price; }
        public long smallestPayment() { return price; }
        public long run(long allowance) {
            long paid = 0;
            int missed = 0, completed = 0;
            int admitted = -1;
            while (completed < steps && missed < targets.size() && allowance - paid >= price && valid(source, group)) {
                BlockPos pos = targets.get(Math.floorMod(source.machineCursor++, targets.size()));
                Runnable work = MachineWork.operation(world.level, pos);
                if (work == null) { missed++; continue; }
                // Reserve world-wide execution before spending TU. This shares the ritual cap;
                // rejected work has neither a debit nor a later catch-up obligation.
                if (admitted < 0) {
                    long affordable = Math.min(steps, Math.min(allowance, FruitFeeding.available(group)) / price);
                    admitted = dev.senluo.witchcraft.ritual.RitualWorkBudget.claim(world.level,
                            dev.senluo.witchcraft.ritual.RitualWorkBudget.Kind.PRODUCTION_MACHINE,
                            source.getBlockPos(), BigInteger.valueOf(affordable));
                }
                if (completed >= admitted) break;
                if (price > FruitFeeding.available(group) || !group.spend(price)) break;
                paid += price;
                AccelerationSources.runProduction(source, () -> {
                    // Cover the next natural update regardless of scheduler ordering.
                    AccelerationSources.renew(world.level, world.level.getBlockEntity(pos), source.owner(), 2);
                    work.run();
                }); // A partial native failure keeps its debit and propagates; no false refund.
                world.level.getProfiler().incrementCounter("kw_production_machine_updates");
                completed++; missed = 0;
            }
            return paid;
        }
    }

    private final class CropJob implements Job {
        record Step(BlockPos pos, net.minecraft.world.level.block.state.BlockState state, int work, boolean extra) {}
        record Offer(BlockPos pos, net.minecraft.world.level.block.state.BlockState state, int work, int attempts, boolean timed) {}
        final LivingEntity source;
        final Colony group;
        final List<Step> steps = new ArrayList<>();
        final long price;
        long quote, minimum;
        CropJob(LivingEntity source, Colony group, int modeRate) {
            this.source = source; this.group = group;
            price = CropSupplyRates.price(source);
            long tick = world.level.getGameTime();
            cropClock.begin(source.tissue.identity, tick, CropSupplyRates.BASE_RATE * modeRate);
            List<BlockPos> targets = world.crops.get(group);
            List<Offer> offers = new ArrayList<>();
            // Bounded round-robin discovery. One stubborn crop cannot consume the whole quote
            // before the other due plants have received their first opportunity.
            for (int n = 0; n < Math.min(64, targets.size()) && offers.size() < 16; n++) {
                BlockPos pos = targets.get(Math.floorMod(source.outputCursor++, targets.size()));
                if (!world.level.hasChunkAt(pos) || !world.level.shouldTickBlocksAt(pos)) {
                    cropClock.unavailable(source.tissue.identity, pos); continue;
                }
                var state = world.level.getBlockState(pos);
                if (!CropGrowth.canAttempt(state)) { cropClock.unavailable(source.tissue.identity, pos); continue; }
                int work = CropGrowth.timed(state) ? CropSupplyRates.TICK_WORK : CropSupplyRates.RANDOM_WORK;
                int tokens = cropClock.available(source.tissue.identity, pos, tick, work);
                if (tokens < work) continue;
                // Expensive survival/light/native-ticker checks only when this plant is due.
                var attempt = CropGrowth.operation(world.level, pos, state);
                if (attempt == null) { cropClock.unavailable(source.tissue.identity, pos); continue; }
                int attempts = attempt.timed() ? tokens / work : source.tree() ? cropFeedback.attempts(pos, state) : 1;
                offers.add(new Offer(pos, state, work, attempts, attempt.timed()));
            }
            int credit = source.cropCostCredit;
            for (int round = 0; round < 16 && steps.size() < 16; round++) {
                boolean added = false;
                for (Offer offer : offers) {
                    if (round >= offer.attempts() || steps.size() == 16) continue;
                    long charge = CropSupplyRates.charge(price, offer.work(), credit);
                    if (steps.isEmpty()) minimum = charge;
                    credit = CropSupplyRates.credit(price, offer.work(), credit, charge);
                    quote += charge;
                    steps.add(new Step(offer.pos(), offer.state(), offer.work(), round > 0 && !offer.timed()));
                    added = true;
                }
                if (!added) break;
            }
        }
        public Key key() { return new Key(1, source.tissue.identity); }
        public long demand() { return quote; }
        public long smallestPayment() { return minimum; }
        public long run(long allowance) {
            long paid = 0;
            int used = 0;
            Set<BlockPos> activeBursts = new HashSet<>();
            for (Step step : steps) {
                BlockPos pos = step.pos();
                if (cropCalls >= 256 || !valid(source, group)) break;
                if (!world.level.hasChunkAt(pos) || !world.level.shouldTickBlocksAt(pos)) continue;
                var before = world.level.getBlockState(pos);
                // A changed age, crop replacement or failed first payment invalidates only this burst.
                if (step.extra() && (!activeBursts.contains(pos) || before != step.state())) continue;
                if (before.getBlock() != step.state().getBlock()) continue;
                var attempt = CropGrowth.operation(world.level, pos, before);
                if (attempt == null) { cropClock.unavailable(source.tissue.identity, pos); continue; }
                int work = attempt.timed() ? CropSupplyRates.TICK_WORK : CropSupplyRates.RANDOM_WORK;
                if (work != step.work()) continue;
                long charge = CropSupplyRates.charge(price, work, source.cropCostCredit);
                if (!step.extra() && !cropClock.ready(source.tissue.identity, pos, world.level.getGameTime(), work)) continue;
                if (charge > allowance - paid) break;
                if (charge > FruitFeeding.available(group) || !group.spend(charge)) break;
                source.cropCostCredit = CropSupplyRates.credit(price, work, source.cropCostCredit, charge);
                if (!step.extra()) cropClock.consume(source.tissue.identity, pos, world.level.getGameTime(), work);
                paid += charge;
                source.setChanged();
                AccelerationSources.runProduction(source, () -> ExtraTicks.run(attempt.advance()));
                activeBursts.add(pos);
                if (source.tree() && !attempt.timed() && world.level.hasChunkAt(pos))
                    cropFeedback.completed(pos, before, world.level.getBlockState(pos));
                cropCalls++; used++;
            }
            if (used > 0 && world.level.getGameTime() % 20 == 0) world.level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                    source.getBlockPos().getX() + .5, source.getBlockPos().getY() + .8, source.getBlockPos().getZ() + .5, 1, .2, .1, .2, 0);
            return paid;
        }
    }
}
