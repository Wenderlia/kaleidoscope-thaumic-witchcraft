package dev.senluo.witchcraft.compat.jade;

import dev.senluo.witchcraft.time.TemporalContent;
import net.neoforged.fml.ModList;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/** Jade discovers this only when installed; TC types stay behind the optional integration boundary. */
@WailaPlugin
public final class WitchcraftJadePlugin implements IWailaPlugin {
    @Override public void register(IWailaCommonRegistration registration) {
        if (ModList.get().isLoaded("thaumcraft")) {
            CrucibleJadeProvider.register(registration);
            ConduitJadeProvider.register(registration);
            BoreJadeProvider.register(registration);
        }
    }
    @Override public void registerClient(IWailaClientRegistration registration) {
        // Jade's standard title/icon path uses the same grade-aware item as creative pick.
        registration.usePickedResult(TemporalContent.FRUIT.get());
        if (ModList.get().isLoaded("thaumcraft")) {
            CrucibleJadeProvider.registerClient(registration);
            ConduitJadeProvider.registerClient(registration);
            BoreJadeProvider.registerClient(registration);
        }
    }
}
