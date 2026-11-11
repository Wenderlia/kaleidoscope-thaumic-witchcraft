package dev.senluo.witchcraft.gametest;

import dev.senluo.witchcraft.compat.thaumcraft.SuctionPresentation;
import dev.tc4port.thaumcraft.network.ActionPresentationPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class SuctionPresentationGameTests {
    @GameTest(template = "neoforge:empty", timeoutTicks = 5)
    public static void suctionColorsPassNativePayloadAndWireCodec(GameTestHelper helper) {
        Vec3 origin = new Vec3(0.5, 64.5, 0.5), target = new Vec3(12.5, 76.5, -11.5);
        boolean rejected = false;
        try {
            packet(origin, target, 0, 0xFFFFFF);
        } catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, "The old RGB-in-value call must reproduce the native range violation");
        for (int rgb : new int[]{0, 1, 0x0FFFFF, 0x100000, 0xFF0000, 0x00FF00, 0x0000FF, 0xB7D6C8, 0xFFFFFF, 0xFFB7D6C8}) {
            var input = packet(origin, target, SuctionPresentation.colorDetail(rgb), SuctionPresentation.colorValue(rgb));
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
            try {
                ActionPresentationPayload.STREAM_CODEC.encode(buffer, input);
                var output = ActionPresentationPayload.STREAM_CODEC.decode(buffer);
                helper.assertTrue(SuctionPresentation.decodeColor(output.detail(), output.value()) == (rgb & 0xFFFFFF),
                        "Native wire round-trip must preserve all 24 RGB bits");
                helper.assertTrue(output.origin().equals(origin) && output.target().equals(target), "Transfer endpoints must survive encoding");
            } finally { buffer.release(); }
        }
        helper.assertTrue(SuctionPresentation.decodeColor(Double.NaN, 0) == -1
                && SuctionPresentation.decodeColor(256, 0) == -1
                && SuctionPresentation.decodeColor(0.5, 0) == -1
                && SuctionPresentation.decodeColor(0, 65536) == -1, "Malformed cosmetic data must be rejected");
        helper.succeed();
    }

    private static ActionPresentationPayload packet(Vec3 origin, Vec3 target, double detail, int value) {
        return new ActionPresentationPayload(SuctionPresentation.ID, -1, InteractionHand.MAIN_HAND,
                origin, origin, target, -1, detail, value, 42L, false, java.util.Optional.empty());
    }
}
