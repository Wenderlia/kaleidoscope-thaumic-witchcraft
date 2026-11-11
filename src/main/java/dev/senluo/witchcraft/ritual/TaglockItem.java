package dev.senluo.witchcraft.ritual;

import dev.senluo.witchcraft.time.TemporalText;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

public final class TaglockItem extends Item {
    public TaglockItem(Properties properties) {
        super(properties);
    }

    @Override
    public net.minecraft.world.InteractionResultHolder<ItemStack> use(net.minecraft.world.level.Level level,
            net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (!TaglockSampling.canSampleSelf(player, hand))
            return net.minecraft.world.InteractionResultHolder.pass(held);
        if (player instanceof ServerPlayer serverPlayer) TaglockSampling.fromSelf(serverPlayer, hand);
        return net.minecraft.world.InteractionResultHolder.sidedSuccess(held, level.isClientSide);
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        var player = context.getPlayer();
        if (player instanceof ServerPlayer serverPlayer && !player.isSpectator()) {
            var result = RitualCenters.activate(serverPlayer, context.getLevel().getBlockEntity(context.getClickedPos()));
            if (result != InteractionResult.PASS) return result;
        }
        if (player != null && !player.isSpectator() && context.getLevel().getBlockState(context.getClickedPos())
                .is(dev.senluo.witchcraft.vishroom.VishroomContent.SEED)) {
            if (player instanceof ServerPlayer serverPlayer) MushroomRitual.start(serverPlayer, context.getHand(), context.getClickedPos());
            return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
        }
        if (player != null && !player.isSpectator()
                && context.getLevel().getBlockEntity(context.getClickedPos()) instanceof dev.senluo.witchcraft.time.LivingEntity tree) {
            if (player instanceof ServerPlayer serverPlayer) OwnershipRitual.start(serverPlayer, context.getHand(), tree);
            return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
        }
        if (player == null || player.isSpectator()
                || !TaglockSampling.isBed(context.getLevel(), context.getClickedPos(), player))
            return InteractionResult.PASS;
        if (player instanceof ServerPlayer serverPlayer)
            TaglockSampling.fromBed(serverPlayer, context.getHand(), context.getClickedPos());
        // Consume even an empty/restricted bed attempt so it cannot sleep or explode the bed.
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    @Override
    public void inventoryTick(ItemStack stack, net.minecraft.world.level.Level level,
                              net.minecraft.world.entity.Entity entity, int slot, boolean selected) {
        if (!level.isClientSide) RitualVial.cleanup(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flags) {
        if (stack.has(DataComponents.HIDE_ADDITIONAL_TOOLTIP)) return;
        TaglockSample sample = stack.get(RitualContent.TAGLOCK_SAMPLE.get());
        if (sample == null) lines.add(text("empty"));
        else {
            lines.add(text("bound", TemporalText.color(sample.displayName(), TemporalText.STAGE)));
            if (!sample.supported()) lines.add(TemporalText.color(text("unsupported"), TemporalText.PENDING));
        }
    }

    private static Component text(String key, Object... args) {
        return TemporalText.color(Component.translatable("tooltip.kaleidoscope_witchcraft.taglock." + key, args), TemporalText.BODY);
    }
}
