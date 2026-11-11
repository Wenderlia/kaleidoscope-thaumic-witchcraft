package dev.senluo.witchcraft.client;

import dev.senluo.witchcraft.reservoir.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.fml.ModList;
import java.util.List;
import static dev.senluo.witchcraft.reservoir.ReservoirLayout.*;

/** Native inventory screen using the approved component atlas; only functional slots are visible. */
public final class ReservoirScreen extends AbstractContainerScreen<ReservoirMenu> {
    private double viewScale=1;
    private AbstractButton pluginTab;
    private int tabPressedButton=-1;
    public ReservoirScreen(ReservoirMenu menu,Inventory inventory,Component title){super(menu,inventory,title);imageWidth=menu.layout().width();imageHeight=HEIGHT;}
    protected void init(){
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
            }
            protected void renderWidget(GuiGraphics g,int mx,int my,float partial){
                if(isHoveredOrFocused())g.fill(getX(),getY(),getX()+getWidth(),getY()+getHeight(),0x30CBB58A);
                sprite(g,"upgrade_glyph",PLUGIN_GLYPH_X,PLUGIN_GLYPH_Y,12,12);
                int x=getX()+19,y=getY()+6;
                int edgeY=menu.pluginsVisible()?y+1:y;
                int tipY=menu.pluginsVisible()?y:y+1;
                g.fill(x,edgeY,x+1,edgeY+1,0xFFCBB58A);
                g.fill(x+2,edgeY,x+3,edgeY+1,0xFFCBB58A);
                g.fill(x+1,tipY,x+2,tipY+1,0xFFCBB58A);
            }
            protected void updateWidgetNarration(NarrationElementOutput output){defaultButtonNarrationText(output);}
        });
    }
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
        g.drawString(font,title,10,8,0xCBB58A,false);
    }
    protected void renderBg(GuiGraphics g,float partial,int mx,int my){
        frame(g,"plugin_rail",PLUGIN_PANEL_X,PLUGIN_PANEL_Y,PLUGIN_PANEL_WIDTH,menu.layout().pluginPanelHeight(menu.pluginsVisible()),20,4);
        frame(g,"panel_frame",0,0,imageWidth,HEIGHT,24,6);
        frame(g,"bay_frame",50,20,116+menu.layout().extraWidth(),99,16,3);
        frame(g,"bay_frame",menu.layout().inventoryX()-6,130,174,87,16,3);
        sprite(g,"input_glyph",INPUT_X+2,INPUT_Y-19,12,12);
        sprite(g,"output_glyph",menu.layout().outputX()+2,OUTPUT_Y-19,12,12);
        if(menu.pluginsVisible())for(int i=0;i<menu.layout().profile.plugins;i++)sprite(g,"upgrade_slot",PLUGIN_X-PLUGIN_SLOT_INSET,pluginY(i)-PLUGIN_SLOT_INSET,PLUGIN_SLOT_SIZE,PLUGIN_SLOT_SIZE);
        if(menu.pluginsVisible())for(int i=0;i<menu.layout().profile.plugins;i++){
            var plugin=menu.slots.get(ReservoirData.FIRST_PLUGIN_SLOT+i).getItem();
            if(plugin.getItem() instanceof ReservoirPluginItem item && item.canToggle()){
                int x=leftPos+PLUGIN_X,y=topPos+pluginY(i)+PLUGIN_SWITCH_OFFSET;
                g.fill(x,y,x+16,y+PLUGIN_SWITCH_HEIGHT,0xFF282722);
                g.fill(x+1,y+1,x+15,y+3,item.enabled(plugin)?0xFF9BD8D9:0xFF756555);
                int knob=item.enabled(plugin)?x+11:x+1;
                g.fill(knob,y,knob+4,y+4,0xFFE6C777);
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
                else g.blit(ReservoirContent.id("textures/gui/reservoir/liquid_neutral.png"),leftPos+lx,topPos+y+layout.liquidBottom()-fill,0,54-fill,lw,fill,20,54);
            }
            if(ModList.get().isLoaded("thaumcraft"))dev.senluo.witchcraft.compat.thaumcraft.client.ReservoirDisplay.glass(g,leftPos+x,topPos+y,w-4,layout.glassHeight());
            else sprite(g,"chamber_glass",x,y,w,h);
            sprite(g,"label_blank",x+(w-layout.labelWidth())/2,y+layout.labelY(),layout.labelWidth(),20);
            if(ModList.get().isLoaded("thaumcraft")){
                dev.senluo.witchcraft.compat.thaumcraft.client.ReservoirDisplay.icon(g,label.isEmpty()?aspect:label,
                        leftPos+x+(w-16)/2,topPos+y+layout.labelY()+2);
            }
            if(i==selected)sprite(g,"chamber_focus",x,y,w,h);
        }
    }
    private void sprite(GuiGraphics g,String name,int x,int y,int w,int h){g.blit(ReservoirContent.id("textures/gui/reservoir/"+name+".png"),leftPos+x,topPos+y,0,0,w,h,w,h);}
    private void frame(GuiGraphics g,String name,int x,int y,int w,int h,int size,int edge){
        int[] src={0,edge,size-edge,size},dx={x,x+edge,x+w-edge,x+w},dy={y,y+edge,y+h-edge,y+h};
        for(int row=0;row<3;row++)for(int col=0;col<3;col++)g.blit(ReservoirContent.id("textures/gui/reservoir/"+name+".png"),
                leftPos+dx[col],topPos+dy[row],dx[col+1]-dx[col],dy[row+1]-dy[row],src[col],src[row],src[col+1]-src[col],src[row+1]-src[row],size,size);
    }
    public boolean mouseClicked(double x,double y,int button){
        double mx=x/viewScale,my=y/viewScale;
        if(pluginTab.isMouseOver(mx,my)){
            tabPressedButton=button;
            if(pluginTab.mouseClicked(mx,my,button))setFocused(pluginTab);
            return true;
        }
        int cell=menu.layout().chamberAt(mx-leftPos,my-topPos);
        int pluginIndex=menu.pluginsVisible()?menu.layout().pluginSwitchAt(mx-leftPos,my-topPos):-1;
        if(pluginIndex>=0){
            var plugin=menu.slots.get(ReservoirData.FIRST_PLUGIN_SLOT+pluginIndex).getItem();
            if(plugin.getItem() instanceof ReservoirPluginItem item && item.canToggle()){
                tabPressedButton=button;
                if(button==0 && menu.getCarried().isEmpty() && minecraft.gameMode!=null && !isQuickCrafting && !isDragging())
                    minecraft.gameMode.handleInventoryButtonClick(menu.containerId,ReservoirMenu.TOGGLE_PLUGIN+ReservoirData.FIRST_PLUGIN_SLOT+pluginIndex);
                return true;
            }
        }
        if(button==0 && cell>=0 && minecraft.gameMode!=null){
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId,cell);return true;
        }
        return super.mouseClicked(mx,my,button);
    }
    public boolean mouseReleased(double x,double y,int button){
        // A tab press must never become an inventory drop when released outside the collapsed rail.
        if(button==tabPressedButton){
            tabPressedButton=-1;pluginTab.mouseReleased(x/viewScale,y/viewScale,button);setDragging(false);return true;
        }
        return super.mouseReleased(x/viewScale,y/viewScale,button);
    }
    public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        if(button==tabPressedButton)return true;
        return super.mouseDragged(x/viewScale,y/viewScale,button,dx/viewScale,dy/viewScale);
    }
    public boolean mouseScrolled(double x,double y,double dx,double dy){return super.mouseScrolled(x/viewScale,y/viewScale,dx,dy);}
    protected boolean hasClickedOutside(double x,double y,int guiLeft,int guiTop,int button){
        return super.hasClickedOutside(x,y,guiLeft,guiTop,button) && !menu.layout().pluginPanelContains(x-guiLeft,y-guiTop,menu.pluginsVisible());
    }
}
