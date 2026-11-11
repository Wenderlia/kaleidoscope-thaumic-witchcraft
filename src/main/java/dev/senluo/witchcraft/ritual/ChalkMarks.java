package dev.senluo.witchcraft.ritual;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.util.INBTSerializable;

/** Surface records belong to the supporting chunk, independently of tree regeneration memory. */
public final class ChalkMarks implements INBTSerializable<CompoundTag> {
    public static final int LIMIT = 1024;
    public record Mark(int symbol, int rotation, int medium, int design) {
        // 0: a logical view of a physical chalk layer; 1: a persisted wood engraving.
        public boolean supported() {
            return symbol == 0 && rotation >= 0 && rotation < ChalkPattern.TURNS && medium >= 0 && medium <= 1
                    && design >= 0 && design < (medium == 0 ? ChalkPattern.DESIGNS : ChalkPattern.CARVING_ATLAS_DESIGNS);
        }
        public Mark asCarving() {
            int carving = medium == 0 ? ChalkPattern.CARVING_DESIGNS + design : design;
            return new Mark(symbol, rotation, 1, carving);
        }
    }
    private final Map<Integer, Mark> marks = new LinkedHashMap<>();
    private final Map<Integer, Mark> view = Collections.unmodifiableMap(marks);
    private CompoundTag preserved;

    public boolean supported() { return preserved == null; }
    public Map<Integer, Mark> entries() { return view; }
    public Mark get(BlockPos pos) { return marks.get(key(pos)); }
    public boolean put(BlockPos pos, Mark mark) {
        int key = key(pos);
        if (!supported() || !mark.supported() || marks.containsKey(key) || marks.size() >= LIMIT) return false;
        marks.put(key, mark);
        return true;
    }
    public boolean replace(BlockPos pos, Mark mark) {
        if (!supported() || !mark.supported() || !marks.containsKey(key(pos))) return false;
        marks.put(key(pos), mark); return true;
    }
    public boolean remove(BlockPos pos) { return supported() && marks.remove(key(pos)) != null; }
    private static int key(BlockPos pos) { return pos.getY() << 8 | (pos.getZ() & 15) << 4 | pos.getX() & 15; }
    public static BlockPos position(ChunkPos chunk, int key) {
        return new BlockPos(chunk.getMinBlockX() + (key & 15), key >> 8, chunk.getMinBlockZ() + ((key >> 4) & 15));
    }

    @Override public CompoundTag serializeNBT(HolderLookup.Provider registries) {
        if (preserved != null) return preserved.copy();
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", 4);
        ListTag list = new ListTag();
        marks.forEach((pos, mark) -> {
            CompoundTag entry = new CompoundTag();
            entry.putInt("Position", pos);
            entry.putInt("Symbol", mark.symbol());
            entry.putInt("Rotation", mark.rotation());
            entry.putInt("Medium", mark.medium());
            entry.putInt("Design", mark.design());
            list.add(entry);
        });
        tag.put("Marks", list);
        return tag;
    }
    @Override public void deserializeNBT(HolderLookup.Provider registries, CompoundTag tag) {
        marks.clear();
        preserved = null;
        if (tag.isEmpty()) return;
        if (!tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 4 || !(tag.get("Marks") instanceof ListTag list)
                || list.size() > LIMIT || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) {
            preserved = tag.copy();
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            Mark mark = new Mark(entry.getInt("Symbol"), entry.getInt("Rotation"), entry.getInt("Medium"), entry.getInt("Design"));
            int position = entry.getInt("Position");
            if (!entry.contains("Position", Tag.TAG_INT) || !entry.contains("Symbol", Tag.TAG_INT)
                    || !entry.contains("Rotation", Tag.TAG_INT) || !entry.contains("Medium", Tag.TAG_INT)
                    || !entry.contains("Design", Tag.TAG_INT)
                    || !mark.supported() || marks.putIfAbsent(position, mark) != null) {
                marks.clear();
                preserved = tag.copy();
                return;
            }
        }
    }

    // Only displayable records cross the network; unknown raw save data stays on the server.
    public static final StreamCodec<RegistryFriendlyByteBuf, ChalkMarks> STREAM_CODEC = StreamCodec.of((buf, value) -> {
        buf.writeBoolean(value.supported());
        buf.writeVarInt(value.marks.size());
        value.marks.forEach((pos, mark) -> {
            buf.writeInt(pos); buf.writeByte(mark.symbol()); buf.writeByte(mark.rotation()); buf.writeByte(mark.medium());
            buf.writeByte(mark.design());
        });
    }, buf -> {
        ChalkMarks value = new ChalkMarks();
        boolean available = buf.readBoolean();
        int count = buf.readVarInt();
        if (count < 0 || count > LIMIT || !available && count != 0) throw new IllegalArgumentException("Invalid chalk count");
        for (int i = 0; i < count; i++) {
            int position = buf.readInt();
            Mark mark = new Mark(buf.readUnsignedByte(), buf.readUnsignedByte(), buf.readUnsignedByte(), buf.readByte());
            if (!mark.supported() || value.marks.putIfAbsent(position, mark) != null)
                throw new IllegalArgumentException("Invalid chalk mark");
        }
        if (!available) value.preserved = new CompoundTag();
        return value;
    });
}
