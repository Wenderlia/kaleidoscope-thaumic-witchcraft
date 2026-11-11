package dev.senluo.witchcraft.time;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;

/** Common-side snapshots, shared by ordinary tooltips and the client detail layer. */
public final class TemporalTooltips {
    private static final String HINT = "tooltip.kaleidoscope_witchcraft.expand";
    private static final String ROOT_HINT = "tooltip.kaleidoscope_witchcraft.expand_root";
    private static final Set<String> EXPANDED_SUMMARY = Set.of(
            "tooltip.kaleidoscope_witchcraft.total", "tooltip.kaleidoscope_witchcraft.sides",
            "tooltip.kaleidoscope_witchcraft.tree_root.count",
            "tooltip.kaleidoscope_witchcraft.plant_core");

    private TemporalTooltips() {}

    public static boolean supports(ItemStack stack) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals("kaleidoscope_witchcraft");
    }

    public static Component source() {
        return Component.translatable("itemGroup.kaleidoscope_witchcraft")
                .withStyle(style -> style.withColor(TemporalText.SOURCE).withItalic(true));
    }

    /** Compare only complete known source names; never delete or recolor another provider's line. */
    public static boolean isSource(FormattedText line, String registeredName) {
        String text = ChatFormatting.stripFormatting(line.getString());
        return source().getString().equals(text) || registeredName.equals(text);
    }

    private static Tissue tissue(ItemStack stack) {
        return TissueItem.read(stack);
    }

    public static void summary(ItemStack stack, List<Component> lines) {
        if (stack.is(TemporalContent.ROOT_PIECE.get())) {
            lines.add(TemporalText.text("root.role"));
            lines.add(TemporalText.color(Component.translatable(ROOT_HINT, TemporalText.key(false, false)), TemporalText.SHIFT));
            return;
        }
        Tissue tissue = tissue(stack);
        if (!tissue.supported()) { lines.add(TemporalText.notice("data_unavailable")); return; }
        var stage = TemporalText.text("root_stage", TemporalText.stage(tissue)).copy();
        if (tissue.bond != null) stage.append(TemporalText.text("bond.owner",
                TemporalText.color(tissue.bond.displayName(), TemporalText.STAGE)));
        if (tissue.amberStage > 0) stage.append(TemporalText.text("amber_stage", TemporalText.number(tissue.amberStage, TemporalText.STAGE)));
        lines.add(stage);
        lines.add(Component.translatable(HINT, TemporalText.key(false, false), TemporalText.key(true, false)));
    }

    /** Replace only our own hint; retain lore, advanced lines and other mods' additions. */
    public static void expand(ItemStack stack, List<Component> lines, boolean controls, boolean details, boolean bottleLoaded) {
        if (!supports(stack) || stack.has(DataComponents.HIDE_ADDITIONAL_TOOLTIP) || (!controls && !details)) return;
        String key = stack.is(TemporalContent.ROOT_PIECE.get()) ? ROOT_HINT : HINT;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).getContents() instanceof TranslatableContents text && text.getKey().equals(key)) {
                List<Component> extra = new ArrayList<>();
                if (stack.is(TemporalContent.ROOT_PIECE.get())) {
                    if (!controls) return;
                    extra.add(TemporalText.color(TemporalText.text("root_heading", TemporalText.key(false, true)),TemporalText.SHIFT));
                    for (String part : List.of("place", "connect", "growth"))
                        extra.add(TemporalText.text("root." + part));
                } else if (details) {
                    extra.add(TemporalText.color(TemporalText.text("details_heading", TemporalText.key(true, true)),TemporalText.CONTROL));
                    details(tissue(stack), extra);
                } else {
                    extra.add(TemporalText.color(TemporalText.text("controls_heading", TemporalText.key(false, true)),TemporalText.SHIFT));
                    controls(tissue(stack), extra, bottleLoaded);
                }
                lines.remove(i);
                lines.addAll(i, extra);
                // Each expanded view replaces our summary rows; foreign lore and attributes stay intact.
                if (!stack.is(TemporalContent.ROOT_PIECE.get())) {
                    for (int j = i - 1; j >= 0; j--) {
                        if (lines.get(j).getContents() instanceof TranslatableContents summary
                                && EXPANDED_SUMMARY.contains(summary.getKey()))
                            lines.remove(j);
                    }
                }
                return;
            }
        }
    }

    private static void details(Tissue tissue, List<Component> lines) {
        lines.add(TemporalText.text("core", TemporalText.amount(tissue.coreQuantity(), true), TemporalText.capacity(tissue.coreCapacity(), true)));
        if (!tissue.sides.isEmpty() || tissue.rootProduction != null)
            lines.add(TemporalText.text("total_exact", TemporalText.amount(tissue.stored(), true), TemporalText.capacity(tissue.capacity(), true)));
        if (!tissue.established()) growth(tissue.growth, lines);
        if (!tissue.giant) lines.add(TemporalText.text("slots", TemporalText.number(tissue.sides.size(), TemporalText.AMOUNT),
                TemporalText.number(tissue.slots(), TemporalText.CAPACITY),
                TemporalText.number(tissue.sides.stream().filter(Tissue.Side::mature).count(), TemporalText.READY)));
        RootDisplay root = RootDisplay.selected(tissue);
        if (root != null) {
            lines.add(root.heading());
            lines.add(TemporalText.text("side_storage", TemporalText.amount(root.stored(), true), TemporalText.capacity(root.capacity(), true)));
            if (root.growthTime() != null) lines.add(root.growthTime());
            if (root.locked()) lines.add(TemporalText.color(TemporalText.text("reserved"), TemporalText.CAPACITY));
        }
        if (tissue.training) lines.add(TemporalText.text("training",
                TemporalText.duration(Tissue.AMBER_TIME[tissue.amberStage + 1] - tissue.trainingTicks)));
        if (tissue.treeGrowing) lines.add(TemporalText.message("tree_growing",TemporalText.duration(TreeBalance.remaining(tissue))));
        if (tissue.giantGrowing) lines.add(TemporalText.message("giant_growing",TemporalText.duration(TreeBalance.remaining(tissue))));
        if(tissue.tree) {
            var sources=Component.empty();
            for(int i=0;i<5;i++) {
                if(i>0)sources.append(" · ");
                sources.append(TemporalText.color(TemporalText.text("source."+i),tissue.sourceEnabled(i)&&(i<2||tissue.giant)?TemporalText.READY:TemporalText.SECONDARY));
            }
            lines.add(sources);
            if(!tissue.giant)lines.add(TemporalText.text("sources.locked"));
        }
    }

    private static void growth(Tissue.Growth growth, List<Component> lines) {
        lines.add(TemporalText.text("growth_time", TemporalText.elapsed(growth.ticks), TemporalText.duration(Tissue.SIDE_TIME[growth.profile])));
    }

    private static void controls(Tissue tissue, List<Component> lines, boolean bottleLoaded) {
        if (tissue.tree) lines.add(TemporalText.color(TemporalText.text("plant_core"), TemporalText.ACTION));
        lines.add(TemporalText.text("controls.inspect"));
        lines.add(TemporalText.text(tissue.giant ? "tree_root.harvest" : "controls.shovel",
                TemporalText.color(TemporalText.text("shovel"), TemporalText.ACTION)));
        lines.add(TemporalText.text("controls.reserve", TemporalText.item("item.minecraft.gold_nugget"),
                TemporalText.sneak(TemporalText.item("item.minecraft.gold_nugget"))));
        lines.add(TemporalText.text("controls.gold"));
        if (tissue.tree) {
            lines.add(TemporalText.text("tree.controls"));
            lines.add(TemporalText.text("tree.harvest", TemporalText.item("item.minecraft.shears")));
        }
        if (bottleLoaded) lines.add(TemporalText.text("controls.bottle",
                TemporalText.color(TemporalText.text("time_bottle"), TemporalText.ACTION)));
    }

    private static void section(List<Component> lines, String name) {
        lines.add(TemporalText.text("section." + name).copy().withStyle(style -> style.withColor(TemporalText.STAGE).withBold(true)));
    }
}
