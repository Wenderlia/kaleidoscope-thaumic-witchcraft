package dev.senluo.witchcraft.time;

import dev.senluo.witchcraft.ritual.TreeBond;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;

/** Dimension-local site ownership, separate from portable tissue and loaded simulation indexes. */
public final class TreeClaims extends SavedData {
    private static final Factory<TreeClaims> FACTORY = new Factory<>(TreeClaims::new, TreeClaims::load);
    private static final int MAX_PARTS = TreeShapeSpace.MAX_PARTS;
    private static final class Site {
        final UUID identity;
        final Set<BlockPos> parts = new HashSet<>();
        TreeBond bond;
        Site(UUID identity, TreeBond bond) { this.identity = identity; this.bond = bond; }
    }
    private final Map<BlockPos, Site> sites = new HashMap<>();
    private final Map<BlockPos, BlockPos> byPart = new HashMap<>();
    private CompoundTag preserved;

    public static TreeClaims get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "witchcraft_tree_claims");
    }
    public boolean supported() { return preserved == null; }
    public BlockPos ownerAt(BlockPos part) { return byPart.get(part); }

    boolean register(LivingEntity core) {
        if (!supported() || !core.tree() || !core.tissue.supported()) return false;
        BlockPos origin = core.getBlockPos().immutable();
        Site old = sites.get(origin);
        if (old != null && !old.identity.equals(core.tissue.identity)) remove(origin, old.identity);
        if (!sites.containsKey(origin)) {
            sites.put(origin, new Site(core.tissue.identity, core.tissue.bond));
            setDirty();
        }
        updateOwner(core);
        return true;
    }

    public void updateOwner(LivingEntity core) {
        if (!supported() || !core.tissue.supported()) return;
        Site site = sites.get(core.getBlockPos());
        if (site == null || !site.identity.equals(core.tissue.identity)) return;
        TreeBond bond = core.tissue.bond;
        if (site.bond == bond || site.bond != null && bond != null && site.bond.save().equals(bond.save())) return;
        site.bond = bond;
        setDirty();
    }

    boolean claim(LivingEntity core, BlockPos part) {
        if (!supported()) return false;
        BlockPos origin = core.getBlockPos();
        Site site = sites.get(origin);
        if (site == null || !site.identity.equals(core.tissue.identity) || !inEnvelope(origin, part)) return false;
        BlockPos owner = byPart.get(part);
        if (owner != null) return owner.equals(origin);
        // A core is an independent body even if another tree's template reaches its position.
        if (sites.containsKey(part) && !part.equals(origin)) return false;
        if (site.parts.size() >= MAX_PARTS) return false;
        part = part.immutable();
        site.parts.add(part);
        byPart.put(part, origin.immutable());
        setDirty();
        return true;
    }

    /** Only actual removal/replacement calls this; chunk unloading never relinquishes a site. */
    public void remove(BlockPos origin, UUID identity) {
        if (!supported()) return;
        Site site = sites.get(origin);
        if (site == null || !site.identity.equals(identity)) return;
        for (BlockPos part : site.parts) byPart.remove(part, origin);
        sites.remove(origin);
        setDirty();
    }

    public boolean mayInscribe(ServerLevel level, BlockPos part, Player player) {
        if (!supported()) return false;
        BlockPos origin = ownerAt(part);
        if (origin == null) return true;
        Site site = sites.get(origin);
        if (level.hasChunkAt(origin) && level.getBlockEntity(origin) instanceof LivingEntity core
                && core.tissue.identity.equals(site.identity)) {
            updateOwner(core);
            return dev.senluo.witchcraft.ritual.TreeOwnership.mayManage(player, core.tissue);
        }
        // Do not load the core merely to check a drawing action near a chunk boundary.
        return site.bond == null || site.bond.belongsTo(player.getUUID());
    }

    private static boolean inEnvelope(BlockPos origin, BlockPos part) {
        return Math.abs((long)part.getX() - origin.getX()) <= TreeShapeSpace.GIANT_RADIUS
                && Math.abs((long)part.getZ() - origin.getZ()) <= TreeShapeSpace.GIANT_RADIUS
                && (long)part.getY() - origin.getY() >= -4 && (long)part.getY() - origin.getY() <= 24;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (preserved != null) return preserved.copy();
        tag.putInt("Version", 2);
        ListTag list = new ListTag();
        sites.forEach((origin, site) -> {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Core", origin.asLong()); entry.putUUID("Identity", site.identity);
            if (site.bond != null) entry.put("Bond", site.bond.save());
            entry.putLongArray("Parts", site.parts.stream().mapToLong(BlockPos::asLong).sorted().toArray());
            list.add(entry);
        });
        tag.put("Sites", list);
        return tag;
    }

    private static TreeClaims load(CompoundTag tag, HolderLookup.Provider registries) {
        TreeClaims result = new TreeClaims();
        if (tag.getInt("Version") != 2 || !(tag.get("Sites") instanceof ListTag list)
                || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) return preserve(tag);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.contains("Core", Tag.TAG_LONG) || !entry.hasUUID("Identity")
                    || !entry.contains("Parts", Tag.TAG_LONG_ARRAY)) return preserve(tag);
            BlockPos origin = BlockPos.of(entry.getLong("Core"));
            if (result.sites.containsKey(origin)) return preserve(tag);
            Site site = new Site(entry.getUUID("Identity"), TreeBond.read(entry.get("Bond")));
            long[] parts = entry.getLongArray("Parts");
            if (parts.length > MAX_PARTS) return preserve(tag);
            for (long packed : parts) {
                BlockPos part = BlockPos.of(packed);
                if (!inEnvelope(origin, part) || result.byPart.putIfAbsent(part, origin) != null) return preserve(tag);
                site.parts.add(part);
            }
            result.sites.put(origin, site);
        }
        return result;
    }

    private static TreeClaims preserve(CompoundTag tag) {
        TreeClaims result = new TreeClaims(); result.preserved = tag.copy();
        org.slf4j.LoggerFactory.getLogger(TreeClaims.class).error("Unsupported or conflicting tree claims; retained unchanged and disabled claiming");
        return result;
    }
}
