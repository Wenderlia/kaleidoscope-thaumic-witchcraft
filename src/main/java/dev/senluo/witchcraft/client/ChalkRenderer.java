package dev.senluo.witchcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.ritual.ChalkMarks;
import dev.senluo.witchcraft.ritual.ChalkPattern;
import dev.senluo.witchcraft.ritual.RitualChalk;
import dev.senluo.witchcraft.ritual.RitualContent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import dev.senluo.witchcraft.time.TemporalContent;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** One flat, depth-tested face per symbol; no block entity or per-symbol tick. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID, value = Dist.CLIENT)
public final class ChalkRenderer {
    private static final int RANGE = 48;
    private static final float[][] CORNERS = {{0,0},{0,1},{1,1},{1,0}};
    // All ornaments/materials share a batch. Alpha preserves the actual supporting wood grain.
    private static final RenderType SURFACE = RenderType.entityTranslucent(ResourceLocation.fromNamespaceAndPath(
            KaleidoscopeWitchcraft.MOD_ID, "textures/ritual/chalk_atlas.png"));
    private ChalkRenderer() {}
    private static int material(BlockState state) {
        if (!RitualChalk.wood(state)) return 0;
        if (state.is(BlockTags.PLANKS)) return 4;
        if (state.hasProperty(BlockStateProperties.AXIS)) {
            Direction.Axis axis = state.getValue(BlockStateProperties.AXIS);
            return axis == Direction.Axis.Y ? 1 : axis == Direction.Axis.X ? 2 : 3;
        }
        return state.is(TemporalContent.CORE.get()) ? 1 : 4;
    }
    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
        Minecraft minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        if (level == null) return;
        var camera = event.getCamera().getPosition();
        BlockPos center = BlockPos.containing(camera);
        var buffers = minecraft.renderBuffers().bufferSource();
        PoseStack poses = event.getPoseStack();
        poses.pushPose();
        poses.translate(-camera.x, -camera.y, -camera.z);
        boolean used = false;
        for (int x = (center.getX()-RANGE)>>4; x <= (center.getX()+RANGE)>>4; x++) {
            for (int z = (center.getZ()-RANGE)>>4; z <= (center.getZ()+RANGE)>>4; z++) {
                net.minecraft.world.level.chunk.ChunkAccess candidate = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (!(candidate instanceof LevelChunk chunk)) continue;
                ChalkMarks marks = chunk.getExistingDataOrNull(RitualContent.CHALK_MARKS);
                if (marks == null || !marks.supported() || marks.entries().isEmpty()) continue;
                for (var entry : marks.entries().entrySet()) {
                    BlockPos pos = ChalkMarks.position(chunk.getPos(), entry.getKey());
                    if (level.isOutsideBuildHeight(pos) || pos.distToCenterSqr(camera.x, camera.y, camera.z) > RANGE*RANGE
                            || !event.getFrustum().isVisible(new AABB(pos))) continue;
                    var state = level.getBlockState(pos);
                    double surface = RitualChalk.surface(level, pos, state);
                    if (surface < 0 || entry.getValue().medium() == 1 && !RitualChalk.wood(state) || camera.y < pos.getY()+surface
                            || surface == 1 && level.getBlockState(pos.above()).isSolidRender(level, pos.above())) continue;
                    used = true;
                    var vertices = buffers.getBuffer(SURFACE);
                    int light = LevelRenderer.getLightColor(level, pos.above());
                    int cell = ChalkPattern.atlasCell(entry.getValue().medium() == 0 ? 0 : material(state),
                            entry.getValue().design(), entry.getValue().rotation());
                    draw(vertices, poses.last(), pos, (float)surface+.002F, cell, light);
                }
            }
        }
        poses.popPose();
        if (used) buffers.endBatch(SURFACE);
    }
    private static void draw(VertexConsumer vertices, PoseStack.Pose pose, BlockPos pos, float height, int cell, int light) {
        for (int i = 0; i < 4; i++) {
            float[] point = CORNERS[i];
            // Rotation is baked before grain treatment, so grain direction follows the wood, not the glyph.
            float u = (cell%ChalkPattern.ATLAS_COLUMNS + point[0])/ChalkPattern.ATLAS_COLUMNS;
            float v = (cell/ChalkPattern.ATLAS_COLUMNS + point[1])/ChalkPattern.ATLAS_ROWS;
            vertices.addVertex(pose, pos.getX()+point[0], pos.getY()+height, pos.getZ()+point[1])
                    .setColor(255,255,255,255).setUv(u,v)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose,0,1,0);
        }
    }
}
