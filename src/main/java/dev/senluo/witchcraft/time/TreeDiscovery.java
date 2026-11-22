package dev.senluo.witchcraft.time;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Server-thread search rooted at one physical tree. Only the connected set survives publication. */
final class TreeDiscovery {
    private record Link(BlockPos parent, int depth) {}

    final BlockPos origin;
    final Set<BlockPos> seen = new HashSet<>();
    final Set<BlockPos> connected = new HashSet<>();
    final LinkedHashSet<BlockPos> frontier = new LinkedHashSet<>();
    final Map<BlockPos, Integer> nodes = new LinkedHashMap<>();
    private final Map<BlockPos, Link> links = new HashMap<>();
    private final LinkedHashSet<BlockPos> severed = new LinkedHashSet<>();
    private int safeDepth = Integer.MAX_VALUE;
    boolean reopening;

    TreeDiscovery(BlockPos origin) {
        this.origin = origin;
        seen.add(origin);
        connected.add(origin);
        links.put(origin, new Link(origin, 0));
        frontier.add(origin);
    }

    void connect(BlockPos pos, BlockPos parent) {
        if (connected.add(pos)) {
            links.put(pos, new Link(parent, links.get(parent).depth() + 1));
            frontier.add(pos);
        }
    }

    boolean proven(BlockPos pos) {
        if (!trimming()) return connected.contains(pos);
        Link link = links.get(pos);
        // A surviving vertex at this depth cannot have a severed vertex among its ancestors.
        return link != null && link.depth() <= safeDepth;
    }

    void remove(BlockPos pos) {
        seen.remove(pos);
        Link link = links.remove(pos);
        if (link == null) return;
        connected.remove(pos);
        frontier.remove(pos);
        nodes.remove(pos.subtract(origin));
        severed.add(pos);
        safeDepth = Math.min(safeDepth, link.depth());
    }

    boolean trimming() {
        return !severed.isEmpty();
    }

    /** One work unit. Surviving boundary cells re-examine detached branches after trimming. */
    void trimOne() {
        BlockPos cut = severed.removeFirst();
        for (BlockPos d : TreeShapeSpace.NEIGHBORS) {
            BlockPos neighbour = cut.offset(d);
            Link link = links.get(neighbour);
            if (link == null) continue;
            if (link.parent().equals(cut)) remove(neighbour);
            else frontier.add(neighbour);
        }
        if (severed.isEmpty()) safeDepth = Integer.MAX_VALUE;
    }

    void revisit(BlockPos restored) {
        if (connected.contains(restored)) return;
        seen.remove(restored);
        for (BlockPos d : TreeShapeSpace.NEIGHBORS) {
            BlockPos neighbour = restored.offset(d);
            if (connected.contains(neighbour)) frontier.add(neighbour);
        }
    }
}
