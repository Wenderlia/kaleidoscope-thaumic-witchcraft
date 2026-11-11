package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.accessory.CastBarrier;
import dev.senluo.witchcraft.slash.SlashContent;
import dev.tc4port.thaumcraft.api.wand.pipeline.WandCastStages;
import dev.tc4port.thaumcraft.api.wand.pipeline.WandPipelineApi;
import dev.tc4port.thaumcraft.api.wand.pipeline.WandPipelineSide;
import dev.tc4port.thaumcraft.api.wand.pipeline.WandWorldStages;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;

/** Public host hooks provide lifetime and native resolved launch positions; vanilla owns effects. */
public final class FocusBarrier {
    public static void register() {
        ShockChain.register();
        WandPipelineApi.aroundAll(SlashContent.id("boundary_cast"), WandCastStages.EXECUTE, -1000,
                (use, next) -> CastBarrier.apply(use.player(), () -> next.proceed(use)));
        WandPipelineApi.aroundAll(SlashContent.id("boundary_impact"), WandWorldStages.IMPACT, -1000,
                (impact, next) -> impact.entity() instanceof Projectile projectile
                        ? CastBarrier.impact(projectile, projectile.getOwner(), () -> ShockFieldSight.during(impact, () -> next.proceed(impact)))
                        : next.proceed(impact));
        WandPipelineApi.aroundAll(SlashContent.id("boundary_block_aim"), WandCastStages.BLOCK_AIM,
                WandPipelineSide.SERVER, -1000, (query, next) -> constrain(next.proceed(query)));
        WandPipelineApi.aroundAll(SlashContent.id("boundary_living_aim"), WandCastStages.LIVING_AIM,
                WandPipelineSide.SERVER, -1000, (query, next) -> constrain(next.proceed(query)));
        WandPipelineApi.aroundAll(SlashContent.id("boundary_cast_origin"), WandCastStages.ORIGIN, -1000,
                (origin, next) -> {
                    var resolved = next.proceed(origin);
                    if (resolved != null) CastBarrier.origin(origin.wielder(), resolved);
                    return resolved;
                });
    }

    /** 20757 processors transform a query; the host still performs its own target search once. */
    private static WandCastStages.Aim constrain(WandCastStages.Aim query) {
        var eye = query.player().getEyePosition();
        CastBarrier.origin(query.player(), eye);
        var destination = query.selectedTarget().map(target -> target.getLocation())
                .orElseGet(() -> eye.add(query.player().getViewVector(1).scale(query.range())));
        var hit = CastBarrier.contact(query.player(), destination);
        if (hit == null) return query;
        if (query.selectedTarget().isPresent()) {
            // Search range is not a permission radius. An explicit override must be clipped itself.
            return query.withTarget(BlockHitResult.miss(hit.point(),
                    Direction.getNearest(hit.direction().x, hit.direction().y, hit.direction().z),
                    BlockPos.containing(hit.point())));
        }
        return query.withRange(Math.min(query.range(), Math.max(0, eye.distanceTo(hit.point()) - 1.0E-5)));
    }
    private FocusBarrier() {}
}
