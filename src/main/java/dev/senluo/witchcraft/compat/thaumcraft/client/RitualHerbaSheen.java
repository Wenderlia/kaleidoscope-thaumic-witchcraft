package dev.senluo.witchcraft.compat.thaumcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.tc4port.thaumcraft.api.client.*;
import dev.senluo.witchcraft.ritual.RitualHerba;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;

/** Surface-only moving highlight. Uses the host's exact texture, material, masking and batch ordering. */
public final class RitualHerbaSheen {
    private static final int GRID = 8;
    private RitualHerbaSheen() {}
    private static final dev.tc4port.thaumcraft.api.aspect.AspectId HERBA = dev.tc4port.thaumcraft.api.aspect.AspectId.parse("herba");
    private static final net.minecraft.resources.ResourceLocation MARK = dev.senluo.witchcraft.slash.SlashContent.id("ritual_herba");

    public static void register() {
        GogglesRenderApi.registerDecoration(MARK, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("thaumcraft", "textures/aspects/herba.png"),
                (entry, poses, vertices) -> draw(vertices, poses.last()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(RitualHerbaSheen::entries);
    }

    private static void entries(GogglesEntriesEvent event) {
        int marked = RitualHerba.count(event.target());
        if (marked == 0) return;
        var entries = new java.util.ArrayList<GogglesEntry>();
        for (var entry : event.entries()) {
            if (marked == 0 || !entry.aspect().equals(HERBA)) { entries.add(entry); continue; }
            int count = Math.min(marked, entry.amount());
            var decorations = new java.util.HashSet<>(entry.decorations());
            decorations.add(MARK);
            entries.add(new GogglesEntry(HERBA, count, decorations));
            if (entry.amount() > count) entries.add(new GogglesEntry(HERBA, entry.amount() - count, entry.decorations()));
            marked -= count;
        }
        event.setEntries(entries);
    }

    private static void draw(VertexConsumer vertices, PoseStack.Pose pose) {
        var minecraft = Minecraft.getInstance();
        double time = minecraft.level == null ? 0 : minecraft.level.getGameTime() % 100L
                + minecraft.getTimer().getGameTimeDeltaPartialTick(false);
        float front = -0.3F + (float)(time / 100.0) * 2.3F;
        // One visible glyph: 64 small quads. No offscreen buffer, particles, scans or texture copies.
        for (int y = 0; y < GRID; y++) for (int x = 0; x < GRID; x++) {
            float u0 = x / (float)GRID, u1 = (x + 1) / (float)GRID;
            float v0 = y / (float)GRID, v1 = (y + 1) / (float)GRID;
            vertex(vertices, pose, u0, v1, front);
            vertex(vertices, pose, u1, v1, front);
            vertex(vertices, pose, u1, v0, front);
            vertex(vertices, pose, u0, v0, front);
        }
    }
    private static void vertex(VertexConsumer vertices, PoseStack.Pose pose, float u, float v,
                               float front) {
        float t = Math.max(0F, 1F - Math.abs(u + v * 0.65F - front) / 0.18F);
        float shine = t * t * (3F - 2F * t) * 0.85F;
        vertices.addVertex(pose, u - 0.5F, v - 0.5F, 0.001F)
                .setColor(240, 255, 210, Math.round(191 * shine))
                .setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, 0, 0, 1);
    }
}
