package com.astrum.pcrd.impl.effect;

import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import org.jetbrains.annotations.ApiStatus;

/**
 * One <b>skill's</b> entry in vanilla's effect bar: the row a fired skill leaves, wearing that skill's own name
 * and picture.
 *
 * <h2>What changed, and why the type now knows everything</h2>
 * This used to be one type per <b>party slot</b> ({@code astrum:skill_1..5}), and because five skills had to
 * share one type, the skill travelled in the instance's <b>amplifier</b> as a row index into
 * {@code SkillRows}. The human found the cost: 「他只会显示冻结之湖或者雪鞋，不会两个同时存在」 — vanilla merges
 * effects by type, so her two skills were one entry and the second overwrote the first.
 *
 * <p>So the type is the identity ({@link SkillEffectTypes.Identity}: a character and a skill), and the
 * amplifier is <b>no longer used at all</b> — no row index, no slot, no "a level" for vanilla to print beside
 * the name. Everything the old design had to look up from outside is now a property of this object:
 *
 * <ul>
 *   <li><b>which character</b> — {@link #identity()}, written as data, so the client no longer reads the
 *       party sync to find out who is in a slot;</li>
 *   <li><b>which skill</b> — the {@code skill} tag, so the name, the picture and the effect list are resolved
 *       by tag from the same {@code SkillRows} join the character book draws, instead of by an index that a
 *       stale entry could point past the end of;</li>
 *   <li><b>which name</b> — {@link #getDescriptionId()} becomes a pure function of the type, which is what
 *       lets JEED and vanilla's own list read something true without asking the instance anything.</li>
 * </ul>
 *
 * <h2>Two different skills coexist; the same skill refreshes</h2>
 * Vanilla keys a player's effects by type and {@code MobEffectInstance.update} merges a re-application of the
 * same type. With one type per skill that is exactly the human's rule: two different skills are two types and
 * two live instances; the same skill fired twice is one type, so the instance's clock is refreshed (the longer
 * of the two durations wins) rather than a second row appearing. {@code SkillEntryCoexistenceTest} asserts both
 * against real {@link net.minecraft.world.effect.MobEffectInstance}s.
 *
 * <p>Inert by construction: no attribute modifier, no tick ({@code shouldApplyEffectTickThisTick} stays
 * {@code false}), so its whole life is vanilla's own effect expiry — there is nothing to schedule, and
 * therefore no tick listener anywhere in this feature.
 */
@ApiStatus.Internal
public final class AstrumSkillEffect extends MobEffect {

    private final SkillEffectTypes.Identity identity;

    /**
     * @param identity which character's which skill this type reports
     */
    public AstrumSkillEffect(SkillEffectTypes.Identity identity) {
        super(MobEffectCategory.BENEFICIAL, colourOf(identity));
        this.identity = identity;
    }

    /** {@return the one character-and-skill this type stands for} */
    public SkillEffectTypes.Identity identity() {
        return identity;
    }

    /**
     * {@return the translation key vanilla and every recipe viewer resolve this effect through}
     *
     * <h2>Why a per-character key and not a per-skill one</h2>
     * The skill's name is <b>data</b> — a ladder that renames it at 6★ (广域斩击+ → 黄金耀光) — so a translation
     * cannot hold it, and 91 keys would be 91 stale strings. What a type-level method <em>can</em> answer is
     * the character, through {@link SkillEffectTypes#nameKeyFor}: {@code astrum.effect.skill_row.<path>}, the
     * 22 keys both language files carry. The exact skill name is answered by {@link #getDisplayName()} and by
     * the hover tooltip, which do have the row.
     *
     * <h2>Why this is a pure function now</h2>
     * The slot version of this method had to ask the client's party cache who was in its slot, because the type
     * did not know; a dedicated server answered the fallback key and a client before its first sync did too.
     * The character is in the type, so the key is the same on both sides, at every moment, with no cache read.
     */
    @Override
    public String getDescriptionId() {
        return SkillEffectTypes.nameKeyFor(identity.character());
    }

    /**
     * {@return the row's name — the skill's, else the character's, else one generic sentence; never a number}
     *
     * <h2>The three answers</h2>
     * <ol>
     *   <li><b>the skill's name</b> at her current affection tier — the row {@code SkillRows} resolves for this
     *       type's own {@code skill} tag, which is the same join the character book draws;</li>
     *   <li><b>the character's name</b>, for a skill the loaded pack no longer defines (a type from a jar a
     *       little older than the data): 「是她的哪个技能我不知道，但我知道是她」;</li>
     *   <li>{@link SkillEffectTypes#FALLBACK_NAME_KEY} — 「技能效果」 — when not even she is known.</li>
     * </ol>
     * Never the old 「技能效果 1 二级」: this type has no slot digit, and vanilla's effect-level suffix is not
     * appended to this method's answer. The skill's own name is the whole point of one type per skill.
     *
     * <p>{@link #getDescriptionId()} covers the surfaces that resolve a <em>key</em> rather than a name (JEED
     * does); this covers vanilla's own. Those two are the only name paths a {@code MobEffect} has.
     */
    @Override
    public Component getDisplayName() {
        java.util.Optional<String> name = resolveName();
        // Component.literal for the known answers; the one shared key only when nothing can say. A translation
        // of the fallback is a real sentence in both languages (「技能效果」 / "Skill"), never a raw key.
        return name.<Component>map(Component::literal)
                .orElseGet(() -> Component.translatable(SkillEffectTypes.FALLBACK_NAME_KEY));
    }

    /** {@return the name from the data at the local player's tier, or empty when nothing can say} */
    private java.util.Optional<String> resolveName() {
        // Dist first, and a nested client class second: this class is loaded on a dedicated server, where
        // there is neither a roster push nor a party, and touching a client class there would fail to link.
        if (!net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            return java.util.Optional.empty();
        }
        return ClientNames.nameOf(identity);
    }

    /**
     * The client-only half, in its own nested class so loading {@link AstrumSkillEffect} on a dedicated server
     * cannot pull a client class in.
     *
     * <p>Nested and private: it exists to be unreachable from the server's class-loading path, not to be
     * reused. Every join it makes — the tier from the roster push, the character from the registry, the row from
     * {@code SkillRows} — is the same call the tooltip and the row make, so the three cannot name different
     * skills.
     */
    @net.neoforged.api.distmarker.OnlyIn(net.neoforged.api.distmarker.Dist.CLIENT)
    private static final class ClientNames {

        static java.util.Optional<String> nameOf(SkillEffectTypes.Identity identity) {
            com.astrum.pcrd.api.v1.CharacterDef definition = com.astrum.pcrd.api.v1.AstrumApi.characters()
                    .map(registry -> registry.get(identity.character()))
                    .orElse(null);
            if (definition == null) {
                return java.util.Optional.empty();
            }
            int tier = com.astrum.pcrd.impl.client.TierAvatar.tierOf(identity.character());
            java.util.Optional<com.astrum.pcrd.impl.party.SkillRows.Row> row =
                    com.astrum.pcrd.impl.party.SkillRows.rowOf(definition, tier, identity.skill());
            if (row.isPresent() && !row.get().name().isBlank()) {
                return java.util.Optional.of(row.get().name());
            }
            String name = definition.displayName();
            return name == null || name.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(name);
        }

        private ClientNames() {
        }
    }

    /**
     * {@return the effect colour for {@code identity}}
     *
     * <p>Vanilla's own effect-particle tint, and the colour a client that draws a plain frame shows. The
     * palette is the <b>skill family's</b>, brighter and more saturated than
     * {@link PartyEffectSlots#SLOT_COLORS}, because a skill entry is an event that just happened while a party
     * rune is a standing fact — and the two families sit in the same column. Indexed by the character, so the
     * skills of one character share a tint and two characters' rows are distinguishable even before the art
     * resolves; no registry, no client, a pure function of the id.
     *
     * @throws IllegalArgumentException when there is no identity — this runs inside {@code super(...)}, and a
     *         missing identity must fail with a sentence rather than as a {@code NullPointerException}
     */
    private static int colourOf(SkillEffectTypes.Identity identity) {
        if (identity == null) {
            throw new IllegalArgumentException("a skill entry needs a (character, skill) identity");
        }
        return SKILL_COLORS[Math.floorMod(identity.character().getPath().hashCode(), SKILL_COLORS.length)];
    }

    /**
     * One colour per character bucket. Six, so the hues are far apart rather than a gradient: the point is
     * telling two rows apart at a glance, not decorating them.
     *
     * <p>Disjoint from {@link PartyEffectSlots#SLOT_COLORS} — asserted by {@code SkillEffectTypesTest}, because
     * a skill row and a party rune sharing a tint would read as two halves of one thing.
     */
    static final int[] SKILL_COLORS = {
            0x6FD3FF, 0xFF9AD5, 0x8CE0A0, 0xFFD97A, 0xC9A0FF, 0x7FE3E0,
    };
}