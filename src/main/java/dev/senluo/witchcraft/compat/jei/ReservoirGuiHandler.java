package dev.senluo.witchcraft.compat.jei;

import dev.senluo.witchcraft.client.ReservoirScreen;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import java.util.List;

/** The main panel and folding upgrade rail occupy the same pixels for JEI and mouse input. */
final class ReservoirGuiHandler implements IGuiContainerHandler<ReservoirScreen> {
    static void register(IGuiHandlerRegistration registration){
        registration.addGuiContainerHandler(ReservoirScreen.class,new ReservoirGuiHandler());
        registration.addGuiScreenHandler(ReservoirScreen.class,screen->{
            var area=screen.panelBounds();var window=Minecraft.getInstance().getWindow();
            return new Bounds(ReservoirScreen.class,area.getX(),area.getY(),area.getWidth(),area.getHeight(),
                    window.getGuiScaledWidth(),window.getGuiScaledHeight());
        });
    }
    @Override public List<Rect2i> getGuiExtraAreas(ReservoirScreen screen){return List.of(screen.pluginBounds());}
    private record Bounds(Class<? extends Screen> screenClass,int guiLeft,int guiTop,int guiXSize,
                          int guiYSize,int screenWidth,int screenHeight) implements IGuiProperties {}
}
