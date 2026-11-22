package dev.senluo.witchcraft.compat.thaumcraft;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Disposable mining sessions. Only the ritual itself survives a pause or reload. */
final class DeepMines {
    private static final Map<ServerLevel, DeepMines> LOADED = new WeakHashMap<>();
    private final Map<UUID, Mine> mines = new HashMap<>();

    static DeepMines get(ServerLevel level) {
        return LOADED.computeIfAbsent(level, unused -> new DeepMines());
    }

    static void unload(ServerLevel level) {
        var sessions = LOADED.remove(level);
        if (sessions != null) {
            var current = new java.util.ArrayList<>(sessions.mines.values());
            sessions.mines.clear();
            current.forEach(Mine::release);
        }
    }

    Mine ensure(UUID id) { return mines.computeIfAbsent(id, Mine::new); }
    Mine find(UUID id) { return mines.get(id); }

    void remove(UUID id) {
        var mine = mines.remove(id);
        if (mine != null) mine.release();
    }

    static final class Mine {
        final UUID id;
        final MixedMine samples;
        private final Map<BlockEntity, BoreExcavation> bindings = new WeakHashMap<>();

        Mine(UUID id) {
            this.id = id;
            samples = new MixedMine(RandomSupport.mixStafford13(id.getMostSignificantBits()) ^ id.getLeastSignificantBits());
        }

        BoreExcavation bind(BlockEntity bore) {
            return bindings.computeIfAbsent(bore, unused -> new BoreExcavation());
        }

        void detach(BlockEntity bore) {
            samples.detach(bore);
            var excavation = bindings.remove(bore);
            if (excavation != null) excavation.cancel(bore);
        }

        private void release() {
            var current = new HashMap<>(bindings);
            bindings.clear();
            samples.release();
            current.forEach((bore, excavation) -> excavation.cancel(bore));
        }
    }
}
