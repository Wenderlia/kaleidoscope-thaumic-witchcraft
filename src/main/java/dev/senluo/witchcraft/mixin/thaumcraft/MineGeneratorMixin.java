package dev.senluo.witchcraft.mixin.thaumcraft;

import dev.senluo.witchcraft.compat.thaumcraft.MixedMine;
import dev.senluo.witchcraft.compat.thaumcraft.MineLevelFactory;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

/** Native level construction adapter; source lookup and sample bands remain in MixedMine. */
@Mixin(MinecraftServer.class)
public abstract class MineGeneratorMixin implements MineLevelFactory {
    @Shadow @Final protected LevelStorageSource.LevelStorageAccess storageSource;
    @Shadow @Final private Executor executor;
    @Shadow @Final private Map<ResourceKey<Level>,ServerLevel> levels;
    @Unique private final Set<ResourceKey<Level>> witchcraft$creatingMines = new HashSet<>();

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
            var border=server.overworld().getWorldBorder();
            mine.getWorldBorder().applySettings(border.createSettings());
            border.addListener(new BorderChangeListener.DelegateBorderChangeListener(mine.getWorldBorder()));
            // Publish before external load callbacks; the native server owns ticking, saving and closing.
            levels.put(key,mine);
            server.markWorldsDirty();
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.level.LevelEvent.Load(mine));
            return mine;
        } finally { witchcraft$creatingMines.remove(key); }
    }
    @WrapOperation(method = "createLevels", at = @At(value = "INVOKE", target = "Ljava/util/Map$Entry;getValue()Ljava/lang/Object;"))
    private Object witchcraft$mineGenerator(Map.Entry<?, ?> entry, Operation<Object> original) {
        if (entry.getKey() instanceof ResourceKey<?> key) {
            var source = MixedMine.source(key.location());
            if (source != null) {
                var server = (MinecraftServer)(Object)this;
                var live = server.getLevel(source);
                if (live != null) return new LevelStem(live.dimensionTypeRegistration(), live.getChunkSource().getGenerator());
                var stems = server.registryAccess()
                        .registryOrThrow(net.minecraft.core.registries.Registries.LEVEL_STEM);
                var stem = stems.get(ResourceKey.create(net.minecraft.core.registries.Registries.LEVEL_STEM, source.location()));
                if (stem != null) return stem;
            }
        }
        return original.call(entry);
    }
}
