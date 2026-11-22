package dev.senluo.witchcraft.client;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mojang.logging.LogUtils;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SlashAppearanceResources {
    private static final ResourceLocation FILE =
            ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID, "visuals/ordinary_slash.json");
    private static final TypeAdapter<JsonElement> JSON_VALUE = new Gson().getAdapter(JsonElement.class);
    private static final SlashAppearance BUNDLED = bundled();
    private static volatile SlashAppearance current = BUNDLED;

    private SlashAppearanceResources() {}

    public static SlashAppearance current() { return current; }

    @SubscribeEvent
    public static void reload(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) SlashAppearanceResources::load);
    }

    private static void load(ResourceManager resources) {
        try (var input = resources.getResourceOrThrow(FILE).openAsReader()) {
            current = parse(input);
        } catch (IOException | JsonParseException | IllegalArgumentException ex) {
            current = BUNDLED;
            LogUtils.getLogger().warn("Cannot load {}; using the bundled slash appearance", FILE, ex);
        }
    }

    private static SlashAppearance bundled() {
        String path = "/assets/" + FILE.getNamespace() + "/" + FILE.getPath();
        try (var input = SlashAppearanceResources.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("Missing bundled resource " + path);
            return parse(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException | JsonParseException | IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid bundled slash appearance " + path, ex);
        }
    }

    private static JsonObject object(JsonElement value, Set<String> keys, String name) {
        if (value == null || !value.isJsonObject() || !value.getAsJsonObject().keySet().equals(keys))
            throw new IllegalArgumentException("Expected " + keys + " in " + name);
        return value.getAsJsonObject();
    }

    private static SlashAppearance parse(Reader input) throws IOException {
        var reader = new JsonReader(input);
        var root = object(JSON_VALUE.read(reader), Set.of("bands"), FILE.toString());
        if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing slash appearance data");
        var bands = object(root.get("bands"), Set.copyOf(SlashAppearance.LAYERS), "bands");
        var fields = Set.copyOf(SlashAppearance.FIELDS);
        for (String id : SlashAppearance.LAYERS) object(bands.get(id), fields, id);
        return SlashAppearance.read((id, key) -> {
            var value = bands.getAsJsonObject(id).get(key);
            if (!value.isJsonPrimitive() || ("color".equals(key)
                    ? !value.getAsJsonPrimitive().isString() : !value.getAsJsonPrimitive().isNumber()))
                throw new IllegalArgumentException("Invalid value for " + id + "." + key);
            return value.getAsString();
        });
    }
}
