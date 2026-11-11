package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.slash.SlashContent;
import dev.senluo.witchcraft.slash.drawing.SlashDrawing;
import dev.tc4port.thaumcraft.api.aspect.VisAction;
import dev.tc4port.thaumcraft.api.focus.action.*;
import dev.tc4port.thaumcraft.api.presentation.ActionPresentationAudience;
import dev.tc4port.thaumcraft.api.research.ResearchRequirement;
import java.util.EnumSet;

/** Native held-use lifecycle, with explicit release intent distinguishing cancellation from casting. */
public final class SlashDrawingAction {
    private SlashDrawingAction() {}
    public static void register() {
        SlashFocus.registerCast();
        SlashFocusPipeline.register();
        FocusActionApi.register(new FocusActionSpec(SlashContent.id("draw_slash"), new FocusActionTarget.Focus(SlashFocus.ID),
                FocusInput.SECONDARY, EnumSet.allOf(FocusTargetKind.class), FocusCastMode.channel(1),
                ResearchRequirement.none(), 8, 0, ActionPresentationAudience.NONE), new FocusActionHandler() {
            @Override public FocusActionResult handle(FocusActionContext context, VisAction action) {
                var player = context.player();
                if (context.gesture() == FocusGesture.RELEASE) {
                    if (action == VisAction.EXECUTE) SlashDrawing.stopped(player, context.hand());
                    return FocusActionResult.SUCCESS;
                }
                if (!SlashDrawing.eligible(player, context.hand())) return FocusActionResult.FAILED;
                if (context.gesture() == FocusGesture.HOLD)
                    return SlashDrawing.active(player, context.hand()) ? FocusActionResult.SUCCESS : FocusActionResult.STOP_USING;
                if (action == VisAction.EXECUTE && !SlashDrawing.begin(player, context.hand())) return FocusActionResult.FAILED;
                // No debit at activation or per tick. The existing atomic slash cast pays at explicit release.
                return FocusActionResult.START_USING;
            }
            @Override public void predict(dev.tc4port.thaumcraft.api.focus.behavior.FocusUseContext use,
                    dev.tc4port.thaumcraft.api.focus.FocusStateView state, FocusInteraction interaction) {
                if (interaction.is(FocusInput.SECONDARY, FocusGesture.PRESS))
                    dev.senluo.witchcraft.client.SlashDrawingClient.begin(use.player(), use.hand());
            }
        });
    }
}
