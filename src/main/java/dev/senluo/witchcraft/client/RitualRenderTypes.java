package dev.senluo.witchcraft.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class RitualRenderTypes {
    private record Key(RenderType source, int gray) {}
    private static final Map<Key, RenderType> TYPES = new LinkedHashMap<>(64, .75F, true);
    private static ShaderInstance shader;
    private RitualRenderTypes() {}

    @SubscribeEvent public static void register(RegisterShadersEvent event) throws IOException {
        clear(); RitualClient.reload();
        event.registerShader(new ShaderInstance(event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath(
                KaleidoscopeWitchcraft.MOD_ID, "ownership_entity"), DefaultVertexFormat.NEW_ENTITY), value -> shader = value);
    }
    public static void clear() { TYPES.clear(); }
    public static MultiBufferSource wrap(MultiBufferSource original, float gray) {
        int amount = Math.clamp(Math.round(gray * 100), 0, 100);
        if (amount == 0 || shader == null) return original;
        return type -> original.getBuffer(type.format() == DefaultVertexFormat.NEW_ENTITY ? type(type, amount) : type);
    }
    private static RenderType type(RenderType source, int amount) {
        Key key = new Key(source, amount);
        RenderType result = TYPES.get(key);
        if (result != null) return result;
        if (TYPES.size() >= 512) TYPES.remove(TYPES.keySet().iterator().next());
        result = new RenderType("ownership_gray", source.format(), source.mode(), source.bufferSize(),
                source.affectsCrumbling(), true, () -> {
                    // Preserve each original layer's texture, blending, depth and lightmap state.
                    source.setupRenderState();
                    ShaderInstance current = shader;
                    current.safeGetUniform("GrayAmount").set(amount / 100.0F);
                    RenderSystem.setShader(() -> current);
                }, source::clearRenderState) {};
        TYPES.put(key, result);
        return result;
    }
}
