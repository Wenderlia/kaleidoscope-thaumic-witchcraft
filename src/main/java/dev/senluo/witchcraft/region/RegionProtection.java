package dev.senluo.witchcraft.region;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/** Native operations call the same spatial rules. This adapter only vetoes, never grants permissions. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID)
public final class RegionProtection {
    public static final String EXPLOSION_DEBRIS = "kaleidoscope_witchcraft:explosion_debris";
    public static boolean mobSource(Entity actor) {
        for (int depth = 0; actor != null && depth < 8; depth++) {
            if (actor instanceof Mob) return true;
            if (actor instanceof TraceableEntity traceable) {
                Entity owner = traceable.getOwner();
                if (owner == actor) return false;
                actor = owner;
            } else return false;
        }
        return false;
    }
    public static boolean mobEdit(Level level, BlockPos target, Entity actor) {
        return level instanceof ServerLevel server && mobSource(actor)
                && RegionRules.applies(server, target, RegionRules.NO_MOB_BLOCK_CHANGES);
    }
    /** Public compatibility seam for custom batch explosions, including edits which bypass Level.setBlock. */
    public static boolean explosionEdit(Level level, BlockPos target) {
        return level instanceof ServerLevel server && RegionRules.applies(server, target, RegionRules.NO_EXPLOSION_BLOCK_CHANGES);
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void detonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel server)) return;
        // Works even when the explosion origin is outside, and also removes its native fire candidates.
        event.getAffectedBlocks().removeIf(pos -> explosionEdit(server, pos));
    }
    @SubscribeEvent public static void destroy(LivingDestroyBlockEvent event) {
        if (mobEdit(event.getEntity().level(), event.getPos(), event.getEntity())) event.setCanceled(true);
    }
    @SubscribeEvent public static void place(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof Level level)) return;
        if (event instanceof BlockEvent.EntityMultiPlaceEvent multi) {
            for (var snapshot : multi.getReplacedBlockSnapshots()) if (mobEdit(level, snapshot.getPos(), event.getEntity())) {
                event.setCanceled(true); return;
            }
        }
        if (mobEdit(level, event.getPos(), event.getEntity())) event.setCanceled(true);
    }
    @SubscribeEvent public static void trample(BlockEvent.FarmlandTrampleEvent event) {
        if (event.getLevel() instanceof Level level && mobEdit(level, event.getPos(), event.getEntity())) event.setCanceled(true);
    }
    private RegionProtection() {}
}
