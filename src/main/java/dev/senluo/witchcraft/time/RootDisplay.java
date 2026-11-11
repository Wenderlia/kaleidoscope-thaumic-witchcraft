package dev.senluo.witchcraft.time;

import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;

/** Short-lived, read-only display facts shared by the action bar, details and storage bar. */
public record RootDisplay(boolean production, int number, boolean locked, long stored, long capacity,
                          Component state, @Nullable Component progress, @Nullable Component growthTime) {
    @Nullable
    public static RootDisplay selected(Tissue mother) {
        if (!mother.supported()) return null;
        Tissue.Side side = mother.selectedSide();
        if (side == null) return null;
        RootProduction production = mother.rootProduction;
        if (production != null) {
            Component state = production.ready()
                    ? TemporalText.color(TemporalText.text("tree_root.available"), TemporalText.READY)
                    : TemporalText.color(TemporalText.text("tree_root.cooldown",
                            TemporalText.duration(Math.max(production.remainingTicks(), side.remainingTicks()))), TemporalText.PENDING);
            return new RootDisplay(true, 0, side.locked, side.stored(), side.capacity(), state, null, null);
        }
        return new RootDisplay(false, mother.selectedSideIndex() + 1, side.locked, side.stored(), side.capacity(),
                TemporalText.state(side.tissue), side.mature() ? null : TemporalText.growth(side.tissue.growth),
                side.mature() ? null : TemporalText.text("growth_time", TemporalText.elapsed(side.tissue.growth.ticks),
                        TemporalText.duration(Tissue.SIDE_TIME[side.tissue.growth.profile])));
    }

    public Component summary() {
        return production ? Component.literal(" · ").append(state)
                : TemporalText.message("side_summary", TemporalText.number(number, TemporalText.AMOUNT), state);
    }

    public Component heading() {
        return production ? state : TemporalText.text("selected", TemporalText.number(number, TemporalText.AMOUNT), state);
    }

    public Component focus() {
        if (production) return state;
        var text = TemporalText.message("side_detail", TemporalText.number(number, TemporalText.AMOUNT),
                TemporalText.amount(stored, false), TemporalText.capacity(capacity, false)).append(" · ");
        text.append(progress != null ? progress : TemporalText.message("side_ready_hint", state,
                TemporalText.item("tooltip.kaleidoscope_witchcraft.shovel")));
        if (locked) text.append(TemporalText.color(TemporalText.message("side_locked"), TemporalText.CAPACITY));
        return text;
    }
}
