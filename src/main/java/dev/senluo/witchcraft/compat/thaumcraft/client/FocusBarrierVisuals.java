package dev.senluo.witchcraft.compat.thaumcraft.client;

import com.mojang.datafixers.util.Unit;
import dev.senluo.witchcraft.accessory.PropagationField;
import dev.senluo.witchcraft.accessory.TurtleField;
import dev.senluo.witchcraft.client.BarrierEffects;
import dev.senluo.witchcraft.slash.SlashContent;
import dev.tc4port.thaumcraft.api.client.ThaumcraftClientRenderApi;
import dev.tc4port.thaumcraft.api.client.WandClientStages;
import dev.tc4port.thaumcraft.api.wand.pipeline.WandPipelineApi;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Visual half of the cast barrier; authoritative effect admission lives in FocusBarrier/CastBarrier. */
public final class FocusBarrierVisuals {
    private FocusBarrierVisuals() {}

    public static void chainBolt(dev.senluo.witchcraft.compat.thaumcraft.ShockBolt packet) {
        var level = Minecraft.getInstance().level;
        if (level == null || !(level.getEntity(packet.source()) instanceof LivingEntity source)) return;
        var target = level.getEntity(packet.target());
        if (target == null) return;
        var bolt = new WandClientStages.Bolt(dev.senluo.witchcraft.compat.thaumcraft.ShockChain.SHOCK,
                source, net.minecraft.world.InteractionHand.MAIN_HAND, target.getBoundingBox().getCenter(), 0, true);
        WandPipelineApi.invoke(WandClientStages.BOLT, bolt, effect -> {
            dev.tc4port.thaumcraft.api.client.ThaumcraftClientEffectsApi.spawnFocusLightning(level,
                    effect.source().getBoundingBox().getCenter(), effect.target());
            return Unit.INSTANCE;
        });
    }

    public static void register() {
        WandPipelineApi.aroundAll(SlashContent.id("boundary_target_presentation"), WandClientStages.PRESENTATION,
                1000, (request, next) -> {
                    var effect = request.presentation();
                    // Preserve launch audio and non-target cues. A blocked shockwave must not display a target hit.
                    if (effect.source() != null && effect.targetEntity().isPresent()
                            && effect.targetEntity().get() != effect.source()) {
                        var hit = contact(effect.source(), effect.origin(), effect.target(), effect.source().getUUID());
                        if (hit != null) return Unit.INSTANCE;
                    }
                    return next.proceed(request);
                });
        // Run after ordinary appearance transforms; do not replace the host renderer or retain continuations.
        WandPipelineApi.aroundAll(SlashContent.id("boundary_beam"), WandClientStages.BEAM, 1000, (beam, next) -> {
            Vec3 start = ThaumcraftClientRenderApi.resolveFocusCastOrigin(beam.source(), beam.hand(), beam.focus());
            var hit = contact(beam.source(), start, beam.target(), beam.operation().owner().getUUID());
            if (hit == null) return next.proceed(beam);
            BarrierEffects.beam(beam.source(), hit);
            if (start.distanceToSqr(hit.point()) < 1.0E-10) {
                // An emitter already inside another field has no visible travel. Remove by ORIGINAL identity.
                var key = beam.operation();
                ThaumcraftClientRenderApi.removeFocusBeam(key.effectId(), key.owner(), key.hand());
                return Unit.INSTANCE;
            }
            return next.proceed(new WandClientStages.Beam(beam.operation(), beam.carrier(), beam.effectId(),
                    beam.source(), beam.hand(), hit.point(), beam.rgb(), beam.endScale(), beam.impactTicks(),
                    beam.texture(), beam.reverse(), beam.focus()));
        });
        WandPipelineApi.aroundAll(SlashContent.id("boundary_bolt"), WandClientStages.BOLT, 1000, (bolt, next) -> {
            Vec3 start = bolt.chain() ? bolt.source().getBoundingBox().getCenter()
                    : ThaumcraftClientRenderApi.resolveFocusCastOrigin(bolt.source(), bolt.hand(), bolt.focus());
            // Chain visuals have no original-caster field: do not mistake a struck wearer for the caster.
            var hit = contact(bolt.source(), start, bolt.target(), bolt.chain() ? null : bolt.source().getUUID());
            if (hit == null) return next.proceed(bolt);
            BarrierEffects.beam(bolt.source(), hit);
            if (start.distanceToSqr(hit.point()) < 1.0E-10) return Unit.INSTANCE;
            return next.proceed(new WandClientStages.Bolt(bolt.operation(), bolt.focusId(), bolt.source(),
                    bolt.hand(), hit.point(), bolt.sparkleSpread(), bolt.chain()));
        });
    }

    private static PropagationField.Hit contact(LivingEntity source, Vec3 start, Vec3 end, java.util.UUID exempt) {
        if (source.level() != Minecraft.getInstance().level || !source.isAlive()
                || !Double.isFinite(start.lengthSqr()) || !Double.isFinite(end.lengthSqr())) return null;
        return TurtleField.rayContact(source.level(), start, end, exempt);
    }
}
