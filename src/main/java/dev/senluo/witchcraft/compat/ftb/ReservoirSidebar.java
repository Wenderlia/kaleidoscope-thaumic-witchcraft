package dev.senluo.witchcraft.compat.ftb;

import dev.ftb.mods.ftblibrary.api.client.FTBLibraryClientApi;
import dev.senluo.witchcraft.client.ReservoirScreen;

/** FTB owns both rendering and mouse dispatch for its sidebar. Opt out through that owner. */
public final class ReservoirSidebar {
    public static void register() {
        FTBLibraryClientApi.get().addSidebarScreenBlacklist(ReservoirScreen.class.getName());
    }
    private ReservoirSidebar() {}
}
