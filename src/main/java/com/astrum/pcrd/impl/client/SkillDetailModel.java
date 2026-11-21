package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.EffectTiers;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.FlatAmount;
import com.astrum.pcrd.api.v1.GrantDuration;
import com.astrum.pcrd.api.v1.SkillLadder;
import com.astrum.pcrd.api.v1.TieredParams;
import com.astrum.pcrd.api.v1.UbDef;
import com.astrum.pcrd.api.v1.UbEffect;
import com.astrum.pcrd.api.v1.UbSegment;
import com.astrum.pcrd.api.v1.UbTier;
import com.astrum.pcrd.impl.effect.CritChances;
import com.astrum.pcrd.impl.effect.TieredAmounts;
import com.astrum.pcrd.impl.growth.AffectionKernel;
import com.astrum.pcrd.impl.growth.GrowthFacts;
import com.astrum.pcrd.impl.party.BookText;
import com.astrum.pcrd.impl.party.SkillRows;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>What the 技能详情 page draws, as data</b> — one entry per skill version, one effect per hook, and the
 * numbers of each effect.
 *
 * <h2>What the human asked for</h2>
 * 「技能详情页」: a page of columns, each column one skill with its effect rows, the rung she is on shown as
 * active, the rungs above it greyed so she can see there is more, and a click that reveals the numbers. The
 * numbers must be <b>read from the data</b>: 「不要写死技能名或效果文案」.
 *
 * <h2>Where every fact comes from — no rule is written twice here</h2>
 * <ul>
 *   <li><b>Which hooks each rung fires, and what the skill is called</b> — {@link SkillRows#of}, i.e.
 *       exactly the join the roster's list used (and, through {@link AffectionKernel#hooksAt}, the one the
 *       damage pipeline runs). A page that resolved its own ladders could show 广域斩击+ while she casts
 *       黄金耀光.</li>
 *   <li><b>Whether a rung exists at all</b> — {@link AffectionKernel#ladderFor} plus the declared
 *       {@code tier2}/{@code tier3}: a skill with no {@code +} version has one column, not three identical
 *       ones.</li>
 *   <li><b>Whether a rung is unlocked</b> — the same tier ladder the rest of the screen uses:
 *       {@link GrowthFacts#MAX_TIER} is the top, {@code TierAvatar.tierOf} answers which rung she is on (the
 *       server's own answer, from the roster push), and a rung is <b>locked iff it is above hers</b>. Nothing
 *       here compares affection thresholds with tiers; that comparison is {@code AffectionKernel}'s.</li>
 *   <li><b>A tiered effect's resolved tier</b> — {@link TieredAmounts#resolve}, the one place the level band
 *       is added and clamped by the effect's own ladder ({@link EffectTiers}). EX passes a band of {@code 0},
 *       because the human ruled 「EX gets no level bonus」 and {@code ExRungs} resolves without one.</li>
 *   <li><b>The numbers</b> — the hook's own {@code effectParams}, field by field, named by the
 *       <em>parameter</em> (see {@link Number}). No sentence about any skill exists in this class, and none is
 *       needed: 佩可莉姆's 午餐时间 and 姬塔's 广域斩击 are drawn by the same code because they are the same
 *       record shapes.</li>
 * </ul>
 *
 * <h2>Two builders, one page — and the ultimate is the first column</h2>
 * A small skill's column is built from the {@link EffectHook}s its rung resolves to; the <b>ultimate</b>'s is
 * built from its {@link UbTier}, which is a different record with its own numbers — one row per damage clause
 * ({@link UbSegment}: an amount, a hit count and a {@link com.astrum.pcrd.api.v1.UbTargetRule}) plus a list of
 * {@link UbEffect}s, a team charge grant, and any clause this build cannot express. A tier that declares no
 * clauses draws its one implicit clause, which is the {@code hits × damage} row this page has always drawn. Both builders produce the
 * <b>same</b> value — a {@link Row} with a picture, a short label, a heading, a tier and its numbers — so the
 * page has one row shape, one painter, one numbers panel and one layout, and {@code UbEffect} rows and hook
 * rows cannot drift apart.
 *
 * <p>The ultimate is <b>first</b>, before the small skills: it is the character's headline skill — the one the
 * cast pad and the wheel are built around — and its tier is the thing a player wants to check first. It also
 * keeps the page's own reading order ("what she casts, then what she swings"), and it is why the column wears
 * the gold ink the read-me-first line uses.
 *
 * <p>The ultimate is one column: the rung she is on ({@code AffectionKernel.usesEnhancedUb} picks base or 6★),
 * not one column per rung. The two UB rungs are not a ladder a player climbs mid-skill the way 广域斩击+ is:
 * they are the same ultimate cast stronger, and its name comes from the shared
 * {@code AffectionKernel.skillNamesAt} so the page cannot name it differently from the cast pad.
 *
 * @param entries the ultimate first, then one per skill version in the rotation's own order, each skill's
 *                rungs adjacent
 */
@ApiStatus.Internal
public record SkillDetailModel(List<SkillDetailModel.Entry> entries) {

    public SkillDetailModel {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** No character selected, or a definition with no skills: the page draws its empty line. */
    public static final SkillDetailModel EMPTY = new SkillDetailModel(List.of());

    /**
     * One row of the numbers panel: a parameter's label key and its value, already formatted.
     *
     * <p>The label is a <b>translation key</b>, not a sentence: {@code astrum.book.detail.radius} beside
     * {@code 8} says 「半径 8」 in Chinese and "Radius 8" in English, and a new hook with a radius field needs no
     * new sentence — which is the property the human's 「不要写死文案」 asks for. The value is a plain string
     * (numbers, ids, roman numerals), because the panel draws it with the current font and nothing in it is
     * language-dependent.
     *
     * @param labelKey the key of the parameter's name
     * @param value    the formatted value
     */
    public record Number(String labelKey, String value) {
    }

    /**
     * <b>One row of the page</b> — the one shape both builders produce.
     *
     * <h2>Why a hook row and an ultimate row are the same record</h2>
     * A hook row is one {@link EffectHook}: a picture (its effect type), a short label, the resolved tier. An
     * ultimate row is one clause of a {@link UbTier}: its own hits, one of its {@link UbEffect}s, the team charge
     * grant, or the unfired {@code extra} tag. They differ in <em>where the numbers come from</em>, not in what
     * a row <b>is</b> — so they are one record, and the page draws them with one method, folds them into the
     * numbers panel with one method, and measures them with {@link SkillDetailLayout} without knowing which is
     * which.
     *
     * @param type       the effect type that picks the row's official icon, or {@code null} for a row with no
     *                   picture (the unfired-clause note)
     * @param labelKey   the translation key of the row's <b>short</b> label — what fits beside a 9-pixel icon in
     *                   a column
     * @param headingKey the translation key of the numbers panel's heading for this row: the effect's full name
     *                   for a hook row, the row's own label where there is no effect name to borrow
     * @param tier       the resolved tier — declared plus level band, clamped to {@link EffectTiers}' maximum —
     *                   or {@code 0} for a row that has no tiers at all
     * @param numbers    the numbers panel's parameters for this row, in display order
     */
    public record Row(@Nullable EffectType type, String labelKey, String headingKey, int tier,
                      List<Number> numbers) {

        public Row {
            labelKey = labelKey == null ? "" : labelKey;
            headingKey = headingKey == null || headingKey.isBlank() ? labelKey : headingKey;
            numbers = numbers == null ? List.of() : List.copyOf(numbers);
        }

        /** {@return whether this row has a tier to show} */
        public boolean tiered() {
            return tier > 0;
        }

        /** {@return the resolved tier as the roman numeral the row shows, or {@code ""} when not tiered} */
        public String tierText() {
            return tier > 0 ? roman(tier) : "";
        }

        /** {@return the official icon to blit for this row, or empty for a row with no picture} */
        public Optional<EffectType> icon() {
            return Optional.ofNullable(type);
        }
    }

    /**
     * One line of the numbers panel.
     *
     * <p>Three kinds, because the panel is three-coloured: the column's name, the name of each effect, and the
     * parameter rows under it. The kind is data here rather than a decision inside the drawing method, so
     * "which line is a heading" is testable and the renderer has nothing to work out.
     *
     * @param kind  what this line is
     * @param key   a translation key: the effect's full name for {@link Kind#EFFECT}, the parameter's label for
     *              {@link Kind#NUMBER}; empty for {@link Kind#NAME}
     * @param value a literal: the column's name for {@link Kind#NAME}, the formatted number for
     *              {@link Kind#NUMBER}; empty for {@link Kind#EFFECT}
     */
    public record Line(Kind kind, String key, String value) {

        public Line {
            key = key == null ? "" : key;
            value = value == null ? "" : value;
        }

        /** What a line is, which is also how it is coloured. */
        public enum Kind {
            /** The column's own name, drawn as the panel's title line. */
            NAME,
            /** An effect's full name, drawn as the heading of its group of numbers. */
            EFFECT,
            /** One parameter: its label and its value. */
            NUMBER
        }

        /**
         * {@return the text this line draws, with translation keys resolved through {@code translate}}
         *
         * <p>The one place a {@link Line} becomes a string. Both the renderer and the wrap measurer go through it,
         * which is what makes "the width I wrapped at" and "the text I drew" the same text — measuring a raw key
         * while drawing its translation is how a wrap silently overflows.
         *
         * <p>A label and its value are separated by <b>two</b> spaces, the gutter the page already drew.
         *
         * <p>A continuation line (see {@link Entry#wrappedPanel}) has an empty {@link #key()} and carries its whole
         * text in {@link #value()}, so it answers its value unchanged: it is not a second parameter and must not
         * grow a second label.
         *
         * @param translate resolves a translation key; {@code null} leaves keys unresolved (fine for a test)
         */
        public String displayText(@Nullable java.util.function.UnaryOperator<String> translate) {
            return switch (kind) {
                case NAME -> value;
                case EFFECT -> resolve(key, translate);
                case NUMBER -> {
                    if (key.isEmpty()) {
                        yield value;
                    }
                    String label = resolve(key, translate);
                    yield value.isEmpty() ? label : label + "  " + value;
                }
            };
        }

        /** {@return {@code key} translated, or as-is when there is no resolver} */
        private static String resolve(String key, @Nullable java.util.function.UnaryOperator<String> translate) {
            if (key.isEmpty()) {
                return "";
            }
            return translate == null ? key : translate.apply(key);
        }
    }

    /**
     * One column of the page: a skill version, or the ultimate.
     *
     * @param skill    the skill tag, matching {@code TriggerParams.skill} — {@link SkillLadder#EX_SKILL} for the
     *                 EX rung, {@code AffectionKernel.ubKey()} for the ultimate
     * @param name     the name this version has at her tier, from {@link SkillLadder#nameAt} for a small skill and
     *                 from {@code AffectionKernel.skillNamesAt} for the ultimate — so the {@code +}, the 6★ rename
     *                 and the UB's two names are all decided where they already were
     * @param rung     which affection rung this version belongs to, {@code 1}–{@code GrowthFacts.MAX_TIER}
     * @param ultimate whether this column is her ultimate rather than a small skill
     * @param current  whether this is the version that <b>fires right now</b>: the highest rung of the ladder
     *                 that she has reached
     * @param locked   whether that rung is above the one the character is on, i.e. whether the column is drawn
     *                 greyed as "not yet"
     * @param rows     the column's rows, in firing order — effect rows, or an ultimate's damage/effect/charge
     *                 rows
     * @param icon     the <b>skill's</b> one picture, empty when the pack ships none
     *                 <p>One picture per <em>skill</em>, which is the human's rule (「一个技能可以引用多个通用效果的
     *                 代码，但是图标只使用一个」), and this column is one skill version — so every rung of a ladder
     *                 wears the same picture. It is a <b>texture</b>, unlike {@link Row#icon()}, which names the
     *                 effect type a row used to be pictured by: the per-effect pictures are gone deliberately,
     *                 and an effect has no picture any more. Empty is ordinary and means "fall back to her face"
     *                 — see {@code SkillArt}.
     */
    public record Entry(String skill, String name, int rung, boolean ultimate, boolean current,
                        boolean locked, List<Row> rows, Optional<ResourceLocation> icon) {

        public Entry {
            skill = skill == null ? "" : skill;
            name = name == null ? "" : name;
            rows = rows == null ? List.of() : List.copyOf(rows);
            icon = icon == null ? Optional.empty() : icon;
        }

        /** The seven-argument form, for a column whose skill the pack gives no picture for. */
        public Entry(String skill, String name, int rung, boolean ultimate, boolean current, boolean locked,
                List<Row> rows) {
            this(skill, name, rung, ultimate, current, locked, rows, Optional.empty());
        }

        /** {@return whether this version is the EX skill's} */
        public boolean ex() {
            return SkillLadder.EX_SKILL.equals(skill);
        }

        /**
         * {@return the key of the marker this column wears}, or {@code ""} for a version she has outgrown
         *
         * <h2>The three states, and why they are three</h2>
         * A skill on a ladder has more than two versions once she has climbed past one: 姬塔's 技能1 is
         * 广域斩击 (outgrown), 广域斩击+ (outgrown at 6★) and 黄金耀光 (current). Painting only "current" and
         * "locked" would call an outgrown version current, which is the one thing the page must not say — she
         * is not casting it. So:
         * <ul>
         *   <li>{@link #current()} — this is what fires; the column is bright and wears 「当前」;</li>
         *   <li>{@link #locked()} — above her; the column is grey and wears 「未解锁」, which is the spec's 「让玩家
         *       知道后面还有」;</li>
         *   <li>neither — a version she has climbed past: drawn in the ordinary body ink, with no marker at
         *       all. It is history, and history does not need a badge.</li>
         * </ul>
         * Both the fact ({@link #locked()}, {@link #current()}) and its word come from here, so the drawing
         * method decides pixels only and this question is testable without a client.
         */
        public String markerKey() {
            if (locked) {
                return BookText.KEY_SKILL_DETAIL_LOCKED;
            }
            return current ? BookText.KEY_SKILL_DETAIL_ACTIVE : "";
        }

        /**
         * {@return the numbers panel's lines for this column: its name, then each row and its parameters}
         *
         * <p>Flattened here rather than in the renderer so that "the panel for this column says exactly these
         * things" is an assertion, and so that the line count ({@link #numberLineCount()}) and the lines
         * themselves cannot disagree — the panel's height is computed from the count and filled from the list.
         * A row's heading is its {@link Row#headingKey()}, which is how an ultimate's damage row and a hook's
         * effect row end up in the same panel without a second formatter.
         *
         * <p>No wrapping: this is the logical list, one line per thing said. The <b>displayed</b> list is
         * {@link #wrappedPanel}, which may be longer because a wide line continues onto the next one.
         */
        public List<Line> numberPanel() {
            List<Line> lines = new ArrayList<>(numberLineCount());
            lines.add(new Line(Line.Kind.NAME, "", name));
            for (Row row : rows) {
                lines.add(new Line(Line.Kind.EFFECT, row.headingKey(), ""));
                for (Number number : row.numbers()) {
                    lines.add(new Line(Line.Kind.NUMBER, number.labelKey(), number.value()));
                }
            }
            return List.copyOf(lines);
        }

        /** {@return how many lines the numbers panel needs for this column: its name, then each row} */
        public int numberLineCount() {
            int lines = 1;
            for (Row row : rows) {
                lines += 1 + row.numbers().size();
            }
            return lines;
        }

        /**
         * {@return the panel's <b>displayed</b> lines at a popup {@code width}, with wide lines wrapped}
         *
         * <h2>Why the width is a parameter and not a field on the model</h2>
         * The model is built from the character and her tier; the popup's width comes from the <em>window</em>
         * ({@code SkillDetailLayout.visibleLines} over {@code layout.skillDetailBody()}). Baking a width into the
         * model would mean rebuilding it on a resize and would make every "what does the page say" assertion depend
         * on a geometry constant. So the model stays geometry-free and the renderer asks this method with the width
         * it is about to draw into — and the paging maths elsewhere asks the same method with the same width, which
         * is what makes the page count grow instead of the text being cut.
         *
         * <h2>Where the measurement comes from</h2>
         * {@code widthOf} is {@code Minecraft.font::width} in production,
         * {@code Component::getString → font.width} at the call site, and {@code String::length} in the tests. Every
         * decision is therefore pure and testable, and this class still has no client types.
         *
         * @param width   the usable text width in GUI pixels
         * @param widthOf measures a string in the same unit
         * @param translate resolves a translation key, so the measured text is the drawn text; {@code null}
         *                  measures the keys themselves, which is what a test wants
         */
        public List<Line> wrappedPanel(int width, java.util.function.ToIntFunction<String> widthOf,
                @Nullable java.util.function.UnaryOperator<String> translate) {
            if (widthOf == null) {
                return numberPanel();
            }
            List<Line> wrapped = new ArrayList<>(numberLineCount());
            for (Line line : numberPanel()) {
                List<String> pieces = TextWrap.wrap(line.displayText(translate), width, widthOf);
                if (pieces.size() <= 1) {
                    wrapped.add(line);
                    continue;
                }
                // The first piece keeps the line's identity, so its colour and kind are the original's; the
                // continuations are the same kind with no key, which is what makes them un-labelled.
                wrapped.add(new Line(line.kind(), line.key(), pieces.get(0)));
                for (int index = 1; index < pieces.size(); index++) {
                    wrapped.add(new Line(line.kind(), "", pieces.get(index)));
                }
            }
            return List.copyOf(wrapped);
        }

        /** {@return {@link #wrappedPanel} measuring and drawing the untranslated text} */
        public List<Line> wrappedPanel(int width, java.util.function.ToIntFunction<String> widthOf) {
            return wrappedPanel(width, widthOf, null);
        }

        /**
         * {@return how many <b>displayed</b> lines the panel needs at {@code width} — the paging total}
         *
         * <p>This is the number the 「3/7」 counter divides by. It used to be {@link #numberLineCount()}, the
         * logical count, which is why a wrapped line could not add a page: the counter would have said the page
         * was full while the text was still being cut.
         */
        public int wrappedLineCount(int width, java.util.function.ToIntFunction<String> widthOf,
                @Nullable java.util.function.UnaryOperator<String> translate) {
            return wrappedPanel(width, widthOf, translate).size();
        }
    }

    /**
     * {@return the page's entries for {@code def} at affection {@code tier} and character {@code level}}
     *
     * <p>Never throws and never answers {@code null}: a {@code null} definition is {@link #EMPTY}, because this
     * is built from a render path where "nothing selected" is an ordinary state.
     *
     * @param def            the character, or {@code null}
     * @param affectionTier  the tier she is on, as the roster push reported it; clamped into
     *                       {@code [1, MAX_TIER]}
     * @param level          her level, which the level bands and the EX point formulas are evaluated at
     */
    public static SkillDetailModel of(@Nullable CharacterDef def, int affectionTier, int level) {
        if (def == null) {
            return EMPTY;
        }
        int tier = Math.max(GrowthFacts.FIRST_TIER, Math.min(GrowthFacts.MAX_TIER, affectionTier));
        int band = GrowthFacts.bandBonus(level);
        // One SkillRows per rung, built once: every entry of every skill reads from these, so a column's
        // hooks are the rung's resolved hooks rather than a re-grouping of the base list.
        SkillRows[] perRung = new SkillRows[GrowthFacts.MAX_TIER + 1];
        for (int rung = GrowthFacts.FIRST_TIER; rung <= GrowthFacts.MAX_TIER; rung++) {
            perRung[rung] = SkillRows.of(def, rung);
        }
        List<Entry> entries = new ArrayList<>();
        // The ultimate first: it is the headline skill, and the thing a player opens this page to check.
        // One column, built from the UbTier she is on — see the class javadoc for why not one per rung.
        Entry ultimate = ubColumn(def.ub(), AffectionKernel.skillNamesAt(def, tier)
                .get(AffectionKernel.ubKey()), tier, level);
        if (ultimate != null) {
            entries.add(ultimate);
        }
        // Skill-major, not rung-major: a skill's versions sit side by side, which is what makes "this is
        // what you have, that is what the next rung buys" readable at a glance.
        for (SkillRows.Row base : perRung[GrowthFacts.FIRST_TIER].rows()) {
            if (base.ultimate()) {
                continue;   // the ultimate's own column was built from its UbTier above
            }
            // First pass: which of this skill's declared rungs the page should show at all.
            List<Integer> versions = new ArrayList<>(GrowthFacts.MAX_TIER);
            Map<Integer, SkillRows.Row> byRung = new LinkedHashMap<>(GrowthFacts.MAX_TIER);
            String previousName = null;
            List<EffectHook> previousHooks = List.of();
            for (int rung = GrowthFacts.FIRST_TIER; rung <= GrowthFacts.MAX_TIER; rung++) {
                if (!declaresRung(def, base.skill(), rung)) {
                    continue;
                }
                SkillRows.Row row = rowOf(perRung[rung], base.skill());
                if (row == null) {
                    continue;
                }
                if (previousName != null && previousName.equals(row.name())
                        && previousHooks.equals(row.hooks())) {
                    // A declared rung that changes neither the name nor the body is not another column: two
                    // identical columns would read as a layout bug rather than as "there is more".
                    continue;
                }
                previousName = row.name();
                previousHooks = row.hooks();
                versions.add(rung);
                byRung.put(rung, row);
            }
            // Second pass: which one of them fires. The highest rung she has reached — NOT "the one equal to
            // her tier", because a skill with no upgrade at her tier keeps firing the version below it.
            int active = versions.isEmpty() ? GrowthFacts.FIRST_TIER : versions.get(0);
            for (int rung : versions) {
                if (rung <= tier) {
                    active = rung;
                }
            }
            for (int rung : versions) {
                SkillRows.Row row = byRung.get(rung);
                boolean ex = base.skill().equals(SkillLadder.EX_SKILL);
                int entryBand = ex ? 0 : band;
                List<Row> entryRows = new ArrayList<>(rowsOf(row.hooks(), entryBand, level));
                if (ex) {
                    // Her own 暴击率 is not one skill's number: it is a passive of the character, granted while she
                    // holds a party slot (CritChances). The EX column is the page's passive column — the one that
                    // lists what she grants just by being enrolled — so it is drawn there rather than invented as
                    // a column of its own. A pack that declares none gets no row.
                    Row crit = critChanceRow(def);
                    if (crit != null) {
                        entryRows.add(crit);
                    }
                }
                entries.add(new Entry(base.skill(), row.name(), rung, false, rung == active, rung > tier,
                        entryRows, row.icon()));
            }
        }
        return new SkillDetailModel(entries);
    }

    /**
     * {@return the ultimate's column, or {@code null} when she has none to show}
     *
     * <h2>One column, and why exactly one</h2>
     * The rung she is on is the rung she casts: {@code AffectionKernel.usesEnhancedUb(tier)} — the same call
     * {@code AffectionKernel.skillNamesAt} and the cast path use — picks {@code ub.enhanced()} at 6★ and
     * {@code ub.base()} below it. Named through {@code skillNamesAt} for the same reason: the page must not be
     * able to call her ultimate something the cast pad does not.
     *
     * <p>The two UB rungs are <b>not</b> shown as two columns the way 广域斩击 / 广域斩击+ / 黄金耀光 are: those
     * are three different <em>skills</em> a player buys in sequence, while the two UB rungs are one ultimate cast
     * stronger, and the column that shows the 6★ numbers to a tier-1 player would be a column of numbers she will
     * never see from this page anyway. One column, always current.
     *
     * <p>A pack that names no ultimate ({@code name} empty) gets no column: the page cannot title it, and an
     * untitled column is worse than an absent one. That matches what {@code SkillRows} has always done with the
     * ultimate row.
     *
     * @param ub    the character's two ultimate tiers, or {@code null}
     * @param name  her ultimate's name at the tier being drawn, from
     *              {@code AffectionKernel.skillNamesAt}; {@code null} when the pack names none
     * @param tier  the affection tier she is on
     * @param level her level, for the level band its effects land on
     */
    static @Nullable Entry ubColumn(@Nullable UbDef ub, @Nullable String name, int tier, int level) {
        if (ub == null || name == null || name.isBlank()) {
            return null;
        }
        UbTier chosen = AffectionKernel.usesEnhancedUb(tier) && ub.enhanced() != null
                ? ub.enhanced()
                : ub.base();
        if (chosen == null) {
            return null;
        }
        // A UB's UbEffects are settled with the caster's level band (UbDamageSettlement#bandOf), unlike EX:
        // so the band is passed in, and the column shows the tier the cast will actually use.
        return new Entry(AffectionKernel.ubKey(), name, tier, true, true, false,
                ubRows(chosen, GrowthFacts.bandBonus(level)), ub.icon());
    }

    /**
     * {@return the rows of one ultimate tier: one per damage clause, its effects, its team charge, and any
     * clause this build cannot express}
     *
     * <h2>One row per clause, and why the clause's rule is the row's label</h2>
     * An ultimate's damage is not one number any more: 日和莉 strikes the nearest body for 10.0 <b>and</b>
     * everything inside four blocks for 4.5, and 美美's 6★ blast is centred on another body. So the damage is
     * drawn <b>once per {@link UbSegment}</b>, and the row's label is the clause's
     * {@link com.astrum.pcrd.api.v1.UbTargetRule} — 「最近的敌人」, 「半径内的全部敌人」 — because "which bodies" is
     * the thing a player cannot otherwise tell from two numbers. The numbers under it are the clause's own: its
     * total, its hit count, its per-hit figure, its reach, its damage type and its crit multiplier, exactly the
     * fields {@code hits × damage} carried before clauses existed.
     *
     * <p>A tier that declares <b>no</b> clauses is drawn from its one implicit clause
     * ({@code UbTier.damageSegments()}) with 「伤害」 as the label — unchanged from before this page knew about
     * clauses, which is what keeps the book identical for the 22 shipped characters that never write one.
     *
     * <p>A clause's own effects are rows too, and so are an effect clause's: 「将最远的敌人拉近 300」 selects a
     * body and pulls it, and a page that drew only the damage would show a move that is not there. They go
     * through the same {@link #effectRow} the tier's own effects use, so a tier-level panic and a clause-level
     * pull cannot be worded or number-read two ways.
     *
     * <p>The {@code extra} tag becomes a row rather than being dropped: it exists so an unimplemented clause stays
     * visible in the data, and a page that hid it would be the one surface pretending the gap is closed.
     *
     * @param tier the ultimate tier (base or enhanced)
     * @param band how many rungs her level adds to the tier's effects
     */
    private static List<Row> ubRows(UbTier tier, int band) {
        List<Row> rows = new ArrayList<>();
        // Whether the pack spelled its damage as clauses at all. It decides one thing only: the label and
        // heading of each damage row — a listed clause is named by its rule, the shorthand by 「伤害」.
        boolean listed = !tier.segments().isEmpty();
        for (UbSegment clause : tier.damageSegments()) {
            if (clause == null) {
                continue;
            }
            rows.add(damageRow(tier, clause, listed));
            for (UbEffect effect : clause.effects()) {
                if (effect != null) {
                    rows.add(effectRow(effect, band));
                }
            }
        }
        for (UbEffect effect : tier.effects()) {
            if (effect != null) {
                rows.add(effectRow(effect, band));
            }
        }
        tier.teamChargeBonus().ifPresent(bonus -> rows.add(new Row(EffectType.AREA_CHARGE,
                BookText.effectShortKey(EffectType.AREA_CHARGE),
                BookText.effectKey(EffectType.AREA_CHARGE), 0,
                List.of(new Number(BookText.LABEL_VALUE, number(bonus))))));
        tier.extra().filter(tag -> !tag.isBlank()).ifPresent(tag -> rows.add(new Row(null,
                BookText.LABEL_UNIMPLEMENTED, BookText.LABEL_UNIMPLEMENTED, 0,
                List.of(new Number(BookText.LABEL_UNIMPLEMENTED, tag)))));
        return List.copyOf(rows);
    }

    /**
     * {@return the row of one damage clause: its rule as the label, and its own numbers under it}
     *
     * <p>EXTRA_DAMAGE's picture is the crossed blades — the damage family's own icon, which is what this row is.
     * The total comes first because that is the number a player compares; the hit count and the per-hit figure
     * follow it, because four hits of 10 reads differently from one hit of 40.
     *
     * @param tier   the tier the clause belongs to — its damage type and crit multiplier are the fallbacks
     * @param clause the clause as the pack wrote it
     * @param listed whether the tier spelled its damage as clauses (see {@link #ubRows})
     */
    private static Row damageRow(UbTier tier, UbSegment clause, boolean listed) {
        List<Number> damage = new ArrayList<>();
        // The shorthand always shows the three numbers, even at damage 0 (千歌's heal-only ultimate still has a
        // 「伤害 0」 row a reader can see); a listed clause shows them only when it deals damage at all, because
        // an effect clause (紬希's pull) has no amount to print and a 「0」 beside 「最远的敌人」 would read as a
        // clause that was meant to hit something.
        if (clause.dealsDamage() || !listed) {
            double perHit = clause.damage().orElse(0.0);
            damage.add(new Number(BookText.LABEL_VALUE, number(clause.hits() * perHit)));
            damage.add(new Number(BookText.LABEL_HITS, number(clause.hits())));
            damage.add(new Number(BookText.LABEL_PER_HIT, number(perHit)));
        }
        // 「伤害随目标数量提升」: the one number that is not an amount of damage but a rate of growth, so it gets
        // its own labelled row rather than being folded into the total (which depends on how many bodies the
        // cast found, and the book is drawn before anybody casts).
        if (clause.perExtraTarget() > 0.0) {
            damage.add(new Number(BookText.LABEL_PER_EXTRA_TARGET, number(clause.perExtraTarget())));
        }
        clause.radius().ifPresent(radius ->
                damage.add(new Number(BookText.LABEL_RADIUS, number(radius))));
        // The clause's own type, else the tier's: the settlement files a hit under exactly this pair, so the page
        // cannot describe a type the damage is not filed under.
        Optional<ResourceLocation> type = clause.damageType().isPresent()
                ? clause.damageType() : tier.damageType();
        type.ifPresent(id -> damage.add(new Number(BookText.LABEL_DAMAGE_TYPE, id.toString())));
        // The ultimate's own crit multiplier (task 55's per-skill channel) was a number in the data with no row:
        // 鏡華's 6★ 「本技能暴击时 4 倍」 could not be read anywhere in the book. It belongs on the damage row
        // because it modifies exactly that number — and a clause may carry its own, so it is read off the clause
        // first, the same order the settlement multiplies in.
        OptionalDouble crit = clause.critMultiplier().isPresent()
                ? clause.critMultiplier() : tier.critMultiplier();
        if (crit.isPresent()) {
            damage.add(new Number(BookText.LABEL_CRIT_MULTIPLIER, "\u00d7" + number(crit.getAsDouble())));
        }
        String label = listed && clause.target() != null
                ? BookText.targetRuleShortKey(clause.target()) : BookText.LABEL_DAMAGE;
        String heading = listed && clause.target() != null
                ? BookText.targetRuleKey(clause.target()) : BookText.LABEL_DAMAGE;
        return new Row(EffectType.EXTRA_DAMAGE, label, heading, 0, damage);
    }

    /**
     * {@return one {@link UbEffect} as a row: its picture, its short label, its full name and its numbers}
     *
     * <p>The tier's own effects and a clause's effects are the same record, so they are the same row — one
     * builder, so 「恐慌」 on the tier and 「拉近」 on a clause cannot drift apart in wording, tier resolution or
     * label choice.
     *
     * @param effect the effect as the pack wrote it
     * @param band   how many rungs her level adds to it
     */
    private static Row effectRow(UbEffect effect, int band) {
        List<Number> numbers = new ArrayList<>();
        // A tier row only for a type that HAS a ladder: the crit-damage buff's magnitude is the wiki's own
        // percentage, not a rung, so a 「I」 beside it would be a tier a pack never wrote.
        boolean tiered = EffectTiers.isTiered(effect.type());
        if (tiered) {
            tierPanel(numbers, effect.type(), effect.params(), band);
        }
        tieredRows(numbers, effect.type(), effect.params(), band);
        effect.damageType().ifPresent(type ->
                numbers.add(new Number(BookText.LABEL_DAMAGE_TYPE, type.toString())));
        // The non-tiered magnitudes. 「暴击伤害 +28%」 and 「暴击率 +2.5%」 are two different stats with two
        // different labels, so the label is chosen by the TYPE rather than written once for "a percent"; a
        // shared label would print the chance as crit damage on the same page that shows the real crit
        // damage two rows up.
        effect.percent().ifPresent(value -> numbers.add(new Number(
                effect.type() == EffectType.CRIT_CHANCE_UP
                        ? BookText.LABEL_CRIT_CHANCE_UP : BookText.LABEL_CRIT_DAMAGE,
                percent(value / 100.0))));
        // 「每秒恢复【10+「冰龙之印」】技能值」: the formula's two numbers, and no rung — a tier row beside a rate
        // the pack states exactly would be the same invented number the percent row above avoids.
        effect.chargeRate().ifPresent(rate -> {
            numbers.add(new Number(BookText.LABEL_BASE, number(rate.base())));
            numbers.add(new Number(BookText.LABEL_PER_LAYER, number(rate.perMark())));
        });
        // 「名指しの効果」: an `ally_effect` names a mob effect by id and its level by `amplifier`, neither of
        // which is a rung — so both are shown, or the row would be a duration with no subject. 胡桃's
        // over-time heal and 矛依未's 标记 125 are the two shipped clauses this draws.
        effect.vanillaEffect().ifPresent(id -> {
            numbers.add(new Number(BookText.LABEL_EFFECT, id.toString()));
            numbers.add(new Number(BookText.LABEL_AMPLIFIER, roman(effect.amplifier() + 1)));
        });
        // 「击飞高度」: the vertical half of a knockback, which the rung cannot carry. Shown because the launcher's
        // page would otherwise describe the kick and silently omit the height — the same reason every other
        // non-rung magnitude (a percent, a charge rate) has a row of its own.
        effect.launch().ifPresent(height -> numbers.add(new Number(BookText.LABEL_HEIGHT, number(height))));
        // A probability is part of the clause, not a footnote: 铃莓's two defence-down branches are 33% and
        // 67% of the same rung, and a page that printed both with no chance would read as two debuffs.
        if (effect.chance() < 1.0) {
            numbers.add(new Number(BookText.LABEL_CHANCE, percent(effect.chance())));
        }
        return new Row(effect.type(), BookText.effectShortKey(effect.type()),
                BookText.effectKey(effect.type()),
                tiered ? TieredAmounts.tierOf(effect.type(), effect.params(), band) : 0, numbers);
    }

    /** {@return how many entries (columns) the page has} */
    public int size() {
        return entries.size();
    }

    /** {@return whether there is nothing to draw} */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * {@return the columns as the geometry sees them — a title and a row count each, in this model's order}
     *
     * <h2>Why the layout is handed this and not the entries</h2>
     * {@link SkillDetailLayout} measures columns; it does not need to know whether a row came from an
     * {@code EffectHook} or from a {@code UbTier}. So the model — which does know — hands it the one thing it
     * needs, in the same order as {@link #entries()}, and the page therefore has <b>one</b> layout, one wrap rule
     * and one hit test however many kinds of row it grows.
     */
    public List<SkillDetailLayout.Column> columns() {
        List<SkillDetailLayout.Column> columns = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            columns.add(new SkillDetailLayout.Column(entry.name(), entry.rows().size()));
        }
        return List.copyOf(columns);
    }

    /** {@return the entry at {@code index}, or {@code null} past the end} */
    public @Nullable Entry entry(int index) {
        return index < 0 || index >= entries.size() ? null : entries.get(index);
    }

    /**
     * {@return whether affection {@code rung} of {@code skill} is a version the page should show}
     *
     * <p>Rung 1 is the base version and always exists. A higher rung exists only when the ladder <b>declares</b>
     * it: a skill with no {@code +} version is the same skill at every tier, and {@code SkillLadder#upgradeAt}
     * would happily hand back the tier-2 rung at tier 3 — which is right for the rotation and would be three
     * identical columns here.
     *
     * @param def   the character
     * @param skill the skill tag
     * @param rung  the affection rung, {@code 1}–{@code MAX_TIER}
     */
    private static boolean declaresRung(CharacterDef def, String skill, int rung) {
        if (rung <= GrowthFacts.FIRST_TIER) {
            return true;
        }
        return AffectionKernel.ladderFor(def, skill).map(ladder -> rung == 2
                ? ladder.tier2().isPresent()
                : ladder.tier3().isPresent()).orElse(false);
    }

    /** {@return the row of {@code skill} in {@code rows}, or {@code null} when that rung does not have it} */
    private static @Nullable SkillRows.Row rowOf(SkillRows rows, String skill) {
        for (SkillRows.Row row : rows.rows()) {
            if (row.skill().equals(skill)) {
                return row;
            }
        }
        return null;
    }

    /**
     * {@return the hook rows of one skill version}
     *
     * <p>The hook half of the page's one row shape: a picture from the effect type, the type's short label, the
     * type's full name as the panel's heading, and the resolved tier. {@link #ubRows} produces the same record
     * from a {@code UbTier} — which is what keeps one painter, one panel and one layout.
     *
     * @param hooks one skill version's hooks, in firing order
     * @param band  how many rungs her level adds; {@code 0} for EX
     * @param level her level, for the EX point formulas
     */
    private static List<Row> rowsOf(List<EffectHook> hooks, int band, int level) {
        List<Row> rows = new ArrayList<>(hooks.size());
        for (EffectHook hook : hooks) {
            if (hook == null) {
                continue;
            }
            int tier = hook.tieredParams()
                    .map(params -> TieredAmounts.tierOf(hook.type(), params, band))
                    .orElse(0);
            rows.add(new Row(hook.type(), BookText.effectShortKey(hook.type()),
                    BookText.effectKey(hook.type()), tier, numbersOf(hook, band, level)));
        }
        return List.copyOf(rows);
    }

    // ---------------------------------------------------------------------------------------------
    // The numbers panel: one row per parameter, dispatched on the EFFECT TYPE.
    //
    // A `switch` with one arm per record of the sealed interface, on purpose: adding an effect to the API
    // then fails compilation here until its numbers are described, instead of shipping a panel that silently
    // shows nothing for it. The rows are built from `effectParams` — the amount, the radius, the seconds,
    // the tier — never from a sentence about a skill.
    // ---------------------------------------------------------------------------------------------

    private static List<Number> numbersOf(EffectHook hook, int band, int level) {
        List<Number> rows = new ArrayList<>();
        hook.tieredParams().ifPresent(params -> tierPanel(rows, hook.type(), params, band));
        switch (hook) {
            case EffectHook.ApplyEffect h -> {
                rows.add(new Number(BookText.LABEL_EFFECT, h.effect().toString()));
                rows.add(new Number(BookText.LABEL_TICKS, number(h.duration())));
                // Amplifiers are zero-based (0 = level I), the same shift HasteRules documents; shown
                // one-based so the panel matches the roman numerals the effect rows already use.
                rows.add(new Number(BookText.LABEL_AMPLIFIER, roman(h.amplifier() + 1)));
            }
            case EffectHook.ExtraDamage h -> {
                rows.add(new Number(BookText.LABEL_VALUE, number(h.amount())));
                damageType(rows, h.damageType());
            }
            case EffectHook.Lifesteal h -> rows.add(new Number(BookText.LABEL_RATIO, percent(h.fraction())));
            case EffectHook.GainCharge h -> rows.add(new Number(BookText.LABEL_VALUE, number(h.amount())));
            case EffectHook.AreaCharge h -> {
                rows.add(new Number(BookText.LABEL_VALUE, number(h.amount())));
                rows.add(new Number(BookText.LABEL_RADIUS, number(h.radius())));
                rows.add(new Number(BookText.LABEL_INCLUDE_SELF, h.includeSelf() ? BookText.YES : BookText.NO));
            }
            case EffectHook.Splash h -> {
                rows.add(new Number(BookText.LABEL_VALUE, number(h.damage())));
                rows.add(new Number(BookText.LABEL_RADIUS, number(h.radius())));
                // Only when it is NOT the struck body: the default needs no row, and printing it everywhere
                // would add a line to every splash in the game to say nothing.
                if (h.casterCentred()) {
                    rows.add(new Number(BookText.LABEL_CENTRE, BookText.CASTER));
                }
                damageType(rows, h.damageType());
            }
            case EffectHook.Stack h -> {
                rows.add(new Number(BookText.LABEL_LAYERS, h.stackId().toString()));
                rows.add(new Number(BookText.LABEL_LAYERS_MAX, number(h.max())));
            }
            case EffectHook.Detonate h -> {
                rows.add(new Number(BookText.LABEL_PER_LAYER, number(h.damagePerStack())));
                rows.add(new Number(BookText.LABEL_RADIUS, number(h.radius())));
            }
            case EffectHook.AttackBuff h -> {
                rows.add(new Number(BookText.LABEL_MULTIPLIER, "×" + number(h.multiplier())));
                rows.add(new Number(BookText.LABEL_SECONDS, number(h.seconds())));
                h.radius().ifPresent(radius ->
                        rows.add(new Number(BookText.LABEL_RADIUS, number(radius))));
            }
            // 「提升我方全体 28% 暴击伤害，持续 18 秒」: the percentage is the hook's own, not a rung, so the panel
            // shows it beside the clock and the reach rather than a resolved tier.
            case EffectHook.CritDamageUp h -> {
                rows.add(new Number(BookText.LABEL_CRIT_DAMAGE, percent(h.percent() / 100.0)));
                rows.add(new Number(BookText.LABEL_SECONDS, number(h.seconds())));
                h.radius().ifPresent(radius ->
                        rows.add(new Number(BookText.LABEL_RADIUS, number(radius))));
            }
            case EffectHook.Heal h -> {
                rows.add(new Number(BookText.LABEL_VALUE, number(h.amount())));
                h.radius().ifPresent(radius ->
                        rows.add(new Number(BookText.LABEL_RADIUS, number(radius))));
            }
            case EffectHook.AllyEffect h -> {
                rows.add(new Number(BookText.LABEL_EFFECT, h.effect().toString()));
                rows.add(new Number(BookText.LABEL_TICKS, number(h.duration())));
                rows.add(new Number(BookText.LABEL_AMPLIFIER, roman(h.amplifier() + 1)));
                h.radius().ifPresent(radius ->
                        rows.add(new Number(BookText.LABEL_RADIUS, number(radius))));
            }
            case EffectHook.FlatAttackUp h -> flat(rows, h.amount(), h.duration(), level);
            case EffectHook.FlatMagicAttackUp h -> flat(rows, h.amount(), h.duration(), level);
            case EffectHook.PercentDamage h -> {
                tieredRows(rows, h.type(), h.params(), band);
                damageType(rows, h.damageType());
            }
            // The thirteen effects whose whole parameter block is the shared TieredParams. One arm each
            // rather than a grouped pattern label, so the dispatch stays a plain switch that a reader can
            // check line by line against the sealed interface's permits list.
            case EffectHook.DefenceDown h -> tieredRows(rows, h.type(), h.params(), band);
            case EffectHook.HealArea h -> tieredRows(rows, h.type(), h.params(), band);
            case EffectHook.HealSelf h -> tieredRows(rows, h.type(), h.params(), band);
            case EffectHook.BuffAttackArea h -> tieredRows(rows, h.type(), h.params(), band);
            case EffectHook.BuffPhysDefenceArea h -> tieredRows(rows, h.type(), h.params(), band);
            case EffectHook.BuffMagicDefenceArea h -> tieredRows(rows, h.type(), h.params(), band);
            case EffectHook.BuffMagicAttackArea h -> tieredRows(rows, h.type(), h.params(), band);
            case EffectHook.RemoveShield h -> tieredRows(rows, h.type(), h.params(), band);
            case EffectHook.Haste h -> tieredRows(rows, h.type(), h.params(), band);
            case EffectHook.Panic h -> tieredRows(rows, h.type(), h.params(), band);
            case EffectHook.ChargeRateUp h -> tieredRows(rows, h.type(), h.params(), band);
            case EffectHook.Shield h -> tieredRows(rows, h.type(), h.params(), band);
            // The two typed channels show the same rows as the shared barrier: the rung (as a percentage of max
            // health), the clock and the reach. The channel itself is the effect's name, so no extra row.
            case EffectHook.ShieldPhys h -> tieredRows(rows, h.type(), h.params(), band);
            case EffectHook.ShieldMagic h -> tieredRows(rows, h.type(), h.params(), band);
            // 「提升范围内所有友方 N% 暴击率」: the converted percentage (千歌 2.5%), the clock and the reach —
            // the crit-damage arm one stat over, and the number a reader compares with her own chance on the
            // EX column (which CritChances grants).
            case EffectHook.CritChanceUp h -> {
                // LABEL_CRIT_CHANCE_UP, not LABEL_CRIT_CHANCE: the character's own chance is a row of its own in
                // the passive column, and one label for both numbers put the buff where her chance belongs.
                rows.add(new Number(BookText.LABEL_CRIT_CHANCE_UP, percent(h.percent() / 100.0)));
                rows.add(new Number(BookText.LABEL_SECONDS, number(h.seconds())));
                h.radius().ifPresent(radius ->
                        rows.add(new Number(BookText.LABEL_RADIUS, number(radius))));
            }
            // 「每秒恢复自身【10+「冰龙之印」】技能值」: the two numbers of the formula, then the clock. Labelled
            // base / per layer because that is the pair the tally adds up — the same labels COUNTER uses.
            // The two victim-side crit debuffs: the percentage and the clock, the same two rows the buffs show
            // one stat over — with a label that says 「降低」 rather than 「提升」, because the sign is the clause.
            case EffectHook.CritChanceDown h -> {
                rows.add(new Number(BookText.LABEL_CRIT_CHANCE_DOWN, percent(h.percent() / 100.0)));
                rows.add(new Number(BookText.LABEL_SECONDS, number(h.seconds())));
            }
            case EffectHook.CritDamageDown h -> {
                rows.add(new Number(BookText.LABEL_CRIT_DAMAGE_DOWN, percent(h.percent() / 100.0)));
                rows.add(new Number(BookText.LABEL_SECONDS, number(h.seconds())));
            }
            case EffectHook.CounterChargeRegen h -> {
                rows.add(new Number(BookText.LABEL_BASE, number(h.base())));
                rows.add(new Number(BookText.LABEL_PER_LAYER, number(h.perMark())));
                rows.add(new Number(BookText.LABEL_SECONDS, number(h.seconds())));
            }
            case EffectHook.MagicDefenceDown h -> tieredRows(rows, h.type(), h.params(), band);
            // The six task-49 effects are all tiered and carry the same TieredParams shape, so they go through the
            // one row builder like every other rung — no per-effect panel code, and a new tiered effect reaches
            // the book automatically.
            case EffectHook.TransferCharge h -> tieredRows(rows, h.type(), h.params(), band);
            case EffectHook.TransferHeal h -> tieredRows(rows, h.type(), h.params(), band);
            case EffectHook.RegenSelf h -> tieredRows(rows, h.type(), h.params(), band);
            case EffectHook.RegenArea h -> tieredRows(rows, h.type(), h.params(), band);
            case EffectHook.ChargeRegenSelf h -> tieredRows(rows, h.type(), h.params(), band);
            case EffectHook.ChargeRegenArea h -> tieredRows(rows, h.type(), h.params(), band);
            // task 56: the three movement-control hooks carry nothing but TieredParams, so they use the same
            // row builder as every other rung. The unit of each rung (blocks of travel, a vanilla strength)
            // is named by the effect's own label, so no new lang key is invented for it.
            case EffectHook.Pull h -> tieredRows(rows, h.type(), h.params(), band);
            case EffectHook.Knockback h -> tieredRows(rows, h.type(), h.params(), band);
            case EffectHook.Taunt h -> tieredRows(rows, h.type(), h.params(), band);
            // agent-32's two immunity hooks: tiered like the movement family, because their magnitude is a
            // rung on a ladder rather than a free number — the panel shows the same rows a tiered effect does.
            case EffectHook.PhysImmunity h -> tieredRows(rows, h.type(), h.params(), band);
            case EffectHook.ControlImmunity h -> tieredRows(rows, h.type(), h.params(), band);
            // 召唤精灵: the rung IS the spirit count, so the panel shows the resolved rung, the clock, and the
            // number of spirits it buys — a reader must not have to know that tier 3 means three.
            case EffectHook.SummonSpirit h -> {
                tieredRows(rows, h.type(), h.params(), band);
                int resolved = TieredAmounts.tierOf(h.type(), h.params(), band);
                rows.add(new Number(BookText.LABEL_LAYERS,
                        Integer.toString(com.astrum.pcrd.impl.effect.Spirits.countForTier(resolved))));
            }
            // 计数 is not tiered (its magnitude is a formula over the marks already held), so it shows the
            // grant, the stated cap, and the ladder rung that cap lands on — which is the cap actually applied.
            case EffectHook.Counter h -> {
                rows.add(new Number(BookText.LABEL_BASE, number(h.base())));
                rows.add(new Number(BookText.LABEL_PER_LAYER, number(h.perMark())));
                int rung = com.astrum.pcrd.impl.effect.Counters.tierForStatedCap(h.cap());
                rows.add(new Number(BookText.LABEL_TIER, roman(rung)));
                rows.add(new Number(BookText.LABEL_LAYERS_MAX,
                        Integer.toString(com.astrum.pcrd.impl.effect.Counters.capForTier(rung))));
                rows.add(new Number(BookText.LABEL_SECONDS, number(h.seconds())));
            }
            case EffectHook.ConsumeCounter h -> {
                rows.add(new Number(BookText.LABEL_LAYERS, Integer.toString(h.remove())));
                rows.add(new Number(BookText.LABEL_PER_LAYER, number(h.damagePerMark())));
                damageType(rows, h.damageType());
            }
        }
        // The per-skill crit multiplier (task 55's channel) is a property of the HOOK, not of an effect type, so it
        // is appended here rather than repeated in every damaging arm: two amounts of one skill crit at this value,
        // replacing the caster's global astrum:crit_damage, and a hook that declares none shows nothing (the
        // ordinary case — the global applies).
        hook.critMultiplier().ifPresent(value ->
                rows.add(new Number(BookText.LABEL_CRIT_MULTIPLIER, "\u00d7" + number(value))));
        if (hook.chance() < 1.0) {
            rows.add(new Number(BookText.LABEL_CHANCE, percent(hook.chance())));
        }
        return List.copyOf(rows);
    }

    /**
     * {@return the row that shows this character's own <b>暴击率</b>, or {@code null} when she declares none}
     *
     * <h2>Why this is not a hook row</h2>
     * A hook row is one {@link EffectHook}; a character's crit chance is not an effect at all — it is a property of
     * the character, applied as an attribute modifier on the player for as long as she holds a party slot
     * ({@code CritChances}). So there is no {@link EffectType} to borrow a picture or a name from, and the row is
     * built here from the one read of the data ({@code CritChances.chanceOf}), which is the same read the applier
     * uses — the book cannot state a chance the game does not grant.
     *
     * <h2>Where it is drawn, and why there</h2>
     * It is appended to the <b>EX column</b>: that column is the page's passive column, the one that lists what she
     * grants simply by being enrolled, and the crit chance has exactly that lifetime. The row's heading is
     * 「角色特性」 rather than the label again, so the panel reads 「角色特性 / 暴击率 5%」 instead of saying 暴击率 twice.
     * A pack that declares no chance gets no row — an honest absence rather than a 0% line on a character who was
     * never meant to crit.
     *
     * @param def the character, never {@code null} at this call site
     */
    private static Row critChanceRow(CharacterDef def) {
        double chance = CritChances.chanceOf(def);
        if (!(chance > 0.0)) {
            return null;
        }
        return new Row(null, BookText.LABEL_CRIT_CHANCE, BookText.LABEL_PASSIVE, 0,
                List.of(new Number(BookText.LABEL_CRIT_CHANCE, percent(chance))));
    }

    /**
     * {@return the two rows a tiered effect leads with: the <b>resolved</b> tier, and how many rungs her level
     * actually added}
     *
     * <p>Written once and used by both builders — the hook side ({@link #rowsOf}) and the ultimate's clauses
     * ({@link #ubRows}) — because "what tier does this land on" must not be answered twice.
     *
     * <p>The band row carries the <b>contribution</b>, not the raw bonus: a level-95 character on a one-rung
     * effect ({@code panic}) adds three rungs and the ladder refuses two of them, so the row would read 「等级加成
     * +3」 beside a tier that did not move. It shows the difference the ladder actually let through, and shows
     * nothing at all when it let none through.
     *
     * @param rows   the panel's rows, appended to
     * @param type   the effect whose ladder applies
     * @param params the declared tier block
     * @param band   how many rungs her level adds; {@code 0} for EX
     */
    private static void tierPanel(List<Number> rows, EffectType type, TieredParams params, int band) {
        TieredParams tiered = params == null ? TieredParams.FIRST : params;
        int resolved = TieredAmounts.tierOf(type, tiered, band);
        rows.add(new Number(BookText.LABEL_TIER, roman(resolved)));
        if (resolved > tiered.tier()) {
            rows.add(new Number(BookText.LABEL_BAND, "+" + (resolved - tiered.tier())));
        }
    }

    /**
     * {@return the rows a tiered effect's rung is worth: the magnitude, then the clock and the reach}
     *
     * @param rows   the panel's rows, appended to
     * @param type   the effect whose ladder applies
     * @param params the tier block, read the same way the executors read it
     * @param band   how many rungs the character's level adds
     */
    private static void tieredRows(List<Number> rows, EffectType type, TieredParams params, int band) {
        TieredParams tiered = params == null ? TieredParams.FIRST : params;
        if (magnitudeIsAFraction(type)) {
            rows.add(new Number(BookText.LABEL_RATE,
                    percent(TieredAmounts.resolve(type, tiered, band).fraction())));
        }
        tiered.seconds().ifPresent(seconds ->
                rows.add(new Number(BookText.LABEL_SECONDS, number(seconds))));
        tiered.radius().ifPresent(radius ->
                rows.add(new Number(BookText.LABEL_RADIUS, number(radius))));
    }

    /**
     * {@return whether an effect's rung is worth a fraction that a percentage can describe}
     *
     * <p>Three tiered effects are not, and {@link EffectTiers}' own javadoc is where that is written down:
     * {@code haste}'s magnitude is a vanilla <em>amplifier</em> (tier 1–3 is amplifier 0–2, so "300%" would be
     * nonsense), and {@code remove_shield} and {@code panic} are one-rung all-or-nothing effects whose
     * {@code stepPerTier} of {@code 1.0} is a placeholder rather than a magnitude. Everything else is a
     * fraction of something the effect names — health, attack, incoming damage — and is shown as a
     * percentage.
     *
     * @param type the effect type
     */
    private static boolean magnitudeIsAFraction(EffectType type) {
        return switch (type) {
            // SUMMON_SPIRIT's rung is a COUNT of spirits and COUNTER's is a count of marks; "300%" of a
            // count is not a number a reader can use. The four over-time types are the same shape: their
            // magnitude IS the vanilla amplifier (regeneration I..V, charge-regen 4..20 points a second), so
            // "300%" would be a wrong unit for a rung.
            case HASTE, REMOVE_SHIELD, PANIC, SUMMON_SPIRIT, COUNTER,
                 REGEN_SELF, REGEN_AREA, CHARGE_REGEN_SELF, CHARGE_REGEN_AREA,
                 // task 56: a rung is a distance in blocks (PULL), a vanilla knockback strength (KNOCKBACK)
                 // and a count of rungs whose length lives in `seconds` (TAUNT). "300%" of any of the three
                 // would be a wrong unit for the number.
                 PULL, KNOCKBACK, TAUNT -> false;
            default -> EffectTiers.ruleOf(type) != null;
        };
    }

    /** The two flat EX effects: a point formula plus its lifetime. */
    private static void flat(List<Number> rows, FlatAmount amount, GrantDuration duration, int level) {
        rows.add(new Number(BookText.LABEL_BASE, number(amount.base())));
        rows.add(new Number(BookText.LABEL_PER_LEVEL, number(amount.perLevel())));
        // Resolved at the character's level through FlatAmount's own method, so the panel shows the points the
        // passive actually grants rather than an arithmetic of its own.
        rows.add(new Number(BookText.LABEL_VALUE, number(amount.amountAt(level))));
        duration.seconds().ifPresent(seconds ->
                rows.add(new Number(BookText.LABEL_SECONDS, number(seconds))));
    }

    private static void damageType(List<Number> rows, Optional<net.minecraft.resources.ResourceLocation> type) {
        type.ifPresent(id -> rows.add(new Number(BookText.LABEL_DAMAGE_TYPE, id.toString())));
    }

    /**
     * {@return {@code tier} as the roman numeral an effect row shows}
     *
     * <p>Roman because a ladder is a rung count, not a quantity: 「攻击提升 III」 reads as "third rung" where
     * "3" beside a damage number is one digit away from being read as damage. Above {@code V} — which no
     * shipped ladder reaches, since the longest is five rungs — the number is used, which is a worse label and
     * still an honest one.
     *
     * @param tier a 1-based tier
     */
    static String roman(int tier) {
        return switch (tier) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> Integer.toString(tier);
        };
    }

    /**
     * {@return a number the way a player reads it: no trailing {@code .0}, at most one decimal}
     *
     * <p>Every value in the panel comes from a {@code double} in a datapack, and {@code 9.4} printing as
     * {@code 9.4000000000000004} is the classic result of not rounding. One decimal is what the derived numbers
     * in {@code docs/balance-derivation.md} carry.
     */
    static String number(double value) {
        if (!Double.isFinite(value)) {
            return "—";
        }
        double rounded = Math.round(value * 10.0) / 10.0;
        if (rounded == Math.rint(rounded) && Math.abs(rounded) < 1.0e15) {
            return Long.toString((long) rounded);
        }
        return String.format(Locale.ROOT, "%.1f", rounded);
    }

    /** {@return {@code fraction} as a percentage, rounded like {@link #number} } */
    static String percent(double fraction) {
        return number(fraction * 100.0) + "%";
    }
}
