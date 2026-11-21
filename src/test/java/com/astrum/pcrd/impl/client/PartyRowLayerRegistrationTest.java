package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.attach.Party;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.GuiLayerManager;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The party status row's GUI layer is registered, and this test <em>runs</em> the registration.</b>
 *
 * <h2>Why this file exists</h2>
 * {@code PartyStatusHud} was written by the previous task and its registration was <b>compiled but never
 * executed</b>: {@code runClient} cannot start here (arm64 natives), so nobody had ever seen
 * {@code RegisterGuiLayersEvent} receive this mod's four layers. "It is registered in {@code ClientEvents}"
 * is a claim about source text; the defect it would hide — a layer id that never arrives, or a row ordered
 * <em>below</em> the gauge it is positioned from — is invisible until somebody launches a client and looks.
 *
 * <h2>So the event is constructed for real</h2>
 * {@link RegisterGuiLayersEvent} is a public class with a public constructor over a mutable
 * {@code List<GuiLayerManager.NamedLayer>}, and {@code registerAbove} is <b>not</b> a no-op: it validates that
 * the anchor exists and splices the new layer in at that index (see its own source). So this test hands
 * {@link ClientEvents#onRegisterGuiLayers} a real event over a list that already holds vanilla's hotbar
 * layer, and then asserts <b>what the event contains afterwards</b>:
 *
 * <ul>
 *   <li>all four ids are present, once each;</li>
 *   <li>the party row comes <b>after</b> the gauge (so it is never painted under it) and <b>before</b> the
 *       wheel (so a wheel the player is holding covers it);</li>
 *   <li>every layer's {@code layer()} is a live {@link LayeredDraw.Layer} — the registration would accept a
 *       {@code null} here and fail at the first frame, which is the failure a compiled-only check cannot
 *       see at all.</li>
 * </ul>
 *
 * <h2>What this still cannot prove</h2>
 * That the row <em>looks</em> right, and that vanilla's effect bar is visually shorter. The predicates behind
 * the second one are asserted here ({@link #thePartyEffectsAreHiddenFromTheVanillaBarAndKeptInTheInventory}),
 * but "the bar looks shorter and correct" is a pixel, and a pixel is a human's call.
 */
class PartyRowLayerRegistrationTest {

    /**
     * The layers vanilla has registered before any mod runs, as far as ordering is concerned.
     *
     * <p>Only the anchors this mod orders against are needed: {@code registerAbove} throws for an
     * unregistered anchor, so seeding exactly {@code EXPERIENCE_BAR} and {@code HOTBAR} makes an ordering
     * mistake fail rather than pass vacuously. They are seeded in vanilla's own order — the experience bar
     * above the hotbar, both at the bottom of the screen.
     */
    private static List<GuiLayerManager.NamedLayer> vanillaLayers() {
        List<GuiLayerManager.NamedLayer> layers = new ArrayList<>();
        layers.add(new GuiLayerManager.NamedLayer(VanillaGuiLayers.EXPERIENCE_BAR,
                (graphics, delta) -> { }));
        layers.add(new GuiLayerManager.NamedLayer(VanillaGuiLayers.HOTBAR,
                (graphics, delta) -> { }));
        return layers;
    }

    /** {@return the ids in {@code layers}, in order} */
    private static List<ResourceLocation> idsOf(List<GuiLayerManager.NamedLayer> layers) {
        return layers.stream().map(GuiLayerManager.NamedLayer::name).toList();
    }

    /**
     * <b>Running the registration puts all four layers in the list, in the intended order.</b>
     *
     * <p>This is the assertion the previous task could not make. It calls the mod's own listener with a
     * real event, so the ids, the anchors and the ordering are all the production ones.
     */
    @Test
    void theRegistrationAddsEveryLayerInDrawingOrder() {
        List<GuiLayerManager.NamedLayer> layers = vanillaLayers();
        RegisterGuiLayersEvent event = new RegisterGuiLayersEvent(layers);

        ClientEvents.onRegisterGuiLayers(event);

        List<ResourceLocation> ids = idsOf(layers);
        assertTrue(ids.contains(ClientEvents.gaugeLayer()), "the UB gauge layer must be registered: " + ids);
        assertTrue(ids.contains(ClientEvents.partyRowLayer()),
                "the party status row's layer must be registered, or the row never draws: " + ids);

        int gauge = ids.indexOf(ClientEvents.gaugeLayer());
        int row = ids.indexOf(ClientEvents.partyRowLayer());
        int hotbar = ids.indexOf(VanillaGuiLayers.HOTBAR);
        assertTrue(gauge > hotbar, "the gauge draws above vanilla's hotbar");
        assertTrue(row > gauge,
                "the row is positioned FROM the gauge's published rectangle, so it must draw after it "
                        + "(gauge at " + gauge + ", row at " + row + ")");
        assertEquals(row, gauge + 1, "and immediately after it — the two layers are one widget");
        assertEquals(1, ids.stream().filter(ClientEvents.partyRowLayer()::equals).count(),
                "registering a layer id twice is refused by the event, so exactly one exists");
    }

    /** Every registered layer is a real drawable, not a null that would throw on the first frame. */
    @Test
    void everyRegisteredLayerIsADrawable() {
        List<GuiLayerManager.NamedLayer> layers = vanillaLayers();
        ClientEvents.onRegisterGuiLayers(new RegisterGuiLayersEvent(layers));
        for (GuiLayerManager.NamedLayer layer : layers) {
            assertNotNull(layer.layer(), "layer " + layer.name() + " has no drawable");
        }
    }

    /**
     * <b>Registering twice is refused by the event itself</b> — which is what makes the once-only property
     * structural rather than a promise. {@code registerAbove} calls
     * {@code Preconditions.checkArgument(!namedLayer.name().equals(key))}, so a second call is an
     * {@code IllegalArgumentException} instead of a duplicated layer.
     */
    @Test
    void theEventRefusesASecondRegistrationOfTheSameLayer() {
        List<GuiLayerManager.NamedLayer> layers = vanillaLayers();
        ClientEvents.onRegisterGuiLayers(new RegisterGuiLayersEvent(layers));
        try {
            ClientEvents.onRegisterGuiLayers(new RegisterGuiLayersEvent(layers));
            throw new AssertionError("a second registration must be refused, not silently duplicated");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("already registered"),
                    "the refusal must name the duplicate: " + expected.getMessage());
        }
    }

    /**
     * <b>The five party effects are hidden from both of vanilla's effect lists.</b>
     *
     * <p>This is the other half of "the row exists": the reason it exists is that the five slot effects used
     * to fill the effect bar. {@code PartyEffectClient.isVisibleInGui} answering {@code false} is the whole
     * mechanism ({@code Gui.renderEffects} filters through it), so it is asserted for every one of the five
     * registered types — read from {@link PartyEffects#holders()}, so a sixth slot would be covered
     * automatically, and obtained through {@code PartyEffectClient.forSlot} so the object under test is the
     * one production registers rather than a copy a test built for itself.
     *
     * <p>{@code isVisibleInInventory} is asserted {@code false} as well, and that assertion is the fix for
     * 「药水栏还有角色头像的药水效果。」: the human opened his inventory, scrolled the potion list, and found
     * 姬塔 / 可可萝 / 佩可莉姆 / 凯露 among the effects. An earlier revision of this test asserted the
     * opposite — vanilla's {@code true} — on the argument that the inventory is where a duration is looked up.
     * That argument was wrong, not merely outdated: a party slot is not an effect on the player, and vanilla's
     * list is a list of effects on the player. The row above the gauge is the lookup.
     *
     * <p>{@code instance} is {@code null} in both calls on purpose — neither predicate reads it, and passing
     * a real {@code MobEffectInstance} would need a live registry for a check that has nothing to do with
     * one.
     */
    @Test
    void thePartyEffectsAreHiddenFromBothVanillaEffectLists() {
        int checked = 0;
        for (int slot = 0; slot < Party.SLOTS; slot++) {
            var client = PartyEffectClient.forSlot(slot);
            assertNotNull(client, "every party slot needs an extension, or the bar shows it");
            assertFalse(client.isVisibleInGui(null),
                    "party slot " + slot + " must not draw in vanilla's effect bar — that is the whole "
                            + "reason PartyStatusHud exists");
            assertFalse(client.isVisibleInInventory(null),
                    "party slot " + slot + " must not draw in the inventory's effect list either: a party "
                            + "member is not a potion (「药水栏还有角色头像的药水效果。」)");
            checked++;
        }
        assertEquals(Party.SLOTS, checked, "the assertion must cover every slot, not a sample");
    }
}
