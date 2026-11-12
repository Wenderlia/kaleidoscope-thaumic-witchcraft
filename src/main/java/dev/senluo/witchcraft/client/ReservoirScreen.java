package dev.senluo.witchcraft.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.senluo.witchcraft.reservoir.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.fml.ModList;
import net.minecraft.client.renderer.Rect2i;
import org.anti_ad.mc.ipn.api.IPNIgnore;
import org.anti_ad.mc.ipn.api.IPNGuiHint;
import org.anti_ad.mc.ipn.api.IPNButton;
import java.util.List;
import static dev.senluo.witchcraft.reservoir.ReservoirLayout.*;

/** Native inventory screen using the approved component atlas; only functional slots are visible. */
@IPNIgnore
@IPNGuiHint(button=IPNButton.SETTINGS,hide=true)
@IPNGuiHint(button=IPNButton.SHOW_EDITOR,hide=true)
public final class ReservoirScreen extends AbstractContainerScreen<ReservoirMenu> {
    private double viewScale=1;
    private AbstractButton pluginTab;
    private int controlPressedButton=-1;
    private final ReservoirGuiSkin skin;
    public ReservoirScreen(ReservoirMenu menu,Inventory inventory,Component title){super(menu,inventory,title);imageWidth=menu.layout().width();imageHeight=HEIGHT;skin=ReservoirGuiSkin.of(menu.layout().profile);}
    protected void init(){
        controlPressedButton=-1;
        // Reinitialization must start from physical GUI dimensions, not the previous logical size.
        width=minecraft.getWindow().getGuiScaledWidth();height=minecraft.getWindow().getGuiScaledHeight();
        viewScale=Math.min(1,Math.min((width-8)/(double)(imageWidth+EXTRA_LEFT),(height-8)/(double)HEIGHT));
        width=(int)(width/viewScale);height=(int)(height/viewScale);super.init();
        leftPos=(width-imageWidth+EXTRA_LEFT)/2;
        pluginTab=addRenderableWidget(new AbstractButton(leftPos+PLUGIN_TAB_X,topPos+PLUGIN_TAB_Y,
                PLUGIN_TAB_WIDTH,PLUGIN_TAB_HEIGHT,Component.empty()){
            public void onPress(){
                if(isQuickCrafting || ReservoirScreen.this.isDragging())return;
                menu.setPluginsVisible(!menu.pluginsVisible());
                hoveredSlot=null;quickCraftSlots.clear();clearDraggingState();
                super.playDownSound(minecraft.getSoundManager());
            }
            // AbstractWidget normally plays before onPress; rejected drags must stay silent.
            @Override public void playDownSound(net.minecraft.client.sounds.SoundManager sounds){}
            protected void renderWidget(GuiGraphics g,int mx,int my,float partial){
                if(isHoveredOrFocused())g.fill(getX(),getY(),getX()+getWidth(),getY()+getHeight(),0x30CBB58A);
                sprite(g,"upgrade_glyph",PLUGIN_GLYPH_X,PLUGIN_GLYPH_Y,12,12);
                sprite(g,menu.pluginsVisible()?"fold_up":"fold_down",PLUGIN_FOLD_X,PLUGIN_FOLD_Y,PLUGIN_FOLD_WIDTH,PLUGIN_FOLD_HEIGHT);
            }
            protected void updateWidgetNarration(NarrationElementOutput output){defaultButtonNarrationText(output);}
        });
    }
    /** Export physical GUI pixels, not the logical pixels used inside the scaled artwork. */
    public Rect2i panelBounds(){return screenBounds(0,0,imageWidth,HEIGHT);}
    public Rect2i pluginBounds(){return screenBounds(PLUGIN_PANEL_X,PLUGIN_PANEL_Y,PLUGIN_PANEL_WIDTH,menu.layout().pluginPanelHeight(menu.pluginsVisible()));}
    private Rect2i screenBounds(int x,int y,int w,int h){
        int l=(int)Math.floor((leftPos+x)*viewScale),t=(int)Math.floor((topPos+y)*viewScale);
        return new Rect2i(l,t,(int)Math.ceil((leftPos+x+w)*viewScale)-l,(int)Math.ceil((topPos+y+h)*viewScale)-t);
    }
    @Override public int getGuiLeft(){return panelBounds().getX();}
    @Override public int getGuiTop(){return panelBounds().getY();}
    @Override public int getXSize(){return panelBounds().getWidth();}
    @Override public int getYSize(){return panelBounds().getHeight();}
    public void render(GuiGraphics g,int mx,int my,float partial){
        g.pose().pushPose();g.pose().scale((float)viewScale,(float)viewScale,1);
        super.render(g,(int)(mx/viewScale),(int)(my/viewScale),partial);
        int logicalX=(int)(mx/viewScale),logicalY=(int)(my/viewScale);
        renderTooltip(g,logicalX,logicalY);
        int cell=menu.layout().chamberAt(logicalX-leftPos,logicalY-topPos);
        if(cell>=0 && menu.getCarried().isEmpty() && !menu.snapshot().isEmpty()){
            var data=ReservoirData.read(menu.snapshot());int amount=ReservoirData.amount(data,cell);
            if(amount==0)g.renderComponentTooltip(font,List.of(Component.translatable("gui.kaleidoscope_witchcraft.reservoir.empty")),logicalX,logicalY);
            else{
                String aspect=ReservoirData.aspect(data,cell);
                Component name=ModList.get().isLoaded("thaumcraft")?dev.senluo.witchcraft.compat.thaumcraft.client.ReservoirDisplay.name(aspect):Component.literal(aspect);
                int color=ModList.get().isLoaded("thaumcraft")?dev.senluo.witchcraft.compat.thaumcraft.client.ReservoirDisplay.color(aspect):0xFFFFFF;
                g.renderComponentTooltip(font,List.of(name.copy().withStyle(style->style.withColor(color)),
                        Component.literal(amount+" / "+data.getInt("capacity")).withStyle(net.minecraft.ChatFormatting.GRAY)),logicalX,logicalY);
            }
        }
        g.pose().popPose();
    }
    protected void renderLabels(GuiGraphics g,int mx,int my){
        g.drawString(font,title,10,8,skin.titleColor,false);
    }
    protected void renderBg(GuiGraphics g,float partial,int mx,int my){
        sprite(g,menu.pluginsVisible()?"rail_open":"rail_closed",PLUGIN_PANEL_X,PLUGIN_PANEL_Y,PLUGIN_PANEL_WIDTH,menu.layout().pluginPanelHeight(menu.pluginsVisible()));
        sprite(g,"panel",0,0,imageWidth,HEIGHT);
        sprite(g,"input_glyph",INPUT_X+2,INPUT_Y-19,12,12);
        sprite(g,"output_glyph",menu.layout().outputX()+2,OUTPUT_Y-19,12,12);
        if(menu.pluginsVisible())for(int i=0;i<menu.layout().profile.plugins;i++)sprite(g,"upgrade_slot",PLUGIN_X-PLUGIN_SLOT_INSET,pluginY(i)-PLUGIN_SLOT_INSET,PLUGIN_SLOT_SIZE,PLUGIN_SLOT_SIZE);
        if(menu.pluginsVisible())for(int i=0;i<menu.layout().profile.plugins;i++){
            var plugin=menu.slots.get(ReservoirData.FIRST_PLUGIN_SLOT+i).getItem();
            if(plugin.getItem() instanceof ReservoirPluginItem item && item.canToggle()){
                sprite(g,item.enabled(plugin)?"switch_on":"switch_off",PLUGIN_X,pluginY(i)+PLUGIN_SWITCH_OFFSET,16,PLUGIN_SWITCH_HEIGHT);
            }
        }
        sprite(g,"transfer_dock",INPUT_X-5,INPUT_Y-5,26,26);
        sprite(g,"transfer_dock",menu.layout().outputX()-5,OUTPUT_Y-5,26,26);
        for(int row=0;row<3;row++)for(int col=0;col<9;col++)sprite(g,"slot",menu.layout().inventoryX()-1+18*col,INVENTORY_Y-1+18*row,18,18);
        for(int col=0;col<9;col++)sprite(g,"slot",menu.layout().inventoryX()-1+18*col,HOTBAR_Y-1,18,18);
        var data=ReservoirData.read(menu.snapshot());int capacity=data.getInt("capacity");
        int selected=ReservoirData.selected(data,menu.layout().profile.cells);
        for(int i=0;i<menu.layout().profile.cells;i++){
            var layout=menu.layout();int x=layout.chamberX(i),y=layout.chamberY();String aspect=ReservoirData.aspect(data,i),label=ReservoirData.label(data,i);
            int w=layout.chamberWidth(),h=layout.chamberHeight(),lw=layout.liquidWidth(),lx=x+(w-lw)/2;
            sprite(g,"chamber_body",x,y,w,h);
            int fill=capacity<=0?0:(int)Math.min(layout.liquidHeight(),(long)layout.liquidHeight()*ReservoirData.amount(data,i)/capacity);
            if(fill>0){
                if(ModList.get().isLoaded("thaumcraft"))
                    dev.senluo.witchcraft.compat.thaumcraft.client.ReservoirDisplay.liquid(g,aspect,leftPos+lx,topPos+y+layout.liquidBottom()-fill,lw,fill);
                else g.blit(skin.texture("liquid_neutral"),leftPos+lx,topPos+y+layout.liquidBottom()-fill,0,layout.liquidHeight()-fill,lw,fill,lw,layout.liquidHeight());
            }
            sprite(g,"chamber_glass",x,y,w,h);
            sprite(g,"label_blank",x+(w-layout.labelWidth())/2,y+layout.labelY(),layout.labelWidth(),20);
            if(ModList.get().isLoaded("thaumcraft")){
                dev.senluo.witchcraft.compat.thaumcraft.client.ReservoirDisplay.icon(g,label.isEmpty()?aspect:label,
                        leftPos+x+(w-16)/2,topPos+y+layout.labelY()+2);
            }
            if(i==selected)sprite(g,"chamber_focus",x,y,w,h);
        }
    }
    private void sprite(GuiGraphics g,String name,int x,int y,int w,int h){
        // Plain 1.21.1 texture blits do not own alpha blending. TC's icon/liquid
        // draw may disable it, so each native layer restores its own draw state.
        g.flush();RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
        try{g.blit(skin.texture(name),leftPos+x,topPos+y,0,0,w,h,w,h);}
        finally{RenderSystem.disableBlend();}
    }
    public boolean mouseClicked(double x,double y,int button){
        double mx=x/viewScale,my=y/viewScale;
        if(!isQuickCrafting && !isDragging() && pressControl(mx,my,button))return true;
        return super.mouseClicked(mx,my,button);
    }
    /** Controls take priority; native inventory input owns all other clicks and ongoing drags. */
    private boolean pressControl(double mx,double my,int button){
        if(pluginTab.isMouseOver(mx,my)){
            controlPressedButton=button;
            if(pluginTab.mouseClicked(mx,my,button))setFocused(pluginTab);
            return true;
        }
        int cell=menu.layout().chamberAt(mx-leftPos,my-topPos);
        int pluginIndex=menu.pluginsVisible()?menu.layout().pluginSwitchAt(mx-leftPos,my-topPos):-1;
        if(pluginIndex>=0){
            var plugin=menu.slots.get(ReservoirData.FIRST_PLUGIN_SLOT+pluginIndex).getItem();
            if(plugin.getItem() instanceof ReservoirPluginItem item && item.canToggle()){
                controlPressedButton=button;
                if(menu.getCarried().isEmpty())sendControl(ReservoirActions.pluginButton(pluginIndex),button);
                return true;
            }
        }
        if(cell>=0){
            controlPressedButton=button;
            sendControl(cell,button);
            return true;
        }
        return false;
    }
    private void sendControl(int id,int button){
        if(button==0 && minecraft.gameMode!=null)minecraft.gameMode.handleInventoryButtonClick(menu.containerId,id);
    }
    public boolean mouseReleased(double x,double y,int button){
        // A control press must never become an inventory drop when released outside the panel.
        if(button==controlPressedButton){
            controlPressedButton=-1;pluginTab.mouseReleased(x/viewScale,y/viewScale,button);setDragging(false);return true;
        }
        return super.mouseReleased(x/viewScale,y/viewScale,button);
    }
    public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        if(button==controlPressedButton)return true;
        return super.mouseDragged(x/viewScale,y/viewScale,button,dx/viewScale,dy/viewScale);
    }
    public boolean mouseScrolled(double x,double y,double dx,double dy){return super.mouseScrolled(x/viewScale,y/viewScale,dx,dy);}
    @Override public void mouseMoved(double x,double y){super.mouseMoved(x/viewScale,y/viewScale);}
    protected boolean hasClickedOutside(double x,double y,int guiLeft,int guiTop,int button){
        return super.hasClickedOutside(x,y,guiLeft,guiTop,button) && !menu.layout().pluginPanelContains(x-guiLeft,y-guiTop,menu.pluginsVisible());
    }
}
