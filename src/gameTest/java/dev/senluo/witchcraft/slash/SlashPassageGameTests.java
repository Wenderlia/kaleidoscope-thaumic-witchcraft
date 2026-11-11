package dev.senluo.witchcraft.slash;

import net.minecraft.gametest.framework.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import dev.senluo.witchcraft.brew.FrostPath;

@net.neoforged.neoforge.gametest.PrefixGameTestTemplate(false)
public final class SlashPassageGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=20)
    public static void paidSlashSnapshotsRetainCurrentGeometryAndWeapon(GameTestHelper h) {
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(h.getLevel(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "SlashSaveTest"));
        player.setPos(h.absoluteVec(new net.minecraft.world.phys.Vec3(2, 2, 2)));
        var direction = new net.minecraft.world.phys.Vec3(0, 0, 1);
        var pose = new SlashFormation.Pose(direction, 0, direction, 0, 0, h.getLevel().dimension(), false);
        var speed = new dev.senluo.witchcraft.motion.BezierSpeed(1, 1, 1, 1, 24);
        for (int mode = 0; mode < 3; mode++) {
            var slash = new SlashEntity(SlashContent.SLASH.get(), h.getLevel());
            slash.prepare(player, mode == 1 ? SlashCritical.potential(4) : 4, 1.25, 2,
                    net.minecraft.world.item.ItemStack.EMPTY, pose, SlashShape.DEFAULT, speed, player.getEyePosition());
            slash.weapon(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD));
            slash.upgrades(true, true, true);
            slash.visibility(SlashVisibility.HIDDEN);
            if (mode == 1) slash.critical(4, speed);
            if (mode == 2) {
                var sphere = dev.senluo.witchcraft.slash.drawing.StrokeSphere.facing(direction);
                var trace = new dev.senluo.witchcraft.slash.drawing.StrokeTrace();
                trace.sample(sphere.project(new net.minecraft.world.phys.Vec3(.3, 0, 1)));
                slash.radial(dev.senluo.witchcraft.slash.drawing.RadialOutline.from(sphere, trace, 24));
            }
            slash.arm();
            var hit = java.util.UUID.randomUUID();
            slash.hitEntities.add(hit); slash.hitBlocks.add(new BlockPos(1, 2, 3).asLong());
            var saved = new net.minecraft.nbt.CompoundTag(); slash.addAdditionalSaveData(saved);
            var restored = new SlashEntity(SlashContent.SLASH.get(), h.getLevel());
            restored.setPos(slash.position()); restored.readAdditionalSaveData(saved);
            var again = new net.minecraft.nbt.CompoundTag(); restored.addAdditionalSaveData(again);
            h.assertTrue(again.getBoolean("Paid") && again.getCompound("Weapon").equals(saved.getCompound("Weapon"))
                    && restored.hitEntities.contains(hit) && restored.hitBlocks.equals(slash.hitBlocks),
                    "Already paid casts retain weapon and hit receipts; no re-payment or duplicate hit on reload");
            h.assertTrue(restored.visibility() == SlashVisibility.HIDDEN
                    && restored.visual() == slash.visual() && restored.penetrating() && restored.fission(),
                    "Audience, critical visual and paid upgrades retain their cast-local values");
            for (String key : new String[]{"Shape", "Route", "Speed", "InitialSpeed", "Radial"})
                h.assertTrue(java.util.Objects.equals(saved.get(key), again.get(key)), "Current geometry retained: " + key);
            restored.discard(); slash.discard();
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=20)
    public static void frostSlashPassage(GameTestHelper h){
        var slash=new SlashEntity(SlashContent.SLASH.get(),h.getLevel());var pos=h.absolutePos(new BlockPos(2,2,2));
        for(boolean restrained:new boolean[]{false,true}){
            slash.restrained(restrained);
            h.assertTrue(SlashBlockPassage.allows(slash,FrostPath.BLOCK.get().defaultBlockState(),pos),"Both slash modes ignore light bridges through the shared policy");
            h.assertTrue(!SlashBlockPassage.allows(slash,Blocks.STONE.defaultBlockState(),pos),"Solid stone is not accidentally added to passage");
        }
        h.getLevel().setBlock(pos,FrostPath.BLOCK.get().defaultBlockState(),3);
        h.getLevel().setBlock(pos.below(),Blocks.STONE.defaultBlockState(),3);
        var player=h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        for(var mode:new net.minecraft.world.level.ClipContext.Block[]{net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Block.OUTLINE}){
            var hit=h.getLevel().clip(SlashBlockPassage.ray(pos.getCenter().add(0,2,0),pos.getCenter().add(0,-2,0),mode,player));
            h.assertTrue(hit.getBlockPos().equals(pos.below()),"Launch/aim must look through the light surface to real stone");
        }
        slash.discard();h.succeed();
    }
}
