package dev.senluo.witchcraft.combat;

import net.minecraft.world.damagesource.DamageSource;
import org.jetbrains.annotations.Nullable;

/** Read-only view of the current nested hurt call; never the previous hit's damage source. */
public interface DamageContext {
    @Nullable DamageSource witchcraft$currentDamageSource();
}
