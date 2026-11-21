package com.astrum.pcrd.impl.item;

import com.astrum.pcrd.impl.InternalIds;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A rolled card carries both halves; a blank card carries neither.</b>
 *
 * <h2>Why this is asserted on a real {@link ItemStack}</h2>
 * The requirement is about what a stack <em>holds</em>: 「角色卡在抽完之后，图标要换成角色头像」 is one component
 * ({@code custom_model_data}) and 「这一张卡给谁」 is another ({@code astrum:character_id}). They are written by
 * one method ({@link CardStamp#stamp}) because four sites writing two components is four chances to write
 * three of them — and the failure mode is silent: the tooltip would name コッコロ while the icon drew the
 * generic card, and nothing would log. So the pair is checked against an actual stack, not against a
 * description of one.
 *
 * <h2>The two cases that must stay empty</h2>
 * <ul>
 *   <li><b>Creative-mode blank card</b> — no character, so no index either, so the generic card art. A
 *       component with index 0 would be worse than useless: {@code ItemProperties} reads an <b>absent</b>
 *       component as {@code 0.0f}, so 0 is the "no override" number and writing it changes nothing while
 *       claiming otherwise.</li>
 *   <li><b>A character the index file does not list</b> — she gets her id (the card must still enrol her)
 *       and <b>no</b> index, so she keeps the generic art rather than borrowing somebody else's face.</li>
 * </ul>
 */
class CardStampTest {

    private static final ResourceLocation DJEETA = InternalIds.id("djeeta");
    private static final ResourceLocation KYARU = InternalIds.id("kyaru");

    /** A card stack, built without a world — the same construction {@code TokenExchangeTest} uses. */
    private static ItemStack card() {
        return new ItemStack(AstrumItems.CHARACTER_CARD.get(), 1);
    }

    /**
     * <b>The rolled card names her and carries the index the model override expects.</b>
     *
     * <p>The index is read back from the shipped {@code card_index.json} through {@link CardIndex}, so this
     * asserts the two agree: if somebody renumbers the file, this test follows it, and
     * {@code CardIndexResourceTest} is what catches the models disagreeing.
     */
    @Test
    void aRolledCardCarriesBothTheCharacterAndItsIndex() {
        ItemStack rolled = card();
        assertTrue(CardStamp.stamp(rolled, DJEETA), "stamping a real character must write");

        assertEquals(DJEETA, CardStamp.characterOf(rolled), "the card must enrol 姬塔");
        assertEquals(CardIndex.indexOf(DJEETA), CardStamp.indexOf(rolled),
                "the stack's custom_model_data must be the index file's number for her");
        assertTrue(CardStamp.indexOf(rolled) > 0,
                "and it must be non-zero, or no override matches and the icon stays generic");
        assertEquals(new CustomModelData(CardIndex.indexOf(DJEETA)),
                rolled.get(DataComponents.CUSTOM_MODEL_DATA),
                "the component itself, not merely a number this class can read back");
    }

    /**
     * <b>A blank card carries neither component.</b>
     *
     * <p>Both halves matter, and they fail differently: an id with no index draws the generic art on a card
     * that works (confusing but harmless), while an index with no id would paint a face on a card that
     * enrols nobody.
     */
    @Test
    void aBlankCardCarriesNeitherComponent() {
        ItemStack blank = card();
        assertNull(CardStamp.characterOf(blank), "a fresh card names nobody");
        assertEquals(0, CardStamp.indexOf(blank), "and therefore carries no model index");
        assertNull(blank.get(DataComponents.CUSTOM_MODEL_DATA),
                "the component must be ABSENT, not present with value 0: 0 is what an absent component "
                        + "already reads, so writing it would assert something untrue");
    }

    /** Stamping nobody writes nothing at all, so a failed roll cannot produce a half-rolled card. */
    @Test
    void stampingNoCharacterWritesNothing() {
        ItemStack blank = card();
        assertFalse(CardStamp.stamp(blank, null));
        assertNull(CardStamp.characterOf(blank));
        assertNull(blank.get(DataComponents.CUSTOM_MODEL_DATA));
        assertFalse(CardStamp.stamp(null, DJEETA), "a null stack is a no-op, never a throw");
        assertFalse(CardStamp.stamp(ItemStack.EMPTY, DJEETA), "and so is an empty one");
    }

    /**
     * <b>A character the index does not list keeps the generic art.</b>
     *
     * <p>Not an edge case to tolerate: a pack author who adds a character before re-running the art pipeline
     * must get a usable, working card with the generic icon — not a crash, and not another girl's face.
     */
    @Test
    void aCharacterWithoutAnIndexKeepsTheGenericArt() {
        ResourceLocation unlisted = InternalIds.id("not_in_the_index");
        assertEquals(0, CardIndex.indexOf(unlisted), "the fixture assumes she is not in the index file");
        ItemStack rolled = card();
        assertTrue(CardStamp.stamp(rolled, unlisted));
        assertEquals(unlisted, CardStamp.characterOf(rolled), "she is still enrolable");
        assertNull(rolled.get(DataComponents.CUSTOM_MODEL_DATA),
                "but she has no face to draw, so no index and the generic card art");
    }

    /**
     * <b>Re-stamping replaces rather than accumulates.</b>
     *
     * <p>A stack can be stamped twice on one path — the crafted stack is stamped, then handed to the
     * inventory — and a second call must leave exactly one id and one index, not merge two characters.
     */
    @Test
    void stampingTwiceLeavesOneCharacter() {
        ItemStack rolled = card();
        CardStamp.stamp(rolled, DJEETA);
        CardStamp.stamp(rolled, KYARU);
        assertEquals(KYARU, CardStamp.characterOf(rolled), "the later stamp wins outright");
        assertEquals(CardIndex.indexOf(KYARU), CardStamp.indexOf(rolled));
    }

    /**
     * <b>Every shipped character maps to a distinct index, and all twenty-two are 1..22 in index-file order.</b>
     *
     * <p>The report claims "index 1 is 姬塔" and the log line quotes the numbers, so they are pinned here.
     * A renumbering is a deliberate edit, never a silent drift.
     */
    @Test
    void theShippedCharactersAreNumberedOneToTwentyTwo() {
        CardIndex.Table table = CardIndex.table();
        List<ResourceLocation> inOrder = table.charactersInIndexOrder();
        assertEquals(List.of(InternalIds.id("djeeta"),
                InternalIds.id("kokkoro"),
                InternalIds.id("pecorine"),
                InternalIds.id("kyaru"),
                InternalIds.id("sheffy"),
                InternalIds.id("hiyori"),
                InternalIds.id("rei"),
                InternalIds.id("kurumi"),
                InternalIds.id("saren"),
                InternalIds.id("mimi"),
                InternalIds.id("muimi"),
                InternalIds.id("suzume"),
                InternalIds.id("misogi"),
                InternalIds.id("kyoka"),
                InternalIds.id("rino"),
                InternalIds.id("ayane"),
                InternalIds.id("yui"),
                InternalIds.id("shizuru"),
                InternalIds.id("nozomi"),
                InternalIds.id("tsumugi"),
                InternalIds.id("chika"),
                InternalIds.id("christina")), inOrder,
                "the index runs 1..22 in this order: " + inOrder);
        List<Integer> indices = new ArrayList<>();
        for (ResourceLocation id : inOrder) {
            indices.add(table.indexOf(id));
        }
        assertEquals(java.util.stream.IntStream.rangeClosed(1, 22).boxed().toList(), indices,
                "contiguous from 1, so no number is dead");
    }

    /**
     * <b>Nothing outside {@link CardStamp} writes the character-id component.</b>
     *
     * <p>The "do not duplicate a rule" requirement, checked mechanically: the roll has exactly one
     * implementation of "a rolled card has both components", and the two roll sites (the crafting lottery and
     * the memory-token exchange) both go through it. A future third site that writes {@code CHARACTER_ID} by
     * hand is exactly the bug this file exists to prevent — it would ship cards with a name and no face — so
     * it fails here rather than in the human's inventory.
     *
     * <p>{@code AstrumDataComponents} is exempt: it declares the type. {@code CharacterCardItem} is exempt
     * for <b>reading</b> only, which this check allows by looking for the setter, not the getter.
     */
    @Test
    void onlyCardStampWritesTheCharacterId() {
        java.nio.file.Path root = com.astrum.pcrd.impl.client.TestProjectRoot.resolve("src/main/java");
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null && java.nio.file.Files.isDirectory(root),
                "main sources not reachable; this assertion would be vacuous rather than wrong");
        List<String> offenders = new ArrayList<>();
        try (var files = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (name.equals("CardStamp.java") || name.equals("AstrumDataComponents.java")) {
                    continue;
                }
                String source = java.nio.file.Files.readString(file)
                        .replaceAll("(?s)/\\*.*?\\*/", "")
                        .replaceAll("(?m)//.*$", "");
                if (source.contains("set(AstrumDataComponents.CHARACTER_ID.get()")) {
                    offenders.add(name);
                }
            }
        } catch (java.io.IOException e) {
            throw new AssertionError("could not walk " + root, e);
        }
        assertTrue(offenders.isEmpty(),
                "the character id must be written only by CardStamp.stamp, which also writes the model "
                        + "index; these files write it by hand: " + offenders);
    }

    /** The two model-index component writes are likewise one place, for the same reason. */
    @Test
    void onlyCardStampWritesTheModelIndex() {
        java.nio.file.Path root = com.astrum.pcrd.impl.client.TestProjectRoot.resolve("src/main/java");
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null && java.nio.file.Files.isDirectory(root),
                "main sources not reachable");
        List<String> offenders = new ArrayList<>();
        try (var files = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("CardStamp.java")) {
                    continue;
                }
                String source = java.nio.file.Files.readString(file)
                        .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
                if (source.contains("set(DataComponents.CUSTOM_MODEL_DATA")) {
                    offenders.add(file.getFileName().toString());
                }
            }
        } catch (java.io.IOException e) {
            throw new AssertionError("could not walk " + root, e);
        }
        assertTrue(offenders.isEmpty(),
                "custom_model_data is a rendering detail of a rolled card and must be written with the "
                        + "character id in one call; these files write it separately: " + offenders);
    }

    /** {@return the card item, so a null registration failure reads as this rather than as an NPE} */
    @Test
    void theCardItemIsTheOneTheRollsUse() {
        assertNotNull(AstrumItems.CHARACTER_CARD.isBound() ? AstrumItems.CHARACTER_CARD.get() : null,
                "a stamped stack must be a character card, or the index means nothing");
    }
}
