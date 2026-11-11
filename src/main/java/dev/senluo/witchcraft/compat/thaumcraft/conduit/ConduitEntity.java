package dev.senluo.witchcraft.compat.thaumcraft.conduit;

import dev.tc4port.thaumcraft.api.aspect.*;
import dev.tc4port.thaumcraft.api.essentia.*;
import dev.senluo.witchcraft.compat.thaumcraft.HostParameters;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** A 64-unit mixed handoff buffer. Transport entitlement is transient; stored matter is not. */
public final class ConduitEntity extends BlockEntity implements EssentiaTransport, EssentiaStorageView {
    public static final int THROUGHPUT = 64;
    private final Map<AspectId, Integer> contents = new TreeMap<>();
    private Tag preserved;
    private int open = 63, count, credit, aspectCursor;
    private long creditTick = Long.MIN_VALUE, pulseTick = Long.MIN_VALUE, refreshed = Long.MIN_VALUE;
    private boolean taken;
    boolean suppressDrop;
    ConduitNetworks.Network network;

    public ConduitEntity(BlockPos pos, BlockState state) { super(ConduitContent.ENTITY.get(), pos, state); }
    boolean open(Direction side) { return preserved == null && (open & 1 << side.ordinal()) != 0; }
    void toggle(Direction side) {
        if (preserved != null) return;
        open ^= 1 << side.ordinal(); changed();
        if (level instanceof ServerLevel server) {
            ConduitNetworks.changed(server, this); ConduitBlock.refresh(server, worldPosition);
            server.updateNeighborsAt(worldPosition, getBlockState().getBlock());
        }
    }
    @Override public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) { ConduitNetworks.add(server, this); ConduitBlock.refresh(server, worldPosition); }
    }
    @Override public void setRemoved() {
        if (level instanceof ServerLevel server) ConduitNetworks.remove(server, this);
        super.setRemoved();
    }
    @Override public void onChunkUnloaded() {
        if (level instanceof ServerLevel server) ConduitNetworks.remove(server, this);
        super.onChunkUnloaded();
    }
    public static void tick(Level level, BlockPos pos, BlockState state, ConduitEntity pipe) {
        if (!(level instanceof ServerLevel server) || pipe.preserved != null) return;
        // Retain at most one pulse between ticks: differently phased pipes must still cooperate.
        if (pipe.creditTick != level.getGameTime()) { pipe.creditTick = level.getGameTime(); pipe.credit = Math.min(THROUGHPUT, pipe.credit); }
        int interval = Math.max(1, (int) HostParameters.value("machines/essentia_tubes/work_interval"));
        if (++pipe.count >= interval) {
            pipe.count = 0;
            pipe.credit = pipe.pulseTick == level.getGameTime()
                    ? (int)Math.min(Integer.MAX_VALUE, (long)pipe.credit + THROUGHPUT) : THROUGHPUT;
            pipe.pulseTick = level.getGameTime();
            if (pipe.network != null) pipe.network.ready = true;
        }
        if (level.getGameTime() % 20 == 0 && pipe.refreshed != level.getGameTime()) {
            pipe.refreshed = level.getGameTime(); ConduitBlock.refresh(server, pos);
        }
    }
    int credit(long tick) { return preserved != null ? 0 : creditTick == tick ? credit : Math.min(THROUGHPUT, credit); }
    void spend(int amount) {
        if (amount < 0 || amount > credit) throw new IllegalStateException("Conduit work exceeded its pulse");
        credit -= amount;
    }
    boolean available(ServerLevel server) { return !isRemoved() && level == server && preserved == null
            && server.hasChunkAt(worldPosition) && server.shouldTickBlocksAt(worldPosition) && server.getBlockEntity(worldPosition) == this; }
    private int stored() { return contents.values().stream().mapToInt(Integer::intValue).sum(); }
    private void changed() {
        if (network != null) network.stockChanged(this);
        setChanged();
        if (level instanceof ServerLevel server) ConduitNetworks.sync(server, this);
    }
    @Override public boolean isConnectable(Direction face) { return face != null && open(face); }
    @Override public boolean canInputFrom(Direction face) { return preserved == null && isConnectable(face); }
    @Override public boolean canOutputTo(Direction face) { return canInputFrom(face); }
    @Override public AspectId suctionType(Direction face) { return null; }
    @Override public int suctionAmount(Direction face) {
        return canInputFrom(face) && network != null && network.valid ? network.pressure : 0;
    }
    @Override public int minimumSuction() { return 0; }
    @Override public boolean renderExtendedTube() { return false; }
    @Override public AspectId essentiaType(Direction face) { return extractableAspect(face); }
    @Override public int essentiaAmount(Direction face) {
        AspectId aspect = extractableAspect(face); return aspect == null ? 0 : availableEssentia(aspect, face);
    }
    @Override public AspectId extractableAspect(Direction face) {
        if (!canOutputTo(face)) return null;
        if (contents.isEmpty()) {
            var quote = ConduitNetworks.draw(this, face, null, 1, EssentiaTransferMode.SIMULATE);
            return quote == null ? null : quote.aspect();
        }
        var keys = new ArrayList<>(contents.keySet()); return keys.get(Math.floorMod(aspectCursor, keys.size()));
    }
    @Override public int availableEssentia(AspectId aspect, Direction face) {
        if (!canOutputTo(face) || aspect == null) return 0;
        int stored = contents.getOrDefault(aspect, 0);
        if (stored > 0) return stored;
        var quote = ConduitNetworks.draw(this, face, aspect, THROUGHPUT, EssentiaTransferMode.SIMULATE);
        return quote == null ? 0 : quote.amount();
    }
    @Override public AspectAmounts visibleAspects() { return new AspectAmounts(contents); }
    @Override public int essentiaCapacity() { return THROUGHPUT; }
    @Override public boolean sharedEssentiaCapacity() { return true; }
    @Override public int addEssentia(AspectId aspect, int amount, Direction face, EssentiaTransferMode mode) {
        if (!canInputFrom(face) || aspect == null || amount <= 0 || mode == null
                || mode.executes() && !(level instanceof ServerLevel) || AspectApi.registry().get(aspect).isEmpty()) return 0;
        int accepted = Math.min(amount, THROUGHPUT - stored());
        if (accepted > 0 && mode.executes() && level instanceof ServerLevel) { contents.merge(aspect, accepted, Integer::sum); changed(); }
        return accepted;
    }
    @Override public int takeEssentia(AspectId aspect, int amount, Direction face, EssentiaTransferMode mode) {
        if (!canOutputTo(face) || aspect == null || amount <= 0 || mode == null || mode.executes() && !(level instanceof ServerLevel)) return 0;
        int removed = Math.min(Math.min(amount, contents.getOrDefault(aspect, 0)), level == null ? 0 : credit(level.getGameTime()));
        if (removed == 0 && !contents.containsKey(aspect)) {
            var draw = ConduitNetworks.draw(this, face, aspect, amount, mode);
            return draw == null ? 0 : draw.amount();
        }
        if (removed > 0 && mode.executes() && level instanceof ServerLevel) {
            if (creditTick != level.getGameTime()) { credit = Math.min(THROUGHPUT, credit); creditTick = level.getGameTime(); }
            spend(removed); removeStored(aspect, removed);
        }
        return removed;
    }
    void removeStored(AspectId aspect, int amount) {
        int remaining = contents.getOrDefault(aspect, 0) - amount;
        if (remaining < 0) throw new IllegalStateException("Conduit stock underflow");
        if (remaining == 0) contents.remove(aspect); else contents.put(aspect, remaining);
        aspectCursor++; changed();
    }
    boolean hasStock() { return !contents.isEmpty(); }
    int stored(AspectId aspect) { return contents.getOrDefault(aspect, 0); }
    Map<AspectId, Integer> stock() { return Map.copyOf(contents); }
    ItemStack takeItem() {
        if (taken) return ItemStack.EMPTY;
        taken = true; var stack = new ItemStack(ConduitContent.ITEM.get());
        if (!contents.isEmpty() || preserved != null || open != 63) {
            saveToItem(stack, level.registryAccess()); stack.set(DataComponents.MAX_STACK_SIZE, 1);
        }
        return stack;
    }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (preserved != null) { tag.put("Conduit", preserved.copy()); return; }
        CompoundTag data = new CompoundTag(); data.putInt("Version", 1); data.putInt("Open", open);
        CompoundTag stock = new CompoundTag(); contents.forEach((aspect, amount) -> stock.putInt(aspect.toString(), amount));
        data.put("Contents", stock); tag.put("Conduit", data);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries); contents.clear(); preserved = null; open = 63;
        if (!tag.contains("Conduit")) {
            if (network != null) network.stockChanged(this);
            return;
        }
        Tag raw = tag.get("Conduit");
        try {
            if (!(raw instanceof CompoundTag data)) throw new IllegalArgumentException("Invalid conduit envelope");
            if (data.getInt("Version") != 1 || !data.contains("Open", Tag.TAG_INT)
                    || data.getInt("Open") < 0 || data.getInt("Open") > 63 || !data.contains("Contents", Tag.TAG_COMPOUND))
                throw new IllegalArgumentException("Invalid conduit data");
            var stock = data.getCompound("Contents");
            if (stock.size() > THROUGHPUT) throw new IllegalArgumentException("Too many stored types");
            long total = 0;
            for (String id : stock.getAllKeys()) {
                if (!stock.contains(id, Tag.TAG_INT) || stock.getInt(id) <= 0) throw new IllegalArgumentException("Invalid conduit stock");
                int amount = stock.getInt(id); total += amount;
                contents.put(AspectId.parse(id), amount);
            }
            if (total > THROUGHPUT) throw new IllegalArgumentException("Conduit storage overflow");
            open = data.getInt("Open");
        } catch (IllegalArgumentException invalid) {
            preserved = raw.copy(); contents.clear();
            org.slf4j.LoggerFactory.getLogger(ConduitEntity.class).error("Conduit data preserved without mutation at {}: {}", worldPosition, invalid.getMessage());
        } finally {
            if (network != null) network.stockChanged(this);
        }
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
