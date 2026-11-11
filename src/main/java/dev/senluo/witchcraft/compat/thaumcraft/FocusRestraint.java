package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.ritual.WorldRestraint;
import dev.senluo.witchcraft.slash.SlashContent;
import dev.tc4port.thaumcraft.api.focus.FocusApi;
import dev.tc4port.thaumcraft.api.focus.FocusId;
import dev.tc4port.thaumcraft.api.focus.FocusUpgradeId;
import dev.tc4port.thaumcraft.api.wand.pipeline.WandCastStages;
import dev.tc4port.thaumcraft.api.wand.pipeline.WandPipelineApi;
import dev.tc4port.thaumcraft.api.wand.pipeline.WandWorldStages;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;

public final class FocusRestraint {
    private static final ResourceLocation PROJECTILE = ResourceLocation.fromNamespaceAndPath("thaumcraft", "focus_projectile");
    private static final FocusId FIRE = FocusId.parse("focus_fire"), PRIMAL = FocusId.parse("focus_primal");
    private static final FocusUpgradeId IGNITION = FocusUpgradeId.parse("alchemistsfire");
    private static final String DESTRUCTIVE = "kaleidoscope_witchcraft:destructive_focus_projectile";

    static boolean focusProjectile(Entity entity) {
        return PROJECTILE.equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
    }

    public static void register() {
        // Classify before the native spawn posts EntityJoinLevelEvent and captures the owner's binding.
        WandPipelineApi.aroundAll(SlashContent.id("restraint_spawn"), WandCastStages.SPAWN, 900, (spawn, next) -> {
            if (!spawn.isClientSide() && focusProjectile(spawn.entity())) {
                var focus = spawn.focus().orElse(null);
                boolean destructive = PRIMAL.equals(focus) || FIRE.equals(focus) && FocusApi.upgradeLevel(spawn.carrier(), IGNITION) > 0;
                spawn.entity().getPersistentData().putBoolean(DESTRUCTIVE, destructive);
            }
            return next.proceed(spawn);
        });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((dev.tc4port.thaumcraft.api.event.TaintBiomeChangeEvent event) -> {
            if (WorldRestraint.denyDestructiveChange(event.level())) event.setCanceled(true);
        });
        WorldRestraint.registerCapability(projectile -> projectile.getPersistentData().getBoolean(DESTRUCTIVE));
        WandPipelineApi.aroundAll(SlashContent.id("restraint_impact"), WandWorldStages.IMPACT, -900,
                (impact, next) -> impact.entity() instanceof Projectile projectile && focusProjectile(projectile) && !impact.isClientSide()
                        ? WorldRestraint.during(projectile, WorldRestraint.projectile(projectile), () -> next.proceed(impact))
                        : next.proceed(impact));
        WandPipelineApi.aroundAll(SlashContent.id("restraint_primal_tick"), WandWorldStages.ENTITY_TICK, -900,
                (step, next) -> step.entity() instanceof Projectile projectile && step.focus().filter(PRIMAL::equals).isPresent()
                        && focusProjectile(projectile) && !step.isClientSide()
                        ? WorldRestraint.during(projectile, WorldRestraint.projectile(projectile), () -> next.proceed(step))
                        : next.proceed(step));
    }
    private FocusRestraint() {}
}
