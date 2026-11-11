package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.ritual.GeasBindings;
import dev.tc4port.thaumcraft.api.recipe.InfusionCraftingRecipe;
import dev.tc4port.thaumcraft.api.ThaumcraftContent;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Read-only TC adapter. Cached loaded pedestal positions, renewed item eligibility; no inventory writes.
 * Mirrors the native altar's highest-pedestal-per-column rule and its 8/10-block scan bounds. */
final class InfusionOfferingVisuals {
    private long scanned=Long.MIN_VALUE;
    private List<BlockPos> candidates=List.of();
    List<BlockPos> snapshot(ServerLevel level,BlockEntity matrix) {
        if(dev.tc4port.thaumcraft.api.machine.InfusionMatrixApi.state(level, matrix.getBlockPos()).map(state -> state.remainingIngredients() == 0).orElse(true))return List.of();
        BlockPos center=matrix.getBlockPos();long now=level.getGameTime();
        if(scanned==Long.MIN_VALUE || now<scanned || now-scanned>=40) {
            var columns=new HashMap<Long,BlockPos>();
            for(int x=(center.getX()-8)>>4;x<=(center.getX()+8)>>4;x++)
                for(int z=(center.getZ()-8)>>4;z<=(center.getZ()+8)>>4;z++) {
                    var chunk=level.getChunkSource().getChunkNow(x,z);if(chunk==null)continue;
                    for(var be:chunk.getBlockEntities().values()) {
                        if(be.getType() != ThaumcraftContent.blockEntity("arcane_pedestal") || be.isRemoved())continue;
                        BlockPos pos=be.getBlockPos();int dx=pos.getX()-center.getX(),dz=pos.getZ()-center.getZ(),dy=pos.getY()-center.getY();
                        if(Math.abs(dx)>8 || Math.abs(dz)>8 || dy < -10 || dy > -1 || dx==0 && dz==0)continue;
                        long key=((long)pos.getX()<<32)^(pos.getZ()&0xffffffffL);
                        columns.merge(key,pos.immutable(),(a,b)->a.getY()>b.getY()?a:b);
                    }
                }
            candidates=columns.values().stream().sorted(Comparator.comparingDouble((BlockPos p)->p.distSqr(center))
                    .thenComparingLong(BlockPos::asLong)).toList();scanned=now;
        }
        var holder=level.getRecipeManager().byKey(GeasBindings.RESTRAINT).orElse(null);
        if(holder==null || !(holder.value() instanceof InfusionCraftingRecipe recipe))return List.of();
        var ingredients=recipe.pedestalComponents();
        var result=new ArrayList<BlockPos>();
        for(BlockPos pos:candidates) {
            if(!level.hasChunkAt(pos)) continue;
            var be = level.getBlockEntity(pos);
            if (!(be instanceof Container pedestal) || be.isRemoved() || be.getType() != ThaumcraftContent.blockEntity("arcane_pedestal")) continue;
            var stack = pedestal.getItem(0);
            if (stack.isEmpty()) continue;
            if(ingredients.stream().anyMatch(ingredient->ingredient.test(stack)))result.add(pos);
            if(result.size()==8)break;
        }
        return List.copyOf(result);
    }
}
