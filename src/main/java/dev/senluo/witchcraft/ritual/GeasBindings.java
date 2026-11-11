package dev.senluo.witchcraft.ritual;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Permanent, server-owned bindings. Rituals own qualification/payment; this store owns membership/transitions.
 * No tick loop, potion effect, equipment dependency, or client mutation endpoint.
 */
public final class GeasBindings {
    public static final ResourceLocation RESTRAINT = ResourceLocation.fromNamespaceAndPath(
            KaleidoscopeWitchcraft.MOD_ID, "restraint");
    public static final BindingPair RESTRAINT_PAIR = new BindingPair(RESTRAINT,
            ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID, "restraint_inverse"));
    public static final ResourceLocation CHANT = ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID, "chant");
    public static final BindingPair CHANT_PAIR = new BindingPair(CHANT,
            ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID, "chant_inverse"));
    private GeasBindings() {}

    public static boolean has(ServerPlayer player, ResourceLocation binding) {
        return has(player.server, player.getUUID(), binding);
    }

    public static boolean writable(net.minecraft.server.MinecraftServer server) { return GeasLedger.get(server).supported(); }
    public static boolean has(net.minecraft.server.MinecraftServer server, java.util.UUID subject, ResourceLocation binding) {
        if (!server.isSameThread()) throw new IllegalStateException("Geas access requires the server thread");
        GeasLedger ledger = GeasLedger.get(server);
        return ledger.supported() && ledger.has(subject, binding);
    }
    public static boolean grant(net.minecraft.server.MinecraftServer server, java.util.UUID subject, ResourceLocation binding) {
        if (!server.isSameThread()) throw new IllegalStateException("Geas mutation requires the server thread");
        for (BindingPair pair : java.util.List.of(RESTRAINT_PAIR, CHANT_PAIR)) {
            if (binding.equals(pair.positive()) || binding.equals(pair.negative())) {
                var changed = apply(server, subject, pair, binding.equals(pair.negative()), state(server, subject, pair));
                return changed == BindingPair.Change.GRANTED || changed == BindingPair.Change.RELEASED;
            }
        }
        return GeasLedger.get(server).grant(java.util.Objects.requireNonNull(subject), java.util.Objects.requireNonNull(binding));
    }
    /** Read/commit on the server thread; the expected state prevents a stale ritual quote from flipping a new state. */
    public static BindingPair.State state(net.minecraft.server.MinecraftServer server, java.util.UUID subject, BindingPair pair) {
        if (!server.isSameThread()) throw new IllegalStateException("Geas access requires the server thread");
        if (!GeasLedger.get(server).supported()) return BindingPair.State.UNAVAILABLE;
        return GeasLedger.get(server).state(subject, pair);
    }
    public static BindingPair.Change apply(net.minecraft.server.MinecraftServer server, java.util.UUID subject,
            BindingPair pair, boolean inverse, BindingPair.State expected) {
        if (!server.isSameThread()) throw new IllegalStateException("Geas mutation requires the server thread");
        java.util.Objects.requireNonNull(subject); java.util.Objects.requireNonNull(pair); java.util.Objects.requireNonNull(expected);
        if (state(server, subject, pair) != expected || expected == BindingPair.State.UNAVAILABLE)
            return BindingPair.Change.REJECTED;
        var result = GeasLedger.get(server).apply(subject, pair, inverse, expected);
        if (result == BindingPair.Change.GRANTED || result == BindingPair.Change.RELEASED) GeasView.changed(server, subject);
        return result;
    }
    /** Short reservation only between native Output and End. Never held during the long infusion. */
    public static Transition reserve(net.minecraft.server.MinecraftServer server, java.util.UUID subject,
            GeasRites.Rite rite, BindingPair.State expected) {
        if (!server.isSameThread()) throw new IllegalStateException("Geas mutation requires the server thread");
        if (state(server, subject, rite.pair()) != expected) return null;
        var lease = GeasLedger.get(server).reserve(subject, rite.pair(), rite.inverse(), expected);
        return lease == null ? null : new Transition(lease, server, subject);
    }
    public static final class Transition implements AutoCloseable {
        private final GeasLedger.Transition lease;
        private final net.minecraft.server.MinecraftServer server;
        private final java.util.UUID subject;
        private Transition(GeasLedger.Transition lease, net.minecraft.server.MinecraftServer server, java.util.UUID subject) {
            this.lease = lease; this.server = server; this.subject = subject;
        }
        public BindingPair.Change commit() {
            var result = lease.commit();
            if (result == BindingPair.Change.GRANTED || result == BindingPair.Change.RELEASED) GeasView.changed(server, subject);
            return result;
        }
        @Override public void close() { lease.close(); }
    }
    /** Server ritual completion hook, after its transaction has succeeded. Duplicate requests are no-ops.
     * Paired bindings remove the opposite first; online and offline subjects share the world UUID ledger.
     */
    public static boolean grant(ServerPlayer player, ResourceLocation binding) {
        if (!player.server.isSameThread()) throw new IllegalStateException("Geas mutation requires the server thread");
        return grant(player.server, player.getUUID(), binding);
    }
}
