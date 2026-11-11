package dev.senluo.witchcraft.time;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class LivingEntity extends BlockEntity implements DevelopmentClock.Participant {
    public Tissue tissue = new Tissue();
    public BlockState host = Blocks.DEEPSLATE.defaultBlockState();
    public boolean preserveSidesOnBreak;
    public boolean transforming;
    public boolean initialGiantBuild; // World-site entitlement; intentionally absent from the portable tissue item.
    int cropCostCredit;
    int outputCursor;
    int machineCursor;
    long lastGrowth = Long.MIN_VALUE;
    long lastRootGrowth = Long.MIN_VALUE;
    int acceleratedRootTicks;
    long lastFruitHarvestTick = Long.MIN_VALUE;
    int fruitHarvestCursor;
    int fruitContainerCursor;
    final TreeCropFarming cropFarming = new TreeCropFarming();
    final TreeShears shears = new TreeShears();

    public LivingEntity(BlockPos pos, BlockState state) {
        super(TemporalContent.LIVING_ENTITY.get(), pos, state);
        if (state.is(TemporalContent.CORE.get())) { tissue.tree = true; tissue.rootStage = 4; }
    }
    public boolean amber() { return getBlockState().is(TemporalContent.AMBER.get()); }
    public boolean tree() { return getBlockState().is(TemporalContent.CORE.get()); }
    public boolean canDistribute() { return tissue.supported() && (amber() || tissue.maturePlant()); }
    public int development() { return amber() ? Math.max(tissue.rootStage, tissue.amberStage) : tissue.rootStage; }
    public boolean compatible(LivingEntity other) {
        return dev.senluo.witchcraft.ritual.TreeBond.compatible(tissue.bond, other.tissue.bond);
    }
    public UUID owner() { return tissue.bond == null ? null : tissue.bond.player(); }
    public boolean hasOwner() { return tissue.bond != null && tissue.bond.supported(); }

    @Override public void advanceDevelopment(int elapsed) { DevelopmentTime.advance(this, elapsed); }

    /** Compatibility notification: inventory commits already happened through TimeStorage. */
    public void received(long amount, boolean natural) {
        // Output is paid from actual stock; receipts must not mint a second spending authorization.
    }

    @Override public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) TemporalWorld.get(server).add(this);
    }
    @Override public void setRemoved() {
        shears.reset();
        dev.senluo.witchcraft.ritual.OwnershipRitual.coreUnavailable(this);
        if (level instanceof ServerLevel server) TemporalWorld.get(server).remove(worldPosition);
        super.setRemoved();
    }
    public void changed() {
        if (!tissue.supported()) return;
        setChanged();
        if (level instanceof ServerLevel server) {
            if (tree()) TreeClaims.get(server).updateOwner(this);
            int age = amber() ? tissue.amberStage : tissue.harvestAge();
            if (getBlockState().getValue(LivingBlock.AGE) != age) {
                level.setBlock(worldPosition, getBlockState().setValue(LivingBlock.AGE, age), 3);
                TemporalWorld.get(server).invalidate();
            }
        }
    }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (level instanceof ServerLevel server && tree()) TemporalWorld.get(server).trees.beforeSnapshot(this);
        tag.put("Tissue", TissueTag.save(tissue));
        tag.putBoolean("InitialGiantBuild",initialGiantBuild);
        tag.put("Host", NbtUtils.writeBlockState(host));
        tag.putInt("CropCostCredit", cropCostCredit);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        shears.reset();
        tissue = TissueTag.load(tag.getCompound("Tissue"));
        if (!tissue.supported()) tissue.tree = tree(); // Preserve the carrier kind even if its schema is unknown.
        initialGiantBuild=tissue.tree&&tag.getBoolean("InitialGiantBuild");
        host = NbtUtils.readBlockState(registries.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK), tag.getCompound("Host"));
        if (TissueItem.stoneGrade(host) == 0) host = Blocks.DEEPSLATE.defaultBlockState();
        cropCostCredit = Tissue.bounded(tag.getInt("CropCostCredit"), CropSupplyRates.RANDOM_WORK - 1);
        // Commands/reloads may replace a tissue on an already registered core without onLoad.
        if (level instanceof ServerLevel server && server.hasChunkAt(worldPosition)
                && server.getBlockEntity(worldPosition) == this) TemporalWorld.get(server).add(this);
    }
    /** Block presentation only. Portable item tooltips still read the complete item component. */
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("ViewVersion", 1);
        tag.put("Host", NbtUtils.writeBlockState(host));
        tag.putInt("Mode", tissue.productionMode);
        tag.putBoolean("Available", tissue.supported());
        tag.putInt("RootStage", tissue.rootStage);
        tag.putInt("AmberStage", tissue.amberStage);
        tag.putBoolean("Tree", tissue.tree);
        tag.putBoolean("Giant", tissue.giant);
        tag.putBoolean("TreeRoot", tissue.treeRoot);
        if (tissue.bond != null) tag.put("Bond", tissue.bond.save());
        return tag;
    }
    @Override public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        if (level == null || !level.isClientSide || tag.getInt("ViewVersion") != 1) return;
        // Presentation packets do not replace the authoritative saved organism.
        Tissue display = new Tissue();
        display.rootStage = Tissue.bounded(tag.getInt("RootStage"), 4);
        display.amberStage = Tissue.bounded(tag.getInt("AmberStage"), 3);
        display.tree = tag.getBoolean("Tree");
        display.giant = tag.getBoolean("Giant");
        display.treeRoot = tag.getBoolean("TreeRoot");
        display.bond = dev.senluo.witchcraft.ritual.TreeBond.read(tag.get("Bond"));
        if (!tag.getBoolean("Available")) display.preserve(new CompoundTag());
        tissue = display;
        host = NbtUtils.readBlockState(registries.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK), tag.getCompound("Host"));
        if (TissueItem.stoneGrade(host) == 0) host = Blocks.DEEPSLATE.defaultBlockState();
        tissue.productionMode = Tissue.bounded(tag.getInt("Mode"), 2);
    }
    @Override public void onDataPacket(net.minecraft.network.Connection connection,
            ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        handleUpdateTag(packet.getTag(), registries);
    }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
