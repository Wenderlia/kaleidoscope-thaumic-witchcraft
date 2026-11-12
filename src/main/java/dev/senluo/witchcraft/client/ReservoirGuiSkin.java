package dev.senluo.witchcraft.client;

import dev.senluo.witchcraft.reservoir.ReservoirContent;
import dev.senluo.witchcraft.reservoir.ReservoirProfile;
import net.minecraft.resources.ResourceLocation;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Material skins only. Slot geometry and interaction remain in ReservoirLayout. */
enum ReservoirGuiSkin {
    STANDARD("standard",0xDFC99D), SILVERWOOD("silverwood",0x2A3530);

    final int titleColor;
    private final Map<String,ResourceLocation> sprites;
    ReservoirGuiSkin(String folder,int titleColor){
        this.titleColor=titleColor;
        sprites=Stream.of("panel","rail_open","rail_closed","chamber_body","chamber_glass",
                "label_blank","chamber_focus","slot","transfer_dock","upgrade_slot",
                "switch_on","switch_off","input_glyph","output_glyph","upgrade_glyph",
                "fold_up","fold_down","liquid_neutral")
                .collect(Collectors.toUnmodifiableMap(name->name,
                        name->ReservoirContent.id("textures/gui/reservoir/"+folder+"/"+name+".png")));
    }
    ResourceLocation texture(String name){return sprites.get(name);}
    static ReservoirGuiSkin of(ReservoirProfile profile){return profile==ReservoirProfile.SILVERWOOD?SILVERWOOD:STANDARD;}
}
