package dev.senluo.witchcraft.mixin;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class WitchcraftMixinPlugin implements IMixinConfigPlugin {
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith("dev.senluo.witchcraft.mixin.botania."))
            return LoadingModList.get().getModFileById("botania") != null;
        if (mixinClassName.startsWith("dev.senluo.witchcraft.mixin.draconic."))
            return LoadingModList.get().getModFileById("draconicevolution") != null;
        if (mixinClassName.startsWith("dev.senluo.witchcraft.mixin.mowzie."))
            return LoadingModList.get().getModFileById("mowziesmobs") != null;
        if (mixinClassName.startsWith("dev.senluo.witchcraft.mixin.tacz."))
            return LoadingModList.get().getModFileById("tacz") != null;
        if (mixinClassName.startsWith("dev.senluo.witchcraft.mixin.accessory.")) return true;
        if (mixinClassName.startsWith("dev.senluo.witchcraft.mixin.slash.")) return true;
        if (mixinClassName.startsWith("dev.senluo.witchcraft.mixin.curios."))
            return LoadingModList.get().getModFileById("curios") != null;
        if (mixinClassName.startsWith("dev.senluo.witchcraft.mixin.tavern."))
            return LoadingModList.get().getModFileById("kaleidoscope_tavern") != null;
        if (mixinClassName.startsWith("dev.senluo.witchcraft.mixin.brew.")) return true;
        if (mixinClassName.startsWith("dev.senluo.witchcraft.mixin.tiab.")) {
            LoadingModList mods = LoadingModList.get();
            return mods.getModFileById("kaleidoscope_tavern") != null
                    && mods.getModFileById("tiab") != null;
        }
        if (mixinClassName.startsWith("dev.senluo.witchcraft.mixin.time.")) return true;
        if (mixinClassName.startsWith("dev.senluo.witchcraft.mixin.thaumcraft."))
            return LoadingModList.get().getModFileById("thaumcraft") != null;
        if (mixinClassName.startsWith("dev.senluo.witchcraft.mixin.ritual.")) return true;
        throw new IllegalArgumentException("Unknown mixin group: " + mixinClassName);
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
