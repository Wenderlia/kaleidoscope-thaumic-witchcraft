package dev.senluo.witchcraft.ritual;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Feedback for committed actions only; material-specific voices live in OfferingSounds. */
public final class RitualSounds {
    private record Pulse(long tick, java.util.Set<BlockPos> positions) {}
    private static final java.util.Map<ServerLevel, Pulse> PULSES = new java.util.WeakHashMap<>();
    private RitualSounds() {}

    public static void chalk(Level level, BlockPos pos) {
        if (level instanceof ServerLevel server)
            server.playSound(null, pos, RitualContent.CHALK_SOUND.get(), SoundSource.BLOCKS, 1F, 1F);
    }

    public static void absorb(ServerLevel level, Vec3 pos) {
        // Witchery's RANDOM_POP is vanilla random.pop, now ITEM_PICKUP.
        float pitch = .4F / (.8F + level.random.nextFloat() * .4F);
        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, .8F, pitch);
    }

    public static void heartInteraction(ServerLevel level, BlockPos pos, boolean rejected) {
        if (rejected) level.playSound(null, pos, SoundEvents.NOTE_BLOCK_SNARE.value(), SoundSource.BLOCKS,
                .5F, .4F / (.8F + level.random.nextFloat() * .4F));
        else level.playSound(null, pos, RitualContent.HEART_CLICK.get(), SoundSource.BLOCKS, .4F, 1F);
    }

    public static void infusionPulse(ServerLevel level, BlockPos pos) {
        var pulse = PULSES.compute(level, (unused, previous) -> previous != null && previous.tick() == level.getGameTime()
                ? previous : new Pulse(level.getGameTime(), new java.util.HashSet<>()));
        if (!pulse.positions().add(pos.immutable())) return; // One audible pulse for sessions settling together.
        // RiteInfusionRecharge uses NOTE_PLING after replenishment, not the
        // RANDOM_FIZZ used by initial infusion. Preserve its low, varied pitch.
        float pitch = .4F / (.8F + level.random.nextFloat() * .4F);
        level.playSound(null, pos, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.BLOCKS, .8F, pitch);
    }
}
