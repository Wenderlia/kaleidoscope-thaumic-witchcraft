package dev.senluo.witchcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.senluo.witchcraft.reservoir.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.world.item.*;
import net.neoforged.fml.ModList;

public final class ReservoirRenderer extends BlockEntityWithoutLevelRenderer implements BlockEntityRenderer<ReservoirBlockEntity> {
    public ReservoirRenderer(){super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),Minecraft.getInstance().getEntityModels());}
    public void renderByItem(ItemStack stack,ItemDisplayContext context,PoseStack poses,MultiBufferSource buffers,int light,int overlay){
        poses.pushPose();
        try{
            boolean gui=context==ItemDisplayContext.GUI;
            // Undo the vanilla item origin before applying model-derived GUI-only bounds.
            if(gui)poses.translate(.5,.5,.5);
            if(stack.getItem() instanceof ReservoirTransducerItem item){
                if(gui)ReservoirGuiPose.converter(poses);
                else {poses.translate(.1,.5,.5);poses.scale(4,4,4);}
                converter(poses,buffers,light,overlay,0xFFFFFFFF,item.material());
            }else if(stack.getItem() instanceof FrostModuleItem){
                if(gui)ReservoirGuiPose.FROST.apply(poses);
                else {poses.translate(.1,.5,.5);poses.scale(4,4,4);}
                frost(poses,buffers,light,overlay);
            }else{
                if(context.firstPerson() || context==ItemDisplayContext.THIRD_PERSON_RIGHT_HAND || context==ItemDisplayContext.THIRD_PERSON_LEFT_HAND){
                    var grip=ReservoirGrip.of(ReservoirProfile.of(stack));
                    poses.translate(.5-grip.x(),.5-grip.y(),.5-grip.z());
                }else if(!gui)poses.translate(.5,0,.38);
                backpack(stack,poses,buffers,light,overlay,gui);
            }
        }finally{poses.popPose();}
    }
    public void render(ReservoirBlockEntity block,float partial,PoseStack poses,MultiBufferSource buffers,int light,int overlay){
        poses.pushPose();
        try{
            poses.translate(.5,0,.5);poses.mulPose(Axis.YP.rotationDegrees(-block.getBlockState().getValue(ReservoirBlock.FACING).toYRot()));
            backpack(block.bag(),ReservoirProfile.of(block.getBlockState()),poses,buffers,light,overlay,false);
        }finally{poses.popPose();}
    }
    public static void backpack(ItemStack bag,PoseStack poses,MultiBufferSource buffers,int light,int overlay){
        backpack(bag,poses,buffers,light,overlay,false);
    }
    private static void backpack(ItemStack bag,PoseStack poses,MultiBufferSource buffers,int light,int overlay,boolean gui){
        backpack(bag,ReservoirProfile.of(bag),poses,buffers,light,overlay,gui);
    }
    private static void backpack(ItemStack bag,ReservoirProfile profile,PoseStack poses,MultiBufferSource buffers,int light,int overlay,boolean gui){
        var level=Minecraft.getInstance().level;
        var mounts=profile==ReservoirProfile.SILVERWOOD?ReservoirPluginMounts.SILVER_SLOTS:ReservoirPluginMounts.SLOTS;
        var slots=ReservoirData.read(bag);
        ItemStack[] devices=new ItemStack[profile.plugins];int installed=0;
        for(int i=0;i<devices.length;i++){
            devices[i]=level==null?ItemStack.EMPTY:ReservoirData.slot(slots,ReservoirData.FIRST_PLUGIN_SLOT+i,level.registryAccess());
            if(i<mounts.length && (devices[i].getItem() instanceof ReservoirTransducerItem || devices[i].getItem() instanceof FrostModuleItem))installed|=mounts[i].cap();
        }
        if(gui)ReservoirGuiPose.backpack(profile,installed).apply(poses);
        ReservoirMesh.draw(profile.model+"_static",poses,buffers,light,overlay,installed,0xFFFFFFFF);
        ReservoirMesh.draw(profile.model+"_wood",poses,buffers,light,overlay,0,0xFFFFFFFF);
        if(ModList.get().isLoaded("thaumcraft"))dev.senluo.witchcraft.compat.thaumcraft.client.ReservoirDisplay.contents(bag,poses,buffers,light);
        for(int i=0;i<devices.length && i<mounts.length;i++)if((installed & mounts[i].cap())!=0){
            ItemStack device=devices[i];
            // Preserve the old attachment at the middle socket; added slots use top and bottom.
            var mount=mounts[i];
            poses.pushPose();poses.translate(mount.x(),mount.y(),mount.z());poses.mulPose(Axis.YP.rotationDegrees(mount.rotation()));
            int tint=0xFFFFFFFF;
            var data=ReservoirData.read(device);
            if(level!=null && data.contains("light_tick")) {
                long age=level.getGameTime()-data.getLong("light_tick");
                if(age>=0 && age<10)tint=0xFF000000|data.getInt("light_color");
            }
            if(device.getItem() instanceof ReservoirTransducerItem transducer)converter(poses,buffers,light,overlay,tint,transducer.material());
            else frost(poses,buffers,light,overlay);
            poses.popPose();
        }
        ReservoirMesh.draw(profile.model+"_glass",poses,buffers,light,overlay,0,0xFFFFFFFF);
    }
    private static void converter(PoseStack poses,MultiBufferSource buffers,int light,int overlay,int tint,String material){
        ReservoirMesh.draw("converter_static",poses,buffers,light,overlay,0,0xFFFFFFFF,material);
        ReservoirMesh.draw("converter_indicators",poses,buffers,tint==0xFFFFFFFF?light:LightTexture.FULL_BRIGHT,overlay,0,tint);
        ReservoirMesh.draw("converter_glass",poses,buffers,light,overlay,0,0xFFFFFFFF,material);
    }
    private static void frost(PoseStack poses,MultiBufferSource buffers,int light,int overlay){
        ReservoirMesh.draw("frost_static",poses,buffers,light,overlay,0,0xFFFFFFFF,"frost_module");
        ReservoirMesh.draw("frost_glass",poses,buffers,light,overlay,0,0xFFFFFFFF,"frost_module");
    }
}
