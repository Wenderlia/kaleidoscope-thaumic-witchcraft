package dev.senluo.witchcraft.brew;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.*;
import java.util.*;

final class WineTargets {
    private WineTargets(){}
    static boolean ally(ServerPlayer p,LivingEntity e){
        return e!=p && (p.isAlliedTo(e) || e instanceof TamableAnimal pet && p.getUUID().equals(pet.getOwnerUUID()));
    }
    static boolean hostile(ServerPlayer p,LivingEntity e){
        return e!=p && !(e instanceof Player) && !(e instanceof TamableAnimal) && !p.isAlliedTo(e)
                && (e instanceof Enemy || e.getType().getCategory()==MobCategory.MONSTER || e instanceof Mob mob && mob.getTarget()==p);
    }
    static List<LivingEntity> enemies(ServerPlayer p,Vec3 center,double radius,int limit){
        Comparator<LivingEntity> order=Comparator.comparingDouble((LivingEntity e)->e.distanceToSqr(center)).thenComparingInt(Entity::getId);
        PriorityQueue<LivingEntity> closest=new PriorityQueue<>(order.reversed());
        // Native loaded-entity spatial query; bounded selection, not a claim of bounded enumeration.
        for(LivingEntity e:p.serverLevel().getEntitiesOfClass(LivingEntity.class,new AABB(center,center).inflate(radius),
                e->e.isAlive() && !e.isSpectator() && hostile(p,e) && e.distanceToSqr(center)<=radius*radius)){
            closest.add(e);if(closest.size()>limit)closest.poll();
        }
        List<LivingEntity> result=new ArrayList<>(closest);result.sort(order);return result;
    }
}
