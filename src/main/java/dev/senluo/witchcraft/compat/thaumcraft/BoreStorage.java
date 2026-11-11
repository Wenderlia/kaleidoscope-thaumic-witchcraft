package dev.senluo.witchcraft.compat.thaumcraft;

import com.mojang.serialization.Codec;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.tc4port.thaumcraft.api.ThaumcraftContent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.util.INBTSerializable;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Server ledgers live in the chunk, outside the host's saveCustomOnly/update packet. */
final class BoreStorage implements INBTSerializable<CompoundTag> {
    private static final DeferredRegister<AttachmentType<?>> TYPES = DeferredRegister.create(
            NeoForgeRegistries.ATTACHMENT_TYPES, KaleidoscopeWitchcraft.MOD_ID);
    private static final java.util.function.Supplier<AttachmentType<String>> ID = TYPES.register("bore_identity",
            () -> AttachmentType.builder(() -> UUID.randomUUID().toString()).serialize(Codec.STRING).build());
    private static final java.util.function.Supplier<AttachmentType<BoreStorage>> SAVED = TYPES.register("bore_accounts",
            () -> AttachmentType.serializable(holder -> new BoreStorage((LevelChunk) holder)).build());
    static final java.util.function.Supplier<AttachmentType<BoreRings.State>> RUNTIME = TYPES.register("bore_state",
            () -> AttachmentType.builder(BoreRings.State::new).build());
    private final LevelChunk chunk;
    private final Map<Long, Account> accounts = new HashMap<>();

    static final class Account {
        final String id;
        CompoundTag legacyMine, legacyWork;
        final BoreRepair repair = new BoreRepair();
        Account(String id) { this.id = id; }
    }

    private BoreStorage(LevelChunk chunk) { this.chunk = chunk; }
    static void register(IEventBus bus) { TYPES.register(bus); }

    static Account get(BlockEntity bore) {
        var chunk = ((ServerLevel) bore.getLevel()).getChunkAt(bore.getBlockPos());
        var store = chunk.getData(SAVED);
        String id = bore.getData(ID);
        long key = bore.getBlockPos().asLong();
        Account account = store.accounts.get(key);
        if (account == null || !account.id.equals(id)) {
            account = new Account(id);
            store.accounts.put(key, account);
            bore.setChanged();
        }
        return account;
    }

    @Override public CompoundTag serializeNBT(HolderLookup.Provider lookup) {
        // Saving already visits this loaded chunk. Prune removed/replaced machines without a world scan.
        accounts.entrySet().removeIf(entry -> {
            BlockPos pos = BlockPos.of(entry.getKey());
            if (!chunk.getBlockState(pos).is(ThaumcraftContent.block("arcane_bore"))) return true;
            BlockEntity live = chunk.getBlockEntities().get(pos);
            return live != null && (!live.hasData(ID) || !entry.getValue().id.equals(live.getData(ID)));
        });
        ListTag entries = new ListTag();
        accounts.forEach((pos, account) -> {
            CompoundTag data = new CompoundTag();
            data.putLong("Pos", pos); data.putString("Id", account.id);
            if (account.legacyMine != null) data.put("Mine", account.legacyMine);
            data.put("Repair", account.repair.serializeNBT(lookup));
            if (account.legacyWork != null) data.put("Work", account.legacyWork);
            entries.add(data);
        });
        CompoundTag tag = new CompoundTag(); tag.put("Accounts", entries); return tag;
    }

    @Override public void deserializeNBT(HolderLookup.Provider lookup, CompoundTag tag) {
        accounts.clear();
        for (Tag entry : tag.getList("Accounts", Tag.TAG_COMPOUND)) {
            CompoundTag data = (CompoundTag) entry;
            Account account = new Account(data.getString("Id"));
            account.legacyMine = data.contains("Mine", Tag.TAG_COMPOUND) ? data.getCompound("Mine") : null;
            account.repair.deserializeNBT(lookup, data.getCompound("Repair"));
            account.legacyWork = data.contains("Work", Tag.TAG_COMPOUND) ? data.getCompound("Work") : null;
            accounts.put(data.getLong("Pos"), account);
        }
    }
}
