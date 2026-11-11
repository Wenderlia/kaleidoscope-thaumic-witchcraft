package dev.senluo.witchcraft.ritual;

import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class GeasLedgerGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=20,batch="geas-ledger")
    public static void geasOfflineReleaseAndCurrentSaveRemainAuthoritative(GameTestHelper h) throws Exception {
        var server=h.getLevel().getServer();var subject=UUID.randomUUID();var pair=GeasBindings.RESTRAINT_PAIR;
        h.assertTrue(server.getPlayerList().getPlayer(subject)==null,"The ritual subject is offline");
        var before=GeasBindings.state(server,subject,pair);
        h.assertTrue(GeasBindings.apply(server,subject,pair,false,before)==BindingPair.Change.GRANTED,
                "A world-owned binding can be granted without a player entity or player file");
        h.assertTrue(GeasBindings.has(server,subject,pair.positive()),"The public query sees the committed grant");
        var active=GeasBindings.state(server,subject,pair);
        h.assertTrue(GeasBindings.apply(server,subject,pair,true,active)==BindingPair.Change.RELEASED,
                "Inverse ritual releases the opposite, without granting itself in the same operation");
        h.assertTrue(!GeasBindings.has(server,subject,pair.positive())&&!GeasBindings.has(server,subject,pair.negative()),
                "Released membership is absent on both sides");
        var saved=GeasLedger.get(server).save(new CompoundTag(),h.getLevel().registryAccess());
        var reader=GeasLedger.class.getDeclaredMethod("load",CompoundTag.class,HolderLookup.Provider.class);
        reader.setAccessible(true);
        var restored=(GeasLedger)reader.invoke(null,saved,h.getLevel().registryAccess());
        h.assertTrue(restored.supported()&&!restored.has(subject,pair.positive()),"Reload cannot resurrect released membership");
        var state=restored.state(subject,pair);
        try(var lease=restored.reserve(subject,pair,true,state)) {
            h.assertTrue(lease!=null&&restored.reserve(subject,pair,true,state)==null,
                    "Only one infusion completion may reserve the same transition");
            h.assertTrue(restored.apply(subject,pair,false,state)==BindingPair.Change.REJECTED,
                    "Competing changes cannot alter a reserved completion");
            h.assertTrue(lease.commit()==BindingPair.Change.GRANTED&&lease.commit()==BindingPair.Change.REJECTED,
                    "Completion commits once");
        }
        var committed=(GeasLedger)reader.invoke(null,restored.save(new CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertTrue(committed.has(subject,pair.negative())&&!committed.has(subject,pair.positive()),
                "The completed inverse binding survives its current-format round trip");
        h.succeed();
    }
}
