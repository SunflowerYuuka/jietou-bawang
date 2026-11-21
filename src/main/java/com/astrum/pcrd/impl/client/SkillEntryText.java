package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.UbEffect;
import com.astrum.pcrd.api.v1.UbTier;
import com.astrum.pcrd.impl.effect.SkillEntries;
import com.astrum.pcrd.impl.effect.SkillEffectTypes;
import com.astrum.pcrd.impl.growth.AffectionKernel;
import com.astrum.pcrd.impl.growth.GrowthFacts;
import com.astrum.pcrd.impl.party.BookText;
import com.astrum.pcrd.impl.party.SkillRows;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>The one formatter behind a skill entry's tooltip and its inventory row: what the skill actually did, as
 * one line per state it left.</b>
 *
 * <h2>The rule</h2>
 * 「效果是这个技能的统合效果」 — the entry's <em>effect</em> text is not the one mechanism the skill happened to
 * apply; it is the skill's own combination. So this class walks the skill's effects at the caster's tier and
 * prints every one that leaves a state, by its own name and its own remaining time:
 *
 * <pre>
 *   加速
 *   全体攻击力提升　持续 12 秒
 * </pre>
 *
 * <h2>Why the list is produced from the hooks, and the durations from {@link SkillEntries}</h2>
 * Which effects a skill has is {@link SkillRows}' join (the same one the character book and the party effect
 * read), and how long each leaves the player in a state is {@link SkillEntries#ticksOf} — the same call the
 * server made when it wrote the entry. A formatter that measured the durations itself would be a second answer
 * to "how long is this buff", and the tooltip would eventually disagree with the row it describes.
 *
 * <h2>One formatter, keyed by {@link EffectType}</h2>
 * {@link #effectLine} is the only place an effect becomes English/Chinese text, and it delegates the name to
 * {@link BookText#effectKey(EffectType)} — the key the 技能详情 page already uses for the same effect. That is
 * deliberately <b>one</b> method rather than a per-effect sentence: a new effect type reaches the tooltip
 * automatically, and cannot acquire a hand-written description that drifts from its own row in the book.
 *
 * <p>Not {@code @OnlyIn}-guarded for its arithmetic: {@link #secondsOf} and {@link #effectsOf} are pure and
 * {@code SkillEntryTextTest} runs them without a game. The class is client-only because
 * {@link Component#translatable} is only ever resolved on a client, which is where a tooltip lives.
 */
@ApiStatus.Internal
@OnlyIn(Dist.CLIENT)
final class SkillEntryText {

    /** 「持续 %s 秒」 — the one duration phrase, so the HUD row and the tooltip cannot word it differently. */
    static final String KEY_DURATION = BookText.KEY_SKILL_ENTRY_DURATION;

    private SkillEntryText() {
    }

    /**
     * {@return everything the entry for {@code skill} of {@code def} says: the skill's name, then one line per
     * state it left}
     *
     * <p>Never returns an empty list, whatever the inputs: a row that cannot name its skill still names
     * <em>something</em> (see {@link #title}), because an empty tooltip falls back to vanilla's numbered
     * per-type name — the exact string the human photographed.
     *
     * @param def   the character the entry belongs to, or {@code null} when the pack does not define her
     * @param tier  her affection tier
     * @param skill the skill tag the entry's <b>type</b> carries, {@code AffectionKernel.ubKey()} for the
     *              ultimate
     */
    static List<Component> lines(@Nullable CharacterDef def, int tier, @Nullable String skill) {
        List<Component> lines = new ArrayList<>();
        // Coloured on the copy: Component.withColor lives on MutableComponent, not on the interface, and
        // Component.copy() hands back a MutableComponent for exactly this. One call that works for both a
        // literal and a translation, and it keeps the fallback a Component either way.
        lines.add(title(def, tier, skill).copy().withColor(COLOUR_NAME));
        SkillRows.Row chosen = rowOf(def, tier, skill);
        if (chosen == null) {
            return lines;
        }
        // <b>One component holding every effect, separated by newlines</b> — not one list element per effect.
        // A list of separate elements looked right in vanilla's own tooltip and wrong under JEED, which laid
        // some of them side by side and some on their own line: the human reported the same build looking
        // 「有时候两个效果写一行，有时候两个效果写两行」. JEED splits on the newline itself
        // (EffectInfoRecipe$ExpandNewLineTextAcceptor calls String.split), so the break has to be inside the
        // text rather than implied by the list structure.
        MutableComponent body = null;
        for (EffectType type : effectsOf(def, tier, chosen)) {
            Component part = effectLine(type, ticksOf(def, tier, chosen, type));
            body = body == null ? part.copy() : body.append(Component.literal(NEWLINE)).append(part);
        }
        if (body != null) {
            lines.add(body);
        }
        return lines;
    }

    /** The one newline every effect list is built with: {@code "\n"}, which JEED splits on. */
    static final String NEWLINE = "\n";

    /**
     * {@return the row's name line — the skill's, else the character's, else one generic word; never empty}
     *
     * <h2>The three answers, and why the third is not a number</h2>
     * <ol>
     *   <li><b>the skill's name</b> — {@code SkillLadder.nameAt(tier)}, what 「显示的药水图标就是这个技能的名字」
     *       asks for, and the answer whenever the client can reach the character and her row;</li>
     *   <li><b>the character's name</b> — for a skill tag this pack no longer defines for her (a type from a jar
     *       a little older than the data). It is true, and more useful than any placeholder: 「是她的哪个技能我
     *       不知道，但我知道是她」;</li>
     *   <li><b>{@link SkillEffectTypes#FALLBACK_NAME_KEY}</b> — 「技能效果」, when not even the character is
     *       known. <b>No slot number and no level.</b> The old fallback was {@code effect.astrum.skill_<n>},
     *       which vanilla resolves per <em>type</em> and then appends its own effect-level suffix to — so a
     *       slot number plus a 「二级」 that described our row index, not a strength. Both are wrong things to
     *       print, so neither survives here.</li>
     * </ol>
     *
     * <p>The skill is looked up <b>by tag</b> ({@link SkillRows#rowOf}), which is the identity the entry's effect
     * type carries; the row index it used to be read from is gone with the amplifier.
     */
    static Component title(@Nullable CharacterDef def, int tier, @Nullable String skill) {
        SkillRows.Row chosen = rowOf(def, tier, skill);
        if (chosen != null && !chosen.name().isBlank()) {
            return Component.literal(chosen.name());
        }
        if (def != null && !def.displayName().isBlank()) {
            return Component.literal(def.displayName());
        }
        return Component.translatable(SkillEffectTypes.FALLBACK_NAME_KEY);
    }

    /** {@return the inventory row's second line: every state the skill left, in one line} */
    static Component summary(@Nullable CharacterDef def, int tier, @Nullable String skill) {
        SkillRows.Row chosen = rowOf(def, tier, skill);
        if (chosen == null) {
            return Component.empty();
        }
        List<EffectType> types = effectsOf(def, tier, chosen);
        if (types.isEmpty()) {
            return Component.empty();
        }
        MutableComponent joined = null;
        for (EffectType type : types) {
            Component part = effectLine(type, ticksOf(def, tier, chosen, type));
            // A newline, not two spaces. This line is drawn inside JEED's effect panel, which is narrow and
            // does not wrap on its own terms: the human's screenshot showed 「全体物攻 持续 12 秒  TP提升
            // 持续 15 秒」 running off the right edge as one run-on sentence. JEED expands newlines
            // (its EffectInfoRecipe has expandNewlines), so one effect per line survives the trip.
            joined = joined == null
                    ? part.copy()
                    : joined.append(Component.literal(NEWLINE)).append(part);
        }
        return joined == null ? Component.empty() : joined;
    }

    /**
     * {@return the types of {@code row}'s effects that leave the caster in a state, in declaration order}
     *
     * <p>Filtered by {@link EffectType#persistsOnCaster()} — the same classification the entry itself was
     * written from, so the tooltip can never list a state the bar is not reporting.
     */
    static List<EffectType> effectsOf(@Nullable CharacterDef def, int tier, SkillRows.@Nullable Row row) {
        List<EffectType> types = new ArrayList<>();
        if (row == null) {
            return types;
        }
        if (row.ultimate()) {
            UbTier cast = ultimateTier(def, tier);
            if (cast == null) {
                return types;
            }
            for (UbEffect effect : cast.effects()) {
                if (effect == null || effect.scope() != UbEffect.Scope.ALLY
                        || !effect.type().persistsOnCaster()) {
                    continue;
                }
                if (SkillEntries.ticksForTiered(effect.type(), effect.params()) > 0) {
                    types.add(effect.type());
                }
            }
            return types;
        }
        for (EffectHook hook : row.hooks()) {
            if (hook == null || !hook.type().persistsOnCaster()) {
                continue;
            }
            if (SkillEntries.ticksOf(hook, 1.0) > 0) {
                types.add(hook.type());
            }
        }
        return types;
    }

    /** {@return how long {@code type} lasts for {@code row}, or {@code 0} when it leaves no clock} */
    static int ticksOf(@Nullable CharacterDef def, int tier, SkillRows.@Nullable Row row, EffectType type) {
        if (row == null || type == null) {
            return 0;
        }
        if (row.ultimate()) {
            UbTier cast = ultimateTier(def, tier);
            if (cast == null) {
                return 0;
            }
            int longest = 0;
            for (UbEffect effect : cast.effects()) {
                if (effect != null && effect.type() == type) {
                    longest = Math.max(longest,
                            SkillEntries.ticksForTiered(effect.type(), effect.params()));
                }
            }
            return longest;
        }
        int longest = 0;
        for (EffectHook hook : row.hooks()) {
            if (hook != null && hook.type() == type) {
                longest = Math.max(longest, SkillEntries.ticksOf(hook, 1.0));
            }
        }
        return longest;
    }

    /**
     * {@return the one-sentence line for an effect and its remaining time}
     *
     * <p>This is the formatter the report names: the <b>only</b> place an {@link EffectType} becomes text on
     * this surface. The name is {@link BookText#effectKey(EffectType)}, i.e. the same translation the 技能详情
     * page shows for the same effect, and the duration phrase is {@link #KEY_DURATION}. A type with no clock
     * (a permanent flat grant) prints its name alone rather than 「0 秒」.
     *
     * @param type  the effect
     * @param ticks its duration, or {@code 0} for "no clock"
     */
    static Component effectLine(EffectType type, int ticks) {
        // The SHORT name, not the full one: this line is drawn inside JEED's effect panel, which is a narrow
        // column, and 「全体攻击力提升 · 持续 12 秒」 ran past its right edge (the human's screenshot). The
        // short vocabulary already exists for exactly this — 「全体物攻」 — so this is not a third naming.
        Component name = Component.translatable(BookText.effectShortKey(type));
        if (ticks <= 0) {
            return name;
        }
        return name.copy().append(Component.literal(SEPARATOR)
                .append(Component.translatable(KEY_DURATION, secondsOf(ticks)))
                .withColor(COLOUR_DIM));
    }

    /**
     * {@return {@code ticks} as the seconds a player reads — {@code "12"} or {@code "1.5"}, never {@code "12.0"}}
     *
     * <p>One decimal only when the tick count is not a whole number of seconds: 1.5 s is a real duration in this
     * mod (恐慌 is 30 ticks), while 「12.0 秒」 reads like a spreadsheet.
     *
     * @param ticks a duration; {@code <= 0} answers {@code "0"}
     */
    static String secondsOf(int ticks) {
        if (ticks <= 0) {
            return "0";
        }
        int whole = ticks / 20;
        int remainder = ticks % 20;
        if (remainder == 0) {
            return Integer.toString(whole);
        }
        // One decimal, rounded — 30t -> 1.5, 25t -> 1.3. Trailing zero trimmed, so a 1.0 is never printed.
        int tenths = (int) Math.round(ticks / 2.0);
        if (tenths % 10 == 0) {
            return Integer.toString(tenths / 10);
        }
        return (tenths / 10) + "." + (tenths % 10);
    }

    /**
     * {@return the row of the character's skill list that {@code skill} names, or {@code null} when there is
     * none}
     *
     * <p>Delegates to {@link SkillRows#rowOf}, which owns the join; this is the nullable view the drawing code
     * wants, and a caller must not re-implement the lookup (the bar, the book and the effect's own display name
     * all read it).
     */
    static SkillRows.@Nullable Row rowOf(@Nullable CharacterDef def, int tier, @Nullable String skill) {
        return SkillRows.rowOf(def, tier, skill).orElse(null);
    }

    /**
     * {@return the ultimate tier the character casts at {@code tier}, or {@code null}}
     *
     * <p>{@link AffectionKernel#usesEnhancedUb(int)} decides base versus enhanced — the same rule the cast path
     * uses, so the tooltip describes the ultimate the player will actually get.
     */
    static @Nullable UbTier ultimateTier(@Nullable CharacterDef def, int tier) {
        if (def == null || def.ub() == null) {
            return null;
        }
        return AffectionKernel.usesEnhancedUb(tier) && def.ub().enhanced() != null
                ? def.ub().enhanced()
                : def.ub().base();
    }

    /** {@return the tier to resolve a slot's rows at, from the roster push; the first tier when unsynced} */
    static int tierOf(@Nullable com.astrum.pcrd.impl.network.RosterSyncPayload.Entry entry) {
        return entry == null || entry.tier() <= 0 ? GrowthFacts.FIRST_TIER : entry.tier();
    }

    /** White, like vanilla's own effect name line. */
    private static final int COLOUR_NAME = 0xFFFFFF;

    /** Grey, like vanilla's own duration line. */
    private static final int COLOUR_DIM = 0x835571;

    /** Between two effects on one line. */
    private static final String SEPARATOR = "  ";
}
