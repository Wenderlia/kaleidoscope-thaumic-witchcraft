package dev.senluo.witchcraft.ritual;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** Only the three selected offerings are held. Their ordinary item entities survive a save/restart. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID)
public final class OwnershipOfferings {
    enum Recipe {
        OWNERSHIP(2, 50, 60, Items.STRING, Items.GOLD_INGOT, Items.AMETHYST_SHARD),
        SUSTAINED(2, 50, 60, Items.COBWEB, Items.GOLD_INGOT, Items.CRYING_OBSIDIAN),
        VISHROOM(3, 60, 60, stack -> stack.is(Items.BONE_MEAL),
                stack -> stack.is(Items.NETHER_WART),
                stack -> stack.is(dev.senluo.witchcraft.vishroom.VishroomContent.MUSHROOM_MATERIAL)
                        || stack.getItem() instanceof net.minecraft.world.item.BlockItem block
                            && block.getBlock() instanceof net.minecraft.world.level.block.HugeMushroomBlock);
        final int consumables, resonanceTick, preparationTicks;
        final java.util.List<java.util.function.Predicate<ItemStack>> items;
        Recipe(int consumables, int resonanceTick, int preparationTicks, Item... items) {
            this.consumables = consumables; this.resonanceTick = resonanceTick;
            this.preparationTicks = preparationTicks;
            this.items = java.util.Arrays.stream(items)
                    .<java.util.function.Predicate<ItemStack>>map(item -> stack -> stack.is(item)).toList();
        }
        @SafeVarargs
        Recipe(int consumables, int resonanceTick, int preparationTicks,
                java.util.function.Predicate<ItemStack>... items) {
            this.consumables = consumables; this.resonanceTick = resonanceTick;
            this.preparationTicks = preparationTicks; this.items = java.util.List.of(items);
        }
        int preparationTicks() { return preparationTicks; }
    }
    private static final String MARKER = "kaleidoscope_witchcraft:ritual_offering";
    private static final Map<UUID, ItemEntity> HELD = new HashMap<>();
    private final Recipe recipe;
    private final ServerLevel level;
    private final BlockPos core;
    private final List<ItemEntity> items;
    private final List<RitualMaterials.Source> sources;
    private final List<Vec3> origins;
    private int consumed;
    private boolean released;

    public static boolean held(net.minecraft.world.entity.Entity entity) {
        return HELD.get(entity.getUUID()) == entity;
    }

    private OwnershipOfferings(ServerLevel level, BlockPos core, List<ItemEntity> items,
            List<RitualMaterials.Source> sources, Recipe recipe) {
        this.recipe = recipe;
        this.level = level;
        this.core = core.immutable();
        this.items = items;
        this.sources = sources;
        this.origins = items.stream().map(ItemEntity::position).toList();
    }

    static OwnershipOfferings reserve(ServerLevel level, BlockPos core, OwnershipCircle circle) {
        return reserve(level, core, circle, Recipe.OWNERSHIP);
    }

    static OwnershipOfferings reserve(ServerLevel level, BlockPos core, OwnershipCircle circle, Recipe recipe) {
        var area = RitualOfferingArea.outermost(level, circle.center());
        if (area.isEmpty()) return null;
        var nearby = RitualMaterials.find(level, area.get(), stack -> {
            for (var required : recipe.items) if (required.test(stack)) return true;
            return false;
        });
        List<RitualMaterials.Source> sources = new ArrayList<>(3);
        for (var required : recipe.items) {
            var source = nearby.stream().filter(item -> required.test(item.item()) && !sources.contains(item)).findFirst().orElse(null);
            if (source == null) return null;
            sources.add(source);
        }
        List<ItemEntity> reserved = new ArrayList<>(3);
        for (var source : sources) {
            Vec3 origin = source.position();
            ItemEntity copy = new ItemEntity(level, origin.x, origin.y + .2, origin.z,
                    source.item(), 0, 0, 0);
            copy.getPersistentData().putBoolean(MARKER, true);
            copy.setNeverPickUp();
            copy.setNoGravity(true);
            copy.setInvulnerable(true);
            copy.setUnlimitedLifetime();
            HELD.put(copy.getUUID(), copy);
            reserved.add(copy);
            if (!level.addFreshEntity(copy)) {
                for (ItemEntity item : reserved) { HELD.remove(item.getUUID()); item.discard(); }
                return null;
            }
        }
        // Entity spawn hooks can reject or change items; debit sources only after all three exist.
        for (int i = 0; i < 3; i++) {
            if (!sources.get(i).available() || !RitualMaterials.inArea(sources.get(i), area.get())
                    || !ItemStack.isSameItemSameComponents(sources.get(i).item(), reserved.get(i).getItem())
                    || !reserved.get(i).isAlive()) {
                for (ItemEntity item : reserved) { HELD.remove(item.getUUID()); item.discard(); }
                return null;
            }
        }
        for (int i = 0; i < sources.size(); i++) {
            ItemStack taken = sources.get(i).takeOne();
            if (taken.isEmpty()) {
                // A container callback may invalidate a later source. Only actual debits are returned.
                for (int j = 0; j < reserved.size(); j++) {
                    ItemEntity item = reserved.get(j); HELD.remove(item.getUUID());
                    if (j < i) returnOrRelease(item, sources.get(j)); else item.discard();
                }
                return null;
            }
            reserved.get(i).setItem(taken);
        }
        return new OwnershipOfferings(level, core, reserved, sources, recipe);
    }

    boolean valid() {
        for (int i = consumed; i < 3; i++) {
            ItemEntity item = items.get(i);
            if (!item.isAlive() || level.getEntity(item.getUUID()) != item || item.level() != level
                    || !level.shouldTickBlocksAt(item.blockPosition()) || item.getItem().getCount() != 1
                    || !recipe.items.get(i).test(item.getItem())) return false;
        }
        return true;
    }

    /** One absorption each second; reusable catalysts remain only in recipes that specify them. */
    void advance(int ticks) {
        for (int i = consumed; i < recipe.consumables; i++) {
            ItemEntity item = items.get(i);
            double progress = Math.clamp((ticks - i * 20) / 20.0, 0, 1);
            Vec3 position = origins.get(i).lerp(Vec3.atCenterOf(core), progress);
            item.setPos(position.x, position.y, position.z);
            item.setDeltaMovement(Vec3.ZERO);
            if (progress >= 1) {
                level.sendParticles(ParticleTypes.ENCHANT, position.x, position.y, position.z, 6, .15, .15, .15, .05);
                OfferingSounds.absorb(level, position, item.getItem());
                HELD.remove(item.getUUID());
                item.discard();
                consumed++;
            }
        }
        if (recipe.consumables == items.size()) return;
        ItemEntity catalyst = items.get(2);
        Vec3 position = origins.get(2);
        catalyst.setPos(position.x, position.y, position.z);
        catalyst.setDeltaMovement(Vec3.ZERO);
        // Reusable matter resonates in place; its session decides when to release it.
        if (ticks == recipe.resonanceTick) OfferingSounds.play(level, position, catalyst.getItem());
    }

    void release() {
        if (released) return;
        released = true;
        for (int i = consumed; i < items.size(); i++) {
            ItemEntity item = items.get(i);
            HELD.remove(item.getUUID());
            if (item.isAlive()) {
                Vec3 position = origins.get(i);
                item.setPos(position.x, position.y, position.z);
                returnOrRelease(item, sources.get(i));
            }
        }
    }

    private static void returnOrRelease(ItemEntity item, RitualMaterials.Source source) {
        if (source.returnOne(item.getItem())) item.discard(); else restore(item);
    }

    private static void restore(ItemEntity item) {
        item.getPersistentData().remove(MARKER);
        item.setNoGravity(false);
        item.setInvulnerable(false);
        item.setDeltaMovement(Vec3.ZERO);
        // Restore a normal lifetime through the public serialization API, without an accessor Mixin.
        CompoundTag tag = new CompoundTag();
        item.addAdditionalSaveData(tag);
        tag.putShort("Age", (short) 0);
        tag.putShort("PickupDelay", (short) 10);
        item.readAdditionalSaveData(tag);
    }

    @SubscribeEvent
    public static void joined(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof ItemEntity item
                && item.getPersistentData().contains(MARKER) && HELD.get(item.getUUID()) != item) restore(item);
    }

    @SubscribeEvent
    public static void itemTick(EntityTickEvent.Pre event) {
        if (!event.getEntity().level().isClientSide && event.getEntity() instanceof ItemEntity item
                && HELD.get(item.getUUID()) == item) {
            // Prevent motion, merging, aging and portal transitions; the session animates position.
            event.setCanceled(true);
        }
    }
}
