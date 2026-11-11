package dev.senluo.witchcraft.time;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Quality is earned one paid development stage at a time, with no extractable TU account. */
public final class FruitEntity extends BlockEntity implements DevelopmentClock.Participant {
    public BlockPos corePos;
    public UUID coreIdentity;
    public UUID sideIdentity;
    public UUID owner;
    public int grade;
    public int economyVersion = 2;
    public int targetGrade; // Fixed by its source node before any paid work.
    public BlockPos source; // Relative node position, or zero for the great-tree core.
    private CompoundTag unsupported;
    public boolean supported() { return unsupported == null; }
    public CompoundTag preservedData() { return unsupported == null ? null : unsupported.copy(); }
    public long invested;
    public int ticks;
    public boolean harvested;
    public boolean orphaned;
    private double harvestRoll = -1;
    private long harvestToolTick = Long.MIN_VALUE;
    private int harvestFortune;

    public FruitEntity(BlockPos pos, BlockState state) { super(TemporalContent.FRUIT_ENTITY.get(),pos,state); }

    @Override public void onLoad() {
        super.onLoad();
        if(level instanceof ServerLevel server)TemporalWorld.get(server).trees.addFruit(this);
    }
    @Override public void setRemoved() {
        if(level instanceof ServerLevel server)TemporalWorld.get(server).trees.removeFruit(this);
        super.setRemoved();
    }

    private void bind(LivingEntity core, UUID fruitSlot) {
        corePos = core.getBlockPos().immutable();
        coreIdentity = core.tissue.identity;
        sideIdentity = fruitSlot;
        owner = core.owner();
        if(level instanceof ServerLevel server)TemporalWorld.get(server).trees.addFruit(this);
        changed();
    }

    public void bindNode(LivingEntity core, BlockPos source, FruitSites.Site site) {
        this.source = source.immutable(); this.targetGrade = site.grade;
        bind(core, site.identity);
    }

    public LivingEntity core() {
        if (orphaned || !(level instanceof ServerLevel server) || corePos == null || !server.hasChunkAt(corePos)
                || !server.shouldTickBlocksAt(corePos) || !server.shouldTickBlocksAt(worldPosition)
                || !server.getBlockState(worldPosition.above()).is(TemporalContent.TIME_LEAVES.get())) return null;
        return server.getBlockEntity(corePos) instanceof LivingEntity core && core.tree() && core.tissue.supported() && !core.isRemoved()
                && core.tissue.identity.equals(coreIdentity) ? core : null;
    }

    public int limit() {
        if (!supported() || core() == null || targetGrade < 1) return 0;
        return level instanceof ServerLevel server && TemporalWorld.get(server).trees.nurturing.permits(this) ? targetGrade : 0;
    }
    private int stageTicks() { return TreeBalance.nodeFruitTicks(targetGrade); }
    private long stageFee() { return TreeBalance.nodeFruitFee(targetGrade); }
    private boolean complete() { return targetGrade <= 0 || grade >= targetGrade; }
    public int remainingTicks() { return complete() ? 0 : Math.max(0, stageTicks() - ticks); }
    public long remainingFee() { return complete() ? 0 : Math.max(0, stageFee() - invested); }
    public boolean canGrow() {
        LivingEntity parent = core();
        if (parent == null || grade >= limit() || !(level instanceof ServerLevel server)) return false;
        Colony group = TemporalWorld.get(server).colony(parent);
        if (group.productionMode() == 0) return false;
        long needed = Math.max(0, WorkCost.cumulative(stageFee(), Math.min(stageTicks(), ticks + 1), stageTicks()) - invested);
        return needed <= FruitFeeding.available(group);
    }

    /** One quantile per fruit, retained across full containers and save/load. */
    public double harvestRoll() {
        if (harvestRoll < 0 && level instanceof ServerLevel server) {
            harvestRoll = server.random.nextDouble();
            setChanged();
        }
        return harvestRoll;
    }

    public void prepareHarvest(ItemStack tool) {
        if (level instanceof ServerLevel server) {
            harvestFortune = FruitHarvest.fortune(server, tool);
            harvestToolTick = server.getGameTime();
        }
    }

    public void clearHarvestTool() { harvestToolTick = Long.MIN_VALUE; harvestFortune = 0; }

    public int harvestFortune() {
        return level != null && harvestToolTick == level.getGameTime() ? harvestFortune : 0;
    }

    public static void tick(FruitEntity fruit) {
        if (!(fruit.level instanceof ServerLevel level) || fruit.isRemoved() || !level.tickRateManager().runsNormally()) return;
        fruit.checkSupport();
        if (fruit.isRemoved() || fruit.orphaned) return;
        TemporalWorld.get(level).development.pulse(fruit);
    }

    @Override public BlockEntity developmentParent() { return core(); }
    @Override public int developmentRate() {
        LivingEntity parent = core();
        return parent == null || !(level instanceof ServerLevel server) ? 1
                : SupplyMode.developmentRate(TemporalWorld.get(server).colony(parent).productionMode());
    }
    @Override public void advanceDevelopment(int elapsed) {
        if (level instanceof ServerLevel server) TemporalWorld.get(server).production.offer(this, elapsed);
    }

    /** Completes the source-defined quality for its remaining price, even while paused. */
    public boolean hasten() {
        LivingEntity parent = core();
        if (parent == null || !(level instanceof ServerLevel server) || isRemoved()
                || !server.tickRateManager().runsNormally() || grade >= limit()) return false;
        Colony group = TemporalWorld.get(server).colony(parent);
        long payment = remainingFee();
        if (payment > FruitFeeding.available(group) || payment > 0 && !group.spend(payment)) return false;
        grade = targetGrade;
        invested = 0;
        ticks = 0;
        changed();
        return true;
    }

    public void loseCore() {
        if (!supported()) return;
        orphaned = true;
        setChanged();
        // onRemove runs while the old core can still be read at its position.
        if (level instanceof ServerLevel server && (grade == 0
                || !server.getBlockState(worldPosition.above()).is(TemporalContent.TIME_LEAVES.get())))
            server.removeBlock(worldPosition,false);
    }

    public void checkSupport() {
        if (!(level instanceof ServerLevel server) || isRemoved()) return;
        // An unloaded core or unavailable plan is unknown, not proof that its leaf will never return.
        if (corePos != null && server.hasChunkAt(corePos) && server.shouldTickBlocksAt(corePos)) {
            if (server.getBlockEntity(corePos) instanceof LivingEntity core && core.tree()
                    && core.tissue.identity.equals(coreIdentity)) {
                if (!core.tissue.supported() || core.isRemoved()) return;
                if (orphaned) { orphaned = false; setChanged(); }
                if (TemporalWorld.get(server).trees.plannedLeaf(core, worldPosition.above()) == TreeLogic.LeafSupport.ABSENT)
                    server.removeBlock(worldPosition,false);
                return;
            }
            if (!orphaned) {
                orphaned = true;
                setChanged();
            }
        }
        // A confirmed lost core cannot regenerate. Its ripe fruit survives only on a remaining real leaf.
        // onRemove settles once and preserves opaque unsupported data through the existing item payload.
        if (orphaned && (grade == 0 || !server.getBlockState(worldPosition.above()).is(TemporalContent.TIME_LEAVES.get())))
            server.removeBlock(worldPosition,false);
    }

    private record Progress(int grade, long invested, int ticks, long paid) {}

    /** Quote and commit use the same pure transition, with cumulative stage payments. */
    private Progress work(int elapsed, long allowance) {
        int nextGrade = grade, nextTicks = ticks;
        long nextInvested = invested, paid = 0;
        int maximum = limit();
        while (elapsed > 0 && nextGrade < maximum) {
            int duration = stageTicks();
            long fee = stageFee();
            int step = Math.min(elapsed, Math.max(0, duration - nextTicks));
            long available = Math.max(0, allowance - paid), payment;
            int affordable = WorkCost.affordableTicks(fee, nextInvested + Math.min(available, fee), duration);
            step = Math.min(step, Math.max(0, affordable - nextTicks));
            payment = Math.max(0, WorkCost.cumulative(fee, nextTicks + step, duration) - nextInvested);
            if (payment > available) break;
            nextInvested += payment;
            paid += payment;
            nextTicks += step;
            elapsed -= step;
            if (nextTicks >= duration && nextInvested >= fee) {
                nextGrade = targetGrade; nextInvested = 0; nextTicks = 0;
            } else break;
        }
        return new Progress(nextGrade, nextInvested, nextTicks, paid);
    }
    long demand(int elapsed) { return work(elapsed, Long.MAX_VALUE).paid; }
    long smallestPayment() { return Math.max(1, demand(1)); }
    double stageProgress() { return complete() ? 1 : Math.max((double) invested / stageFee(), (double) ticks / stageTicks()); }

    long advance(int elapsed, long allowance, Colony group) {
        LivingEntity parent = core();
        if (elapsed <= 0 || parent == null || !(level instanceof ServerLevel server) || isRemoved()
                || TemporalWorld.get(server).colony(parent) != group || group.productionMode() == 0) return 0;
        Progress next = work(elapsed, Math.min(Math.max(0, allowance), FruitFeeding.available(group)));
        if (next.paid > 0 && !group.spend(next.paid)) return 0;
        if (grade != next.grade || invested != next.invested || ticks != next.ticks) {
            grade = next.grade; invested = next.invested; ticks = next.ticks;
            changed();
        }
        return next.paid;
    }

    private void changed() {
        setChanged();
        if (level != null && !level.isClientSide && getBlockState().is(TemporalContent.FRUIT.get())) {
            level.setBlock(worldPosition,getBlockState().setValue(FruitBlock.GRADE,grade),3);
            level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),2);
        }
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag,registries);
        if (unsupported != null) { tag.merge(unsupported.copy()); return; }
        if (corePos != null) tag.putLong("CorePos",corePos.asLong());
        if (coreIdentity != null) tag.putUUID("Core",coreIdentity);
        if (sideIdentity != null) tag.putUUID("Side",sideIdentity);
        if (owner != null) tag.putUUID("Owner",owner);
        tag.putInt("EconomyVersion", economyVersion);
        if (targetGrade > 0 && source != null) {
            tag.putInt("NurturingVersion", 1); tag.putInt("TargetGrade", targetGrade); tag.putLong("Source", source.asLong());
        }
        tag.putInt("Grade",grade); tag.putLong("Invested",invested); tag.putInt("Ticks",ticks);
        tag.putBoolean("Orphaned",orphaned);
        if (harvestRoll >= 0) tag.putDouble("HarvestRoll",harvestRoll);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag,registries);
        double roll = tag.contains("HarvestRoll",Tag.TAG_DOUBLE) ? tag.getDouble("HarvestRoll") : -1;
        harvestRoll = Double.isFinite(roll) && roll >= 0 && roll < 1 ? roll : -1;
        clearHarvestTool();
        corePos = tag.contains("CorePos") ? BlockPos.of(tag.getLong("CorePos")) : null;
        coreIdentity = tag.hasUUID("Core") ? tag.getUUID("Core") : null;
        sideIdentity = tag.hasUUID("Side") ? tag.getUUID("Side") : null;
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        orphaned = tag.getBoolean("Orphaned");
        economyVersion = tag.getInt("EconomyVersion");
        unsupported = economyVersion == 2 ? null : tag.copy();
        targetGrade = tag.getInt("TargetGrade");
        source = tag.contains("Source", Tag.TAG_LONG) ? BlockPos.of(tag.getLong("Source")) : null;
        if (tag.getInt("NurturingVersion") != 1 || targetGrade < 1 || targetGrade > 5
                || source == null || Math.abs((long)source.getX()) > 16 || Math.abs((long)source.getZ()) > 16
                || source.getY() < -4 || source.getY() > 24 || (targetGrade == 5) != source.equals(BlockPos.ZERO)) unsupported = tag.copy();
        if (unsupported != null) { targetGrade = 0; source = null; }
        grade = Tissue.bounded(tag.getInt("Grade"),TreeBalance.MAX_FRUIT_GRADE);
        if (targetGrade > 0 && grade != 0 && grade != targetGrade) { unsupported = tag.copy(); targetGrade = 0; }
        invested = complete() ? 0 : Math.min(Tissue.bounded(tag.getLong("Invested")),stageFee());
        ticks = complete() ? 0 : Tissue.bounded(tag.getInt("Ticks"),stageTicks());
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("ViewVersion", 1);
        tag.putInt("Grade", grade);
        return tag;
    }
    @Override public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        if (level == null || !level.isClientSide || tag.getInt("ViewVersion") != 1) return;
        grade = Tissue.bounded(tag.getInt("Grade"), TreeBalance.MAX_FRUIT_GRADE);
    }
    @Override public void onDataPacket(net.minecraft.network.Connection connection,
            ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        handleUpdateTag(packet.getTag(), registries);
    }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
