package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.effect.PartyEffects;
import java.util.List;
import net.neoforged.neoforge.client.extensions.common.IClientMobEffectExtensions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The effect bar shows skill rows and nothing else — every generic effect is hidden, on both surfaces.</b>
 *
 * <h2>The two defects this pins</h2>
 * His words: 「药水栏不是把每个通用效果都显示出来，是仅显示角色的技能图标，是技能图标不是效果图标。」 and his screenshots,
 * which showed 攻击力提升 / 魔法防御力 / 行动速度 / 护盾 in <b>magenta</b> plus a 「技能效果 1」 row.
 *
 * <p>The magenta and the row count are one defect: a generic effect is a real {@code MobEffect}, none of them was
 * hidden, and their per-effect sprites had been deleted — so vanilla drew thirteen rows, each with the
 * missing-texture checkerboard. The fix is not a picture per effect (that would be thirteen pictures for
 * mechanics nobody reads); it is that <b>no</b> generic effect belongs in either of vanilla's lists.
 *
 * <h2>Why the assertion walks the registry list rather than a literal</h2>
 * {@link PartyEffects#mechanicHolders()} is the declaration every other consumer already reads — the sprite
 * generator's manifest, {@code EffectSpriteResourceTest}'s id source, and now this. A fourteenth generic effect
 * added to it arrives hidden with no further edit, and a test written against a hand-typed list of thirteen names
 * would have silently gone on passing while the real one was visible.
 *
 * <h2>What this cannot check</h2>
 * That the bar <em>looks</em> right. The predicates are the whole mechanism ({@code Gui.renderEffects} and
 * {@code EffectRenderingInventoryScreen} filter their lists through them), but "the column is now three rows
 * instead of sixteen" is a pixel, and a pixel is a human's check. The report says so.
 */
class HiddenEffectClientTest {

    /**
     * <b>Every generic effect answers {@code false} from both visibility predicates.</b>
     *
     * <p>Read from the registry's own declaration and asked of the object production registers
     * ({@link HiddenEffectClient#instance()}), not of an equivalent a test built for itself — a test that
     * constructed its own would pass while {@code ClientEvents} registered something else.
     *
     * <p>{@code instance} is {@code null} on purpose: neither predicate reads it, and building a real
     * {@code MobEffectInstance} would need a live registry for a check that has nothing to do with one.
     */
    @Test
    void everyGenericEffectIsHiddenFromBothVanillaLists() {
        List<? extends net.neoforged.neoforge.registries.DeferredHolder<
                net.minecraft.world.effect.MobEffect, net.minecraft.world.effect.MobEffect>> holders =
                PartyEffects.mechanicHolders();
        assertFalse(holders.isEmpty(), "sanity: the manifest must not be empty");
        IClientMobEffectExtensions hider = HiddenEffectClient.instance();
        assertNotNull(hider);
        int checked = 0;
        for (var holder : holders) {
            assertFalse(hider.isVisibleInGui(null),
                    "generic effect " + holder.getKey().location()
                            + " must not draw in vanilla's HUD column — that column reports fired skills");
            assertFalse(hider.isVisibleInInventory(null),
                    "generic effect " + holder.getKey().location()
                            + " must not appear in the inventory's effect list either");
            checked++;
        }
        assertEquals(holders.size(), checked, "the assertion must cover every generic effect, not a sample");
    }

    /**
     * <b>The party slots keep their own hider, and the two do not collapse into one.</b>
     *
     * <p>Both families answer {@code false}, so a careless refactor could merge them — and then the next decision
     * about party rows would silently move the generic effects too. They are different questions
     * (「谁和我在一起」 vs 「这是个机制」) that happen to share an answer today, so they stay two objects and this
     * asserts it.
     */
    @Test
    void thePartyHiderIsStillItsOwnObject() {
        assertFalse(PartyEffectClient.forSlot(0).getClass() == HiddenEffectClient.instance().getClass(),
                "the party family and the generic family must stay two extensions");
    }

    /**
     * <b>One instance serves every generic effect — the family is one decision.</b>
     *
     * <p>This is the "one table, not thirteen copies" requirement made structural: the registry map holds the
     * <em>same</em> object for all of them, so there is no per-effect override anywhere to drift. A per-effect
     * subclass would satisfy the two assertions above and fail this one.
     */
    @Test
    void oneSharedInstanceServesEveryGenericEffect() {
        assertSame(HiddenEffectClient.INSTANCE, HiddenEffectClient.instance());
        for (var holder : PartyEffects.mechanicHolders()) {
            assertTrue(holder.getKey().location().getNamespace().equals("astrum"),
                    "a generic effect is registered in this mod's namespace: " + holder.getKey().location());
        }
    }

    /**
     * <b>Hiding is a drawing predicate, so the effects must still exist and still be distinct.</b>
     *
     * <p>The failure mode this guards against is somebody "simplifying" the thirteen registrations into one now
     * that they are invisible. Vanilla keys a player's effects by <b>type</b>, so one shared type would merge
     * thirteen simultaneous mechanics into a single instance and change behaviour that has nothing to do with
     * drawing — {@code ShieldPool}, {@code EffectTiers} and the skill-entry duration all read the instances.
     */
    @Test
    void theEffectsAreStillSeparatelyRegistered() {
        List<? extends net.neoforged.neoforge.registries.DeferredHolder<
                net.minecraft.world.effect.MobEffect, net.minecraft.world.effect.MobEffect>> holders =
                PartyEffects.mechanicHolders();
        long distinctPaths = holders.stream()
                .map(holder -> holder.getKey().location().getPath())
                .distinct()
                .count();
        assertEquals(holders.size(), distinctPaths,
                "every generic effect keeps its own registry path; only the *client extension* is shared");
        assertEquals(holders.size(), PartyEffects.mechanicHolders().size(),
                "and the manifest is the same size twice — it is a method, not a shared mutable list");
    }
}
