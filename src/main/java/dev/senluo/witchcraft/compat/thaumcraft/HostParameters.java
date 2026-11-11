package dev.senluo.witchcraft.compat.thaumcraft;

import dev.tc4port.thaumcraft.api.data.GameplayParameter;
import net.minecraft.resources.ResourceLocation;

public final class HostParameters {
    public static double value(String path) {
        var id = ResourceLocation.fromNamespaceAndPath("thaumcraft", path);
        return GameplayParameter.findValue(id).orElseThrow(() -> new IllegalStateException("Missing TC gameplay parameter " + id));
    }
    private HostParameters() {}
}
