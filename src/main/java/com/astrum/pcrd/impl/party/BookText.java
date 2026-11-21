package com.astrum.pcrd.impl.party;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every translation key the 角色册 (character book) asks for, in one common class.
 *
 * <h2>Why the keys are not constants inside the screen</h2>
 * Two reasons, both practical:
 *
 * <ul>
 *   <li>{@code LangParityTest} must be able to enumerate them, and it is a plain unit test: reading a
 *       key set off a {@code Screen} subclass would initialise {@code net.minecraft.client} classes in
 *       a JVM that has no client. This class has no Minecraft imports at all, so the test can hold the
 *       set the way it already holds {@code AstrumCommand.LANG_KEYS}.</li>
 *   <li>The refusal keys are <b>derived</b> from {@link PartyRules.Reason} through
 *       {@link PartyRules#reasonKey}, exactly as the cast path derives its keys from
 *       {@code CastGate.Reason}. A new reason therefore cannot reach a player as a raw
 *       {@code astrum.party.reason.SOMETHING} string: it fails the parity test first.</li>
 * </ul>
 *
 * <p>Item-side keys live here too ({@code item.astrum.character_book}, its tooltip), because the item
 * and the screen are one feature: the tooltip is the only place a player learns the book exists.
 */
public final class BookText {

    /** The item's own name, {@code item.astrum.character_book}. */
    public static final String KEY_ITEM = "item.astrum.character_book";

    /**
     * {@return every book item name}, which is exactly one name, for {@code LangParityTest}
     *
     * <p>A method rather than {@link #KEY_ITEM} read directly by the caller, because the set is what the
     * parity test consumes and the day a second book id exists this is the one place that has to notice.
     * It used to walk the deleted {@code BookTheme} enum.
     */
    public static Set<String> itemKeys() {
        return Set.of(KEY_ITEM);
    }

    /** The item's tooltip: what a right-click does. */
    public static final String KEY_TOOLTIP = "astrum.book.tooltip";

    /** The screen's title. */
    public static final String KEY_TITLE = "astrum.book.title";

    // -------------------------------------------------------------------------------------------
    // The front page (角色一览).
    //
    // 「进入现在这个界面之前的界面，一排5个角色横着摆，角色行下面左侧两个按钮，一个是角色编队，一个是技能详情，
    //  角色行下面右侧显示养成选中角色的养成信息。」
    //
    // The book now opens on this page instead of on the roster. Nothing about the roster page changed: the
    // 「角色编队」 button is its door, and the front page is what a player sees before it. So the front page's
    // two buttons are new keys, and the page needs no title of its own — it keeps the book's.
    // -------------------------------------------------------------------------------------------

    /**
     * The front page's left button: 「角色编队」, which opens the roster page this book used to open on.
     *
     * <p>Named 角色编队 rather than 编队 because this button is a <b>door to a page</b>, not the act of placing a
     * character into a slot: the placing still happens on the page behind it, with the party column's own
     * gestures. The word he used for that act is 「编队」 and it is already the party section's heading.
     */
    public static final String KEY_BOARD_PARTY = "astrum.book.board.party";

    /**
     * The front page's right button: 「技能详情」, the existing skill-detail page for the selected character.
     *
     * <p>A second key rather than reusing {@link #KEY_SKILLS_OPEN}, even though both pages' buttons open the same
     * page: this one opens it for the character the <b>front page</b> is describing, and the two buttons can be
     * worded differently in a language that needs a different word for a door on a different page. Both files
     * must translate it.
     */
    public static final String KEY_BOARD_SKILLS = "astrum.book.board.skills";

    /** The front page's footer: what each of the five portraits and the two buttons does. */
    public static final String KEY_BOARD_HINT = "astrum.book.board.hint";

    /** The front page's growth heading, shown in place of the roster page's 「③ 养成」. */
    public static final String KEY_BOARD_GROWTH = "astrum.book.board.growth";

    /** Shown in place of the roster, party and growth views when the save cannot be read. */
    public static final String KEY_QUARANTINED = "astrum.book.quarantined";

    /** Section heading: the owned-character grid. */
    public static final String KEY_SECTION_ROSTER = "astrum.book.section.roster";

    /** Section heading: the five party slots. */
    public static final String KEY_SECTION_PARTY = "astrum.book.section.party";

    /** Section heading: the selected character's progress. */
    public static final String KEY_SECTION_GROWTH = "astrum.book.section.growth";

    /** Section heading: the selected character's effect hooks, one icon row each. */
    public static final String KEY_SECTION_SKILLS = "astrum.book.section.skills";

    /**
     * Shown in the skills section when the selected character declares no skills at all.
     *
     * <p>Distinct from "no character selected" ({@link #KEY_GROWTH_NONE}): a character with an empty hook list
     * is a pack defect worth saying out loud, and an empty section with a heading and nothing under it reads
     * as a rendering failure.
     */
    public static final String KEY_SKILLS_EMPTY = "astrum.book.skills.empty";

    // There used to be KEY_SKILLS_MORE ("astrum.book.skills.more"), the "+N" line that summarised the hooks
    // that did not fit. It is gone with the truncation it described. The strip that replaced it — a paged
    // list of hook rows — is gone too (task 40): the strip is now a single button into the skill-detail
    // page, because even paging could not make two rows readable. See KEY_SKILLS_OPEN.

    /**
     * The entry point that replaced the roster's skill list: 「技能详情」, a button the size of the old strip.
     *
     * <p>A key rather than a hard-coded word because it is a control, and the two languages must agree on
     * what it says. The whole strip it fills, and why the list could not stay there, is in
     * {@code CharacterBookScreen#drawSkillsEntry}.
     */
    public static final String KEY_SKILLS_OPEN = "astrum.book.skills.open";

    /** The skill-detail page's own heading, drawn under the book's title while that page is open. */
    public static final String KEY_SKILL_DETAIL_TITLE = "astrum.book.detail.title";

    /** The detail page's footer: how to get back, how to scroll, how to read a skill's numbers. */
    public static final String KEY_SKILL_DETAIL_HINT = "astrum.book.detail.hint";

    /** Hint shown on the numbers page, where a click goes back instead of deeper. */
    public static final String KEY_SKILL_DETAIL_NUMBERS_HINT = "astrum.book.detail.numbers_hint";

    /** Marker on a rung the character has not reached yet, so the page says "there is more" in words too. */
    public static final String KEY_SKILL_DETAIL_LOCKED = "astrum.book.detail.locked";

    /** Marker on the rung she is on — the one that actually fires. */
    public static final String KEY_SKILL_DETAIL_ACTIVE = "astrum.book.detail.active";

    // ---------------------------------------------------------------------------------------------
    // The numbers panel's labels.
    //
    // One key per PARAMETER NAME, never one per skill and never one per effect type: a panel row is
    // "label: value" where the label names the field the value came from, so a new hook reuses the labels
    // its shape already has and no sentence about a skill exists anywhere in the code or the lang files.
    // ---------------------------------------------------------------------------------------------

    /** The resolved effect tier, as a roman numeral (declared tier + level band, clamped). */
    public static final String LABEL_TIER = "astrum.book.detail.tier";

    /** How many rungs the character's level added, when it added any. */
    public static final String LABEL_BAND = "astrum.book.detail.band";

    /** A raw amount: damage, charge points, healed health. */
    public static final String LABEL_VALUE = "astrum.book.detail.value";

    /** A tiered effect's magnitude: the fraction one rung is worth, times the resolved tier. */
    public static final String LABEL_RATE = "astrum.book.detail.rate";

    /** A fraction of the damage dealt (lifesteal). */
    public static final String LABEL_RATIO = "astrum.book.detail.ratio";

    /** Reach in blocks. */
    public static final String LABEL_RADIUS = "astrum.book.detail.radius";

    /**
     * How high a knockback lifts the body, in blocks — 「击飞高度」.
     *
     * <p>Its own label rather than {@link #LABEL_RADIUS}: a kick moves a body in <b>two</b> directions and the
     * page states both, so "how far" and "how high" are two parameters of one clause. A shared label would print
     * 「范围 1」 beside 「范围 6」 on 绫音's page and read as a contradiction.
     */
    public static final String LABEL_HEIGHT = "astrum.book.detail.height";

    /**
     * Where a splash is centred — 「圆心」.
     *
     * <p>Only drawn when the centre is <b>not</b> the body that was struck, which is the default and the silent
     * one. 矛依未's SP2 is 「以自身为中心」 and a player reading her page deserves to know the blast is measured
     * from her, because it changes which enemies are reached.
     */
    public static final String LABEL_CENTRE = "astrum.book.detail.centre";

    /** The value drawn beside {@link #LABEL_CENTRE} when the blast is measured from the caster. */
    public static final String CASTER = "astrum.book.detail.caster";

    /** A duration in seconds. */
    public static final String LABEL_SECONDS = "astrum.book.detail.seconds";

    /** A duration in ticks, for the vanilla-effect hooks that take ticks. */
    public static final String LABEL_TICKS = "astrum.book.detail.ticks";

    /** A vanilla mob effect's level, shown one-based (amplifier 0 is level I). */
    public static final String LABEL_AMPLIFIER = "astrum.book.detail.amplifier";

    /** The chance a hook fires at all. */
    public static final String LABEL_CHANCE = "astrum.book.detail.chance";

    /** A multiplier: {@code 1.5} shown as {@code ×1.5}. */
    public static final String LABEL_MULTIPLIER = "astrum.book.detail.multiplier";

    /**
     * Task 55's per-skill <b>crit multiplier</b>: {@code 4.0} shown as {@code ×4}.
     *
     * <p>A label of its own rather than {@link #LABEL_MULTIPLIER}, because the two say different things and a
     * reader must not confuse them: {@code multiplier} is an attack ratio a buff multiplies by, while this is
     * 「这一招暴击时是 4 倍」 — the value a crit of <em>this skill</em> replaces the player's global
     * {@code astrum:crit_damage} with. The channel had no row at all until this task, which is why the number
     * 鏡華's 专属 spends could not be read anywhere in the book.
     */
    public static final String LABEL_CRIT_MULTIPLIER = "astrum.book.detail.crit_multiplier";

    /**
     * A character's own <b>暴击率</b>, as the percentage she declares.
     *
     * <p>Not one skill's number: it is the attribute she grants the player for as long as she holds a party slot
     * ({@code CritChances}), which is why it is drawn in the detail page's passive column.
     */
    public static final String LABEL_CRIT_CHANCE = "astrum.book.detail.crit_chance";

    /**
     * The heading of the row group that is not an effect: what the character grants simply by being enrolled.
     *
     * <p>Distinct from the labels under it so the numbers panel does not say 「暴击率」 twice — the panel draws a
     * heading line and then 「label value」 lines, so a row whose heading repeated its label would read as a
     * duplicated parameter.
     */
    public static final String LABEL_PASSIVE = "astrum.book.detail.passive";

    /**
     * A <b>crit damage</b> bonus: the {@code percent} of a {@code crit_damage_up} hook, shown as {@code 28%}.
     *
     * <p>Its own label rather than {@link #LABEL_RATE} because that one names a <em>rung's</em> fraction ("one rung is
     * worth"), while this is the whole increase a timed buff grants.
     */
    public static final String LABEL_CRIT_DAMAGE = "astrum.book.detail.crit_damage";

    /**
     * A <b>crit chance</b> bonus: the {@code percent} of a {@code crit_chance_up} hook, shown as {@code 2.5%}.
     *
     * <p>Its own label rather than {@link #LABEL_CRIT_CHANCE}, and that is not cosmetic: the two numbers sit on one
     * page and mean different things — the character's chance ({@code CritChances}, a permanent grant for holding
     * a slot) and this buff's increase (a timed grant to the party). Sharing one label made the passive column
     * report the buff instead of her own chance the moment 千歌's 专属 gained the clause, which is exactly the
     * defect a second label exists to prevent.
     */
    public static final String LABEL_CRIT_CHANCE_UP = "astrum.book.detail.crit_chance_up";

    /**
     * A <b>crit chance</b> reduction: the {@code percent} of a {@code crit_chance_down} hook, shown as {@code 18%}.
     *
     * <p>A third label rather than reusing the two above, for the reason the buff's own label exists: the sign is
     * what a reader is looking for, and "2.5%" and "−18%" on the same page are two different clauses. These two are
     * also the only crit rows that describe a state on the <b>enemy</b>.
     */
    public static final String LABEL_CRIT_CHANCE_DOWN = "astrum.book.detail.crit_chance_down";

    /** A <b>crit damage</b> reduction: the {@code percent} of a {@code crit_damage_down} hook, shown as {@code 25%}. */
    public static final String LABEL_CRIT_DAMAGE_DOWN = "astrum.book.detail.crit_damage_down";

    /** Which stack kind a hook adds. */
    public static final String LABEL_LAYERS = "astrum.book.detail.layers";

    /** A stack cap. */
    public static final String LABEL_LAYERS_MAX = "astrum.book.detail.layers_max";

    /** Damage per consumed stack. */
    public static final String LABEL_PER_LAYER = "astrum.book.detail.per_layer";

    /** The flat part of an EX point formula. */
    public static final String LABEL_BASE = "astrum.book.detail.base";

    /** The per-character-level part of an EX point formula. */
    public static final String LABEL_PER_LEVEL = "astrum.book.detail.per_level";

    /** Whether an area effect charges its caster too. */
    public static final String LABEL_INCLUDE_SELF = "astrum.book.detail.include_self";

    /** Which vanilla mob effect a hook applies. */
    public static final String LABEL_EFFECT = "astrum.book.detail.effect";

    /** Which damage type a damage hook is filed under. */
    public static final String LABEL_DAMAGE_TYPE = "astrum.book.detail.damage_type";

    /** {@value #LABEL_INCLUDE_SELF}'s true half. */
    public static final String YES = "astrum.book.detail.yes";

    /** {@value #LABEL_INCLUDE_SELF}'s false half. */
    public static final String NO = "astrum.book.detail.no";

    /**
     * The ultimate's damage, as a row label — 「伤害」.
     *
     * <p>Serves as both the column's short label and the numbers panel's heading for that row, because an
     * ultimate's own hits are not an {@code EffectType}: they are {@code hits × damage} on the tier itself,
     * so there is no effect name to borrow.
     */
    public static final String LABEL_DAMAGE = "astrum.book.detail.damage";

    /** How many hits an ultimate deals. */
    public static final String LABEL_HITS = "astrum.book.detail.hits";

    /** What one hit of an ultimate deals. */
    public static final String LABEL_PER_HIT = "astrum.book.detail.per_hit";

    /**
     * The growth rate of a damage clause that hits more than one body — 「伤害随目标数量提升」.
     *
     * <p>A label of its own rather than folding the number into the total, because it is not an amount: it is
     * how much each <em>extra</em> body adds ({@code UbSegment#perExtraTarget}), and the total depends on how
     * many bodies a cast happens to find — which the book is drawn before anybody knows.
     */
    public static final String LABEL_PER_EXTRA_TARGET = "astrum.book.detail.per_extra_target";

    /**
     * A clause this build cannot express yet — the ultimate's {@code extra} tag.
     *
     * <p>Shown rather than dropped: {@code UbTier.extra} exists so a gap stays <b>visible in the data</b>, and a
     * page that silently omitted it would hide the gap it exists to record.
     */
    public static final String LABEL_UNIMPLEMENTED = "astrum.book.detail.unimplemented";

    /**
     * The duration phrase a skill entry's tooltip and its inventory row use: 「持续 %s 秒」.
     *
     * <h2>Why it lives here rather than in the client class that reads it</h2>
     * A {@code String} key is not client code, and {@code LangParityTest} can only hold a key set that is
     * declared in a class it is free to load — the client package is not (initialising a {@code Screen}
     * subclass in a JVM with no client is how a test suite starts failing for a reason nobody can see). So the
     * constant lives with the rest of the wording and {@code SkillEntryText} imports it, which also means the
     * tooltip and the row cannot word the same phrase two ways.
     */
    public static final String KEY_SKILL_ENTRY_DURATION = "astrum.skillentry.duration";

    /**
     * {@return the one-word label an effect row wears on the detail page}
     *
     * <h2>Why a second effect name exists</h2>
     * {@link #effectKey} is the effect's full name — 「全体 HP 回复（按上限%）」 — which is right in a one-per-line
     * list and unreadable in a grid column three words wide. The detail page prints one effect per 9-pixel
     * row beside a 9-pixel icon, so it needs a name of two to four characters. That is a real second
     * vocabulary, not a duplicate of the first: derived from the enum's serialized name, so a new
     * {@code EffectType} fails {@code LangParityTest} until somebody shortens it in both languages.
     *
     * @param type the hook's effect type
     */
    public static String effectShortKey(com.astrum.pcrd.api.v1.EffectType type) {
        return "astrum.effect.short." + type.getSerializedName();
    }

    /**
     * {@return the name key of a trigger, for a hook row}
     *
     * <p>Derived from the enum's serialized name, the same string the datapack JSON uses, so a new
     * {@link com.astrum.pcrd.api.v1.Trigger} cannot be added without the parity test asking for its
     * translation.
     *
     * @param trigger the hook's trigger
     */
    public static String triggerKey(com.astrum.pcrd.api.v1.Trigger trigger) {
        return "astrum.trigger." + trigger.getSerializedName();
    }

    /**
     * {@return the name key of an effect type, for a hook row}
     *
     * @param type the hook's effect type
     */
    public static String effectKey(com.astrum.pcrd.api.v1.EffectType type) {
        return "astrum.effect." + type.getSerializedName();
    }

    /**
     * {@return the name key of one target rule — 「最近的敌人」, 「半径内的全部敌人」}
     *
     * <p>Derived from the enum's serialized name, exactly as {@link #effectKey} is, so a new
     * {@link com.astrum.pcrd.api.v1.UbTargetRule} cannot be added without both language files gaining its
     * translation: the parity test walks the enum. The name is a real second vocabulary rather than a
     * duplicate of the code's identifier — "second_nearest" is not a sentence a player reads.
     *
     * @param rule the rule a damage clause selects its bodies by
     */
    public static String targetRuleKey(com.astrum.pcrd.api.v1.UbTargetRule rule) {
        return "astrum.ub.target." + rule.getSerializedName();
    }

    /**
     * {@return the one-word label a damage clause's row wears on the detail page}
     *
     * <p>The same split {@link #effectShortKey} documents: 「半径内的全部敌人」 is right as a panel heading and
     * too long for the few pixels beside a 9-pixel icon, so the row's short label is 「全范围」 or 「最近」.
     *
     * @param rule the rule a damage clause selects its bodies by
     */
    public static String targetRuleShortKey(com.astrum.pcrd.api.v1.UbTargetRule rule) {
        return "astrum.ub.target.short." + rule.getSerializedName();
    }

    /** Shown when the roster is readable but empty. */
    public static final String KEY_ROSTER_EMPTY = "astrum.book.roster.empty";

    /** One line telling the player how to move characters between the grid and the slots. */
    public static final String KEY_PARTY_HINT = "astrum.book.party.hint";

    /** Shown in the growth view when no character is selected. */
    public static final String KEY_GROWTH_NONE = "astrum.book.growth.none";

    /** Growth view line: the level. */
    public static final String KEY_GROWTH_LEVEL = "astrum.book.growth.level";

    /** Growth view line: the experience bar's numbers. */
    public static final String KEY_GROWTH_EXP = "astrum.book.growth.exp";

    /** Growth view line: affection and the tier it currently is. */
    public static final String KEY_GROWTH_AFFECTION = "astrum.book.growth.affection";

    /** Growth view line: the threshold of the next affection tier. */
    public static final String KEY_GROWTH_NEXT = "astrum.book.growth.next";

    /** Growth view line: already at the last affection tier. */
    public static final String KEY_GROWTH_MAX = "astrum.book.growth.max";

    /** Growth view entry point when a dish handler is installed. */
    public static final String KEY_FEED_AVAILABLE = "astrum.book.feed.available";

    /** Growth view entry point when no dish handler is installed yet: the route, as text. */
    public static final String KEY_FEED_ROUTE = "astrum.book.feed.route";

    /** A grid cell whose character the loaded pack no longer defines. */
    public static final String KEY_UNKNOWN_CHARACTER = "astrum.book.unknown_character";

    /** The last refused edit, shown at the bottom of the screen. */
    public static final String KEY_REFUSED = "astrum.book.refused";

    /**
     * Every key this feature asks for, for {@code LangParityTest}.
     *
     * <p>The five {@code astrum.party.reason.*} keys are added by walking the enum, so the set cannot
     * fall out of step with the reasons the server can actually return.
     */
    public static final Set<String> LANG_KEYS = keys();

    private BookText() {
    }

    private static Set<String> keys() {
        // A List, not Set.of: a duplicated constant would make Set.of throw inside a static
        // initialiser, i.e. take the whole screen down at class-load time. A duplicate key is a bug,
        // but it must not be a crash in a player's game.
        Set<String> keys = new LinkedHashSet<>(List.of(KEY_ITEM, KEY_TOOLTIP, KEY_TITLE, KEY_QUARANTINED,
                // Task 51: the front page a player lands on — its two doors and its two lines of guidance.
                KEY_BOARD_PARTY, KEY_BOARD_SKILLS, KEY_BOARD_HINT, KEY_BOARD_GROWTH,
                KEY_SECTION_ROSTER, KEY_SECTION_PARTY, KEY_SECTION_GROWTH, KEY_ROSTER_EMPTY, KEY_PARTY_HINT, KEY_GROWTH_NONE, KEY_GROWTH_LEVEL,
                KEY_GROWTH_EXP, KEY_GROWTH_AFFECTION, KEY_GROWTH_NEXT, KEY_GROWTH_MAX,
                KEY_FEED_AVAILABLE, KEY_FEED_ROUTE, KEY_UNKNOWN_CHARACTER, KEY_REFUSED,
                KEY_SECTION_SKILLS, KEY_SKILLS_EMPTY,
                // Task 40: the skill-detail page. The button, its heading, its footer, the two rung markers
                // and the numbers panel's per-parameter labels.
                KEY_SKILLS_OPEN, KEY_SKILL_DETAIL_TITLE, KEY_SKILL_DETAIL_HINT, KEY_SKILL_DETAIL_LOCKED,
                KEY_SKILL_DETAIL_ACTIVE,
                LABEL_TIER, LABEL_BAND, LABEL_VALUE, LABEL_RATE, LABEL_RATIO, LABEL_RADIUS, LABEL_SECONDS,
                LABEL_TICKS, LABEL_AMPLIFIER, LABEL_CHANCE, LABEL_MULTIPLIER, LABEL_LAYERS,
                LABEL_HEIGHT,
                LABEL_CENTRE,
                CASTER,
                // Task 58: the three crit numbers the book did not show — the per-skill multiplier, the
                // character's own chance, and the timed crit-damage buff's percentage — plus the heading that keeps
                // the chance row from repeating its own label.
                LABEL_CRIT_MULTIPLIER, LABEL_CRIT_CHANCE, LABEL_PASSIVE, LABEL_CRIT_DAMAGE,
                LABEL_CRIT_CHANCE_UP,
                // The two victim-side crit debuffs' own labels: a reduction is not a bonus, and one label for both
                // signs would print 「−18%」 under a heading that says 「提升」.
                LABEL_CRIT_CHANCE_DOWN, LABEL_CRIT_DAMAGE_DOWN,
                LABEL_LAYERS_MAX, LABEL_PER_LAYER, LABEL_BASE, LABEL_PER_LEVEL, LABEL_INCLUDE_SELF,
                LABEL_EFFECT, LABEL_DAMAGE_TYPE, YES, NO,
                // The ultimate's own rows: its hits, its per-hit damage, and the clause this build cannot
                // express yet (kept visible rather than dropped).
                LABEL_DAMAGE, LABEL_HITS, LABEL_PER_HIT, LABEL_UNIMPLEMENTED, LABEL_PER_EXTRA_TARGET,
                // The skill entry's one duration phrase (「持续 %s 秒」), shared by its tooltip and its row.
                KEY_SKILL_ENTRY_DURATION));
        keys.addAll(itemKeys());
        for (PartyRules.Reason reason : PartyRules.Reason.values()) {
            keys.add(PartyRules.reasonKey(reason));
        }
        // The skills list names every trigger and every effect type. Walking the enums (rather than
        // listing strings) is what makes a new enum constant fail the parity test instead of shipping
        // a row whose label is a raw "astrum.effect.something" key.
        for (com.astrum.pcrd.api.v1.Trigger trigger : com.astrum.pcrd.api.v1.Trigger.values()) {
            keys.add(triggerKey(trigger));
        }
        // The detail page names a damage clause by the rule it selects its bodies by (「最近的敌人」 /
        // 「半径内的全部敌人」). Walking the enum is what makes a new rule fail the parity test until both files
        // translate it, instead of shipping a row whose label is a raw "astrum.ub.target.something".
        for (com.astrum.pcrd.api.v1.UbTargetRule rule : com.astrum.pcrd.api.v1.UbTargetRule.values()) {
            keys.add(targetRuleKey(rule));
            keys.add(targetRuleShortKey(rule));
        }
        for (com.astrum.pcrd.api.v1.EffectType type : com.astrum.pcrd.api.v1.EffectType.values()) {
            keys.add(effectKey(type));
            // The detail page's one-word label for the same effect. Added from the same walk, so a new
            // effect type cannot ship with a grid row reading "astrum.effect.short.something".
            keys.add(effectShortKey(type));
        }
        return Set.copyOf(keys);
    }
}

