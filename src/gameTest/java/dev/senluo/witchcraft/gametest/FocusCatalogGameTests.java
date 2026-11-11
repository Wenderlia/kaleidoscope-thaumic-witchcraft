package dev.senluo.witchcraft.gametest;

import dev.senluo.witchcraft.compat.thaumcraft.SlashFocus;
import dev.senluo.witchcraft.slash.SlashContent;
import dev.senluo.witchcraft.slash.SlashEntity;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import dev.tc4port.thaumcraft.api.focus.FocusApi;
import dev.tc4port.thaumcraft.api.focus.FocusUpgradeId;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Exercises the actual TC4R reload before any world can be opened; compilation alone cannot detect this failure. */
@PrefixGameTestTemplate(false)
public final class FocusCatalogGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=40, batch="datapack-load")
    public static void geasNativePreviewUsesViewerAndNeverDeliversGlyph(GameTestHelper h) {
        var viewer = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "recipe-viewer"));
        dev.senluo.witchcraft.ritual.GeasRecipePreview.viewer(() -> viewer);
        try {
            for (var rite : dev.senluo.witchcraft.ritual.GeasRites.ALL) {
                var holder = h.getLevel().getRecipeManager().byKey(rite.id()).orElseThrow();
                var recipe = (dev.tc4port.thaumcraft.recipe.InfusionRecipe) holder.value();
                var inputs = recipe.previewInputs(h.getLevel());
                h.assertTrue(inputs.size() == 1 && recipe.supportsCentralInput(inputs.getFirst()),
                        "Native book and JEI retain the filled central sample");
                var input = inputs.getFirst();
                var sample = input.get(dev.senluo.witchcraft.ritual.RitualContent.TAGLOCK_SAMPLE.get());
                h.assertTrue(sample.subject().equals(viewer.getUUID()) && sample.name().equals("recipe-viewer"),
                        "The central sample belongs to the current viewer");
                var output = recipe.assembleFromCentral(input, h.getLevel().registryAccess());
                h.assertTrue(net.minecraft.world.item.ItemStack.isSameItemSameComponents(output,
                        dev.senluo.witchcraft.ritual.GeasRecipePreview.outcome(rite)), "Native viewers receive the corresponding glyph");
                input.setCount(3);
                var completed = recipe.completeFromCentral(input, h.getLevel());
                h.assertTrue(net.minecraft.world.item.ItemStack.matches(completed,input),
                        "Server completion keeps the real sample stack for Output/End settlement, never the glyph");
                h.assertTrue(dev.senluo.witchcraft.ritual.GeasBindings.state(h.getLevel().getServer(),viewer.getUUID(),rite.pair())
                        == dev.senluo.witchcraft.ritual.BindingPair.State.NONE, "Previewing has no binding side effects");
            }
        } finally { dev.senluo.witchcraft.ritual.GeasRecipePreview.viewer(() -> null); }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=40, batch="datapack-load")
    public static void fruitRegistrationRetainsNativeAspectView(GameTestHelper h) {
        for (int grade = 1; grade <= 5; grade++) {
            var fruit = dev.senluo.witchcraft.time.FruitItem.stack(grade);
            h.assertTrue(fruit.getItem() instanceof dev.tc4port.thaumcraft.api.aspect.AspectItemView,
                    "The optional item factory retains the native aspect SPI when TC is installed");
            var aspects = ((dev.tc4port.thaumcraft.api.aspect.AspectItemView) fruit.getItem()).storedAspectData(fruit);
            h.assertTrue(aspects.amount(dev.tc4port.thaumcraft.api.aspect.AspectId.parse("herba")) == grade
                            && aspects.amount(dev.tc4port.thaumcraft.api.aspect.AspectId.parse("praecantatio")) == 2 * grade
                            && aspects.amounts().size() == 6,
                    "The registered item exposes grade-specific aspects through the host interface");
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=40, batch="datapack-load")
    public static void slashProfileSurvivesNativeDatapackLoad(GameTestHelper h) {
        var profile = FocusApi.registry().profile(SlashFocus.ID).orElseThrow();
        h.assertTrue(profile.focusId().equals(SlashFocus.ID) && profile.baseCost().isPresent(),
                "The loaded profile matches the registered focus and retains its base price");
        var frugal = FocusUpgradeId.of(ResourceLocation.parse("thaumcraft:frugal"));
        h.assertTrue(profile.ranks().size() == 5 && profile.ranks().stream().allMatch(rank -> rank.contains(frugal)),
                "Native reload resolves the existing upgrade on all five ranks");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=40, batch="datapack-load")
    public static void slashUsesDirectDamageAndQuarterArmor(GameTestHelper h) {
        var caster = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "slash-damage"));
        var carrier = new SlashEntity(SlashContent.SLASH.get(), h.getLevel());
        var source = SlashFocus.damageSource(carrier, caster, 1, 0);
        h.assertTrue(!source.is(DamageTypeTags.IS_PROJECTILE) && !source.is(DamageTypeTags.BYPASSES_ARMOR)
                        && source.getDirectEntity() == carrier && source.getEntity() == caster,
                "Direct focus damage retains carrier and caster without bypassing all defenses");
        var enderman = EntityType.ENDERMAN.create(h.getLevel());
        enderman.getAttribute(Attributes.ARMOR).setBaseValue(20);
        enderman.getAttribute(Attributes.ARMOR_TOUGHNESS).setBaseValue(8);
        float before = enderman.getHealth();
        float expected = CombatRules.getDamageAfterAbsorb(enderman, 10, source, 5, 8);
        h.assertTrue(enderman.hurt(source, 10) && Math.abs(before - enderman.getHealth() - expected) < .001,
                "Enderman takes the hit using 25% armor and unchanged toughness");
        h.assertTrue(enderman.getArmorValue() == 20 && enderman.getAttributeValue(Attributes.ARMOR_TOUGHNESS) == 8,
                "Armor attributes are never mutated for the hit");
        var normalTarget = EntityType.ENDERMAN.create(h.getLevel());
        normalTarget.getAttribute(Attributes.ARMOR).setBaseValue(20);
        normalTarget.getAttribute(Attributes.ARMOR_TOUGHNESS).setBaseValue(8);
        var normalSource = h.getLevel().damageSources().playerAttack(caster);
        expected = CombatRules.getDamageAfterAbsorb(normalTarget, 10, normalSource, 20, 8);
        before = normalTarget.getHealth();
        h.assertTrue(normalTarget.hurt(normalSource, 10) && Math.abs(before - normalTarget.getHealth() - expected) < .001,
                "Ordinary attacks retain all armor points");
        h.succeed();
    }
}
