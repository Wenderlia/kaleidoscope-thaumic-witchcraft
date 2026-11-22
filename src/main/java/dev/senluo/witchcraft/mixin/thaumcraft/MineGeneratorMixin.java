package dev.senluo.witchcraft.mixin.thaumcraft;

import dev.senluo.witchcraft.compat.thaumcraft.MineLevelFactory;
import dev.senluo.witchcraft.compat.thaumcraft.MixedMine;
import dev.senluo.witchcraft.compat.thaumcraft.MineStorageCleanup;
import dev.senluo.witchcraft.worldgen.LocalGeneration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.LinkedHashSet;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelResource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

/** Native level construction adapter; source lookup and sample bands remain in MixedMine. */
@Mixin(MinecraftServer.class)
public abstract class MineGeneratorMixin implements MineLevelFactory {
    @Shadow @Final protected LevelStorageSource.LevelStorageAccess storageSource;
    @Shadow @Final private Executor executor;
    @Shadow @Final private Map<ResourceKey<Level>,ServerLevel> levels;
    @Unique private final Set<ResourceKey<Level>> witchcraft$creatingMines = new HashSet<>();

    @Inject(method = "createLevels", at = @At("HEAD"))
    private void witchcraft$cleanOldMineStorage(CallbackInfo ci) {
        MineStorageCleanup.clean(storageSource.getLevelPath(LevelResource.ROOT));
    }

    @WrapOperation(method = "createLevels", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Registry;entrySet()Ljava/util/Set;"))
    private Set<Map.Entry<ResourceKey<LevelStem>, LevelStem>> witchcraft$lazyCarriers(Registry<LevelStem> registry,
            Operation<Set<Map.Entry<ResourceKey<LevelStem>, LevelStem>>> original) {
        // level.dat can retain removed datapack dimensions. All disposable carriers use the same lazy factory.
        var entries = new LinkedHashSet<>(original.call(registry));
        entries.removeIf(entry -> MixedMine.source(entry.getKey().location()) != null);
        return entries;
    }

    @Override public boolean witchcraft$isCreatingMine(ResourceKey<Level> key) {
        return witchcraft$creatingMines.contains(key);
    }

    @Override public ServerLevel witchcraft$mineLevel(ServerLevel source, ResourceKey<Level> key) {
        MinecraftServer server=(MinecraftServer)(Object)this;
        if (!server.isSameThread()) throw new IllegalStateException("Mine levels must be constructed on the server thread");
        ServerLevel existing=levels.get(key);
        if (existing != null) return existing;
        // Constructor/load events may reenter this factory. A caller retries an unfinished creation.
        if (!witchcraft$creatingMines.add(key)) return null;
        try {
            var data=server.getWorldData();
            var stem=new LevelStem(source.dimensionTypeRegistration(),source.getChunkSource().getGenerator());
            ServerLevel mine=new ServerLevel(server,executor,storageSource,new DerivedLevelData(data,data.overworldData()),
                    key,stem,MineLevelFactory.QuietProgress.INSTANCE,data.isDebugWorld(),BiomeManager.obfuscateSeed(source.getSeed()),
                    List.of(),false,server.overworld().getRandomSequences());
            LocalGeneration.attach(mine);
            // Publish before external load callbacks; the native server owns ticking, saving and closing.
            levels.put(key,mine);
            server.markWorldsDirty();
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.level.LevelEvent.Load(mine));
            return mine;
        } finally { witchcraft$creatingMines.remove(key); }
    }
}
