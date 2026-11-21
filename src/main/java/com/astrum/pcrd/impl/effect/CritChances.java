package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.impl.attach.Attachments;
import com.astrum.pcrd.impl.attach.Party;
import com.astrum.pcrd.impl.attach.Roster;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>「按角色数据配置」: a character's own 暴击率 as one attribute modifier on the player, granted while she
 * holds a party slot and removed when she leaves.</b>
 *
 * <h2>What the human chose, and what it replaced</h2>
 * Before this class there was exactly one answer to "does anybody crit": {@code Crit.DEFAULT_CHANCE = 0.0}, i.e.
 * <b>nobody</b>, until an effect or a foreign mod granted a chance. The human's ruling was
 * 「按角色数据配置」 — each character declares her own chance in her JSON — and then, when the numbers turned
 * out to be mostly absent from the source material, 「根据不同角色给不同的基础暴击率，有暴击需求的比没暴击需求的角色，基础暴击率高一些
 * 」. So the shipped data has two bands plus the four characters whose material names a crit <em>score</em>.
 *
 * <h2>The band table — the one place the rule lives</h2>
 * <pre>
 *   band   chance   equivalent score   who
 *   HIGH   0.05     100 points          her kit explicitly mentions crit (a crit multiplier, a crit grant,
 *                                       or a crit-damage buff) and the material gives no score for her
 *   LOW    0.02      40 points          her kit has no crit clause at all
 * </pre>
 *
 * <p><b>Why two bands and not a third axis.</b> A physical-attacker / supporter split was considered and
 * rejected: the one rule the human stated is about <em>needing</em> crit, and a third band would let a
 * non-crit swordswoman out-crit a crit-support healer, i.e. contradict the rule he did state. The band a
 * character is in is recorded in her own {@code _notes} beside the value, so the assignment is auditable.
 *
 * <p><b>Why a named score wins over the band.</b> Four shipped characters' material names a crit <em>number</em>
 * (璃乃 45/75, 千歌 50, 静流 100, 铃莓 200), and the human's second ruling was 「千歌那个是因为原作给的暴击率是数值不是百分比，你看看
 * 给多少数值合适」 — decide what the score is worth, do not round it to a band. So a character with a score gets
 * the <b>conversion</b> of that score, and the bands are what the other eighteen get. See
 * {@link #CHANCE_PER_POINT} for the conversion and {@code task-58-report.md} for the arithmetic.
 *
 * <h2>Why an attribute modifier, and why one id per character</h2>
 * {@code astrum:crit_chance} is a real {@link net.minecraft.world.entity.ai.attributes.RangedAttribute} with a
 * default of {@code 0.0} (see {@link Crit}), and {@code Crit.chanceOf} reads the attribute's <em>value</em>, i.e.
 * base plus every modifier. So a grant is one {@code ADD_VALUE} modifier and nothing else: no second crit path,
 * no per-hit bookkeeping, and "she left the party" is {@code removeModifier} — the same construction, and the
 * same per-character modifier id, as {@code FlatAttack}'s EX grants. Two characters in one party therefore
 * <b>add</b>, which is {@code Crit}'s own rule for every source of "you crit more often"
 * ({@code Crit.chanceOf} sums, then clamps at {@code MAX_CHANCE}); a max instead would be a second copy of a
 * rule that already exists.
 *
 * <h2>The lifecycle: driven by {@code ExPassives.apply}, not a watcher</h2>
 * This build has exactly one membership-change point plus two restore points (login, respawn), and
 * {@code ExPassives.apply} is already called from all three. A crit grant has the same lifetime as an EX passive
 * — "while she holds the slot" — so it is reconciled there rather than by a second listener. That keeps the
 * "no tick listener" constraint and the one-owner rule: there is no path on which a character can be enrolled
 * without her chance arriving, or leave without it going.
 *
 * <p><b>Never throws.</b> Reached from a party edit and from login; a body with no attribute map (an armour
 * stand, a client-side copy, a careful test) is skipped rather than allowed to cost the party edit.
 */
@ApiStatus.Internal
public final class CritChances {

    /**
     * The <b>high</b> band: her kit mentions crit, and the material gives no score for her.
     *
     * <p>{@code 0.25} = her own <b>100</b> points (the score 静流's material names, i.e. a crit character without
     * a score is worth the one crit score in the middle of the shipped set) <b>plus</b> the
     * {@link #GEAR_POINTS gear share} of 400 — 500 points total. The gear share is the point: this build ships
     * no equipment, so the number has to stand in for what she would be wearing.
     */
    public static final double HIGH_CHANCE = 0.25;

    /**
     * The <b>low</b> band: her kit has no crit clause, and the material gives no score for her.
     *
     * <p>{@code 0.22} = her own <b>40</b> points (a design floor: 80% of the smallest crit score the material
     * names, 千歌's 50) <b>plus</b> the {@link #GEAR_POINTS gear share} of 400 — 440 points total. Every
     * crit-wanted character stays <b>strictly</b> above every other one, which is the human's rule, and the gap
     * survives the share because both bands receive it. There is no wiki number behind the 40; the report says
     * so.
     */
    public static final double LOW_CHANCE = 0.22;

    /**
     * The conversion from a PCR crit <b>score</b> to a fraction: <b>one point is 0.05 percentage points</b>,
     * i.e. {@code chance = score × 0.0005}.
     *
     * <h2>Where the number comes from, and whose choice it is</h2>
     * PCR's クリティカル is a raw stat, not a rate, and the community wiki gives the provisional relation
     * 「クリティカル率 = クリティカル × 0.05 × (キャラレベル / 相手レベル)」 (percent) — see
     * {@code task-58-report.md} for the citation. The level-ratio term is <b>dropped</b>: this mod's crit is a
     * per-character rate with no per-target level scaling, and reproducing the ratio would require a stat this
     * build does not carry. What is left is the linear part, which is exactly the score-to-rate reading the human
     * asked for: 50 → 2.5%, 100 → 5%, 200 → 10%. <b>The choice is this build's</b>, and it is stated rather than
     * implied — the source gives a score and no rate.
     */
    public static final double CHANCE_PER_POINT = 0.0005;

    /**
     * The <b>gear share</b> in crit points: <b>400</b>, which the human authorised as 「可以」.
     *
     * <h2>Why a gear share exists at all</h2>
     * PCR characters reach their real crit rate mostly through equipment and their unique weapon — a bare level-208
     * character carries a few hundred points and a built one carries a thousand. This mod ships <b>no equipment</b>
     * (「原作还有装备要素，mod没有制作相关内容」), so reading only the bare score would make crit a rare curiosity here
     * while it is an ordinary 20-60% stat in the original.
     *
     * <p>So every character gets this many points on top of whatever the material names for her, as the stand-in
     * for the gear she would be wearing. That is the <b>same convention the damage numbers already use</b>:
     * {@code docs/balance-derivation.md} derives every damage figure at 参考等级 208 / 参考攻击力 2000, and 2000 is an
     * equipped attack value, not a naked one. Deriving damage from equipped stats and crit from naked ones would
     * put two different assumptions behind one stat sheet.
     *
     * <p>400 points is 20 percentage points at {@link #CHANCE_PER_POINT}, which lands a non-crit character at 22%
     * and a crit character at 25% — the 20-25% band the original sits in at the reference level, and inside
     * {@link Crit#MAX_CHANCE} with room for a foreign attribute to add to it.
     */
    public static final double GEAR_POINTS = 400.0;

    /**
     * The modifier id's namespace-prefix, before the character path is appended.
     *
     * <p>One id per (character, stat), exactly as {@code FlatAttack}'s ids are: vanilla keys modifiers by id, so
     * a single shared id would make the second girl in the party silently cancel the first one's grant. Spelling
     * the prefix once also keeps {@code CritChanceTest}'s id assertions and this class from disagreeing.
     */
    public static final String PREFIX = "crit_chance/";

    private CritChances() {
    }

    /**
     * {@return the fraction a PCR crit score is worth at this build's conversion, clamped into the attribute's
     * range}
     *
     * <p>Never throws: a {@code NaN}, an infinity or a negative score is "no grant" rather than an exception,
     * because this is read off a datapack.
     *
     * @param points the score the material names, e.g. 千歌's {@code 50}
     */
    public static double chanceForScore(double points) {
        if (!Double.isFinite(points) || points <= 0.0) {
            return 0.0;
        }
        // Points first, then the gear share: the share is what every character wears, so a fictional score of 0
        // still answers "nothing of her own", and the caller adds the share where it applies. Kept separate so
        // a test can assert the bare conversion without the 400 leaking into it.
        return Math.max(0.0, Math.min(Crit.MAX_CHANCE, points * CHANCE_PER_POINT));
    }

    /**
     * {@return the chance a character carries in play: her own points plus the gear share}
     *
     * <p>The value the data actually stores. {@link #chanceForScore} answers the bare conversion, which is what a
     * test should pin when it wants to prove the source arithmetic; this one is the runtime answer.
     *
     * @param points the score the material names for her, or {@code 0} when it names none
     */
    public static double chanceInPlay(double points) {
        double bare = Double.isFinite(points) && points > 0.0 ? points : 0.0;
        return Math.max(0.0, Math.min(Crit.MAX_CHANCE,
                (bare + GEAR_POINTS) * CHANCE_PER_POINT));
    }

    /**
     * {@return the modifier id a character's crit grant writes under}
     *
     * @param characterId the character the grant belongs to
     */
    public static ResourceLocation modifierId(ResourceLocation characterId) {
        String path = PREFIX + (characterId == null ? "unknown" : characterId.getPath().toLowerCase(Locale.ROOT));
        return ResourceLocation.fromNamespaceAndPath(Astrum.MOD_ID, path);
    }

    /**
     * {@return the chance this character grants, or {@code 0} when she declares none}
     *
     * <p>The one read of {@code AffectionDef.critChance}, so "what does she grant" is answered in one place and
     * the applier, the book's row and the tests cannot disagree. Absent, {@code 0} and a hostile value all answer
     * {@code 0}, which is the pre-existing behaviour.
     *
     * @param def the character, possibly {@code null}
     */
    public static double chanceOf(@Nullable CharacterDef def) {
        if (def == null || def.affection() == null) {
            return 0.0;
        }
        double declared = def.affection().critChance();
        if (!Double.isFinite(declared) || declared <= 0.0) {
            return 0.0;
        }
        return Math.min(Crit.MAX_CHANCE, declared);
    }

    /**
     * {@return the attribute instance a grant is written to, or empty when this body has no crit attribute}
     *
     * <p>Empty is ordinary: the attribute is added to every <em>registered</em> living type at start-up
     * ({@code Crit.addToEveryLivingEntity}), and a client-side copy or a test double may legitimately not have
     * it.
     *
     * @param entity the body, possibly {@code null}
     */
    public static java.util.Optional<AttributeInstance> instanceOf(@Nullable LivingEntity entity) {
        if (entity == null || !Crit.CRIT_CHANCE.isBound()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(entity.getAttribute(Crit.CRIT_CHANCE));
    }

    /**
     * Writes {@code chance} onto {@code instance}, replacing that character's previous grant.
     *
     * <p>Idempotent in the same sense {@code FlatAttack.grant} is: an existing modifier that is already exactly
     * right is left alone (<b>no write</b>, so nothing on the client re-syncs), while a grant at a different
     * amount is replaced — which is what makes a data change move the number rather than leaving the old one
     * behind.
     *
     * <p>Public over {@link AttributeInstance} rather than only over an entity so the arithmetic is assertable
     * without a world: no {@code LivingEntity} exists in this project's unit tests, and "the declared chance is
     * the modifier's amount, and removing it returns the attribute to 0" is exactly the property the grant owes.
     *
     * @param instance    the attribute to write; {@code null} is a no-op
     * @param characterId whose grant this is, for the modifier id
     * @param chance      the fraction to add, in the attribute's own units
     * @return whether the attribute changed
     */
    public static boolean grantOn(@Nullable AttributeInstance instance, ResourceLocation characterId,
            double chance) {
        if (instance == null || !Double.isFinite(chance) || !(chance > 0.0)) {
            return false;
        }
        double amount = Math.min(Crit.MAX_CHANCE, chance);
        ResourceLocation id = modifierId(characterId);
        AttributeModifier existing = instance.getModifier(id);
        if (existing != null && existing.amount() == amount
                && existing.operation() == AttributeModifier.Operation.ADD_VALUE) {
            return false;
        }
        instance.removeModifier(id);
        instance.addTransientModifier(new AttributeModifier(id, amount,
                AttributeModifier.Operation.ADD_VALUE));
        Astrum.LOGGER.debug("astrum: crit_chance of {} -> +{} under {} (now {})", characterId, amount, id,
                instance.getValue());
        return true;
    }

    /**
     * Takes a character's crit grant back off {@code instance}.
     *
     * @param instance    the attribute to clean; {@code null} is a no-op
     * @param characterId whose grant to remove
     * @return whether something was removed
     */
    public static boolean revokeFrom(@Nullable AttributeInstance instance, ResourceLocation characterId) {
        if (instance == null) {
            return false;
        }
        ResourceLocation id = modifierId(characterId);
        if (instance.getModifier(id) == null) {
            return false;
        }
        instance.removeModifier(id);
        Astrum.LOGGER.debug("astrum: crit_chance of {} removed (now {})", characterId, instance.getValue());
        return true;
    }

    /**
     * {@return the chance a character's grant currently contributes on {@code instance}, {@code 0} when none}
     *
     * <p>The read side, so a log line or a test can state the grant without recomputing the data.
     *
     * @param instance    the attribute, possibly {@code null}
     * @param characterId whose grant to read
     */
    public static double grantedOn(@Nullable AttributeInstance instance, ResourceLocation characterId) {
        if (instance == null) {
            return 0.0;
        }
        AttributeModifier modifier = instance.getModifier(modifierId(characterId));
        return modifier == null ? 0.0 : modifier.amount();
    }

    /**
     * {@return the grants the party wants on {@code player}, already resolved and free of duplicates}
     *
     * <p>Package-private and pure over its inputs, like {@code ExPassives.wantedFor}, so a test can assert "the
     * party's chances are these and nobody else's" without a world.
     *
     * <p><b>Ownership is not re-checked here.</b> {@code ExPassives} does not check it either: the party
     * attachment is the player's own declaration of who is equipped, and a slot whose character the book no
     * longer owns is committed at the edit that placed her. A second filter here would disagree with the EX half
     * one line above it.
     *
     * @param registry the character registry, or {@code null} when no pack is loaded
     * @param party    the party, {@code null} for "no party"
     */
    static List<Wanted> wantedFor(@Nullable Registry<CharacterDef> registry, @Nullable Party party) {
        if (registry == null || party == null) {
            return List.of();
        }
        List<Wanted> wanted = new ArrayList<>();
        Set<ResourceLocation> seen = new LinkedHashSet<>();
        for (var slot : party.slots()) {
            if (slot.isEmpty() || !seen.add(slot.get())) {
                continue;   // the same character cannot hold two slots, but a broken save must not grant twice
            }
            CharacterDef def = registry.get(slot.get());
            double chance = chanceOf(def);
            if (chance > 0.0) {
                wanted.add(new Wanted(slot.get(), chance));
            }
        }
        return List.copyOf(wanted);
    }

    /** {@return the same answer as {@link #wantedFor(Registry, Party)}, read off a live player} */
    static List<Wanted> wantedFor(ServerPlayer player, Registry<CharacterDef> registry, Party party) {
        return wantedFor(registry, party);
    }

    /**
     * Makes the player's crit grants match her party <b>exactly</b>: grants what the occupied slots declare, then
     * removes every grant whose character no longer holds one.
     *
     * <p>Called from {@code ExPassives.apply}, which is the one membership-change sweep of this build (party edit,
     * login, respawn).
     *
     * @param player the player whose crit chance is reconciled; {@code null} is a no-op
     * @return how many grants were written or removed, for the caller's log line
     */
    public static int reconcile(@Nullable ServerPlayer player) {
        if (player == null) {
            return 0;
        }
        Registry<CharacterDef> registry = com.astrum.pcrd.api.v1.AstrumApi.characters().orElse(null);
        Roster roster = player.getData(Attachments.ROSTER);
        Party party = player.getData(Attachments.PARTY);
        return reconcile(player, registry, roster, party);
    }

    /**
     * {@return the reconcile of {@link #reconcile(ServerPlayer)} over explicit inputs, for a test}
     *
     * @param player   the player to write to
     * @param registry the character registry, or {@code null}
     * @param roster   the roster, used only to enumerate the characters whose grant must be revoked
     * @param party    the party
     */
    static int reconcile(ServerPlayer player, @Nullable Registry<CharacterDef> registry, @Nullable Roster roster,
            @Nullable Party party) {
        java.util.Optional<AttributeInstance> instance = instanceOf(player);
        if (instance.isEmpty()) {
            return 0;   // no crit attribute on this body: nothing can be granted and nothing can be stale
        }
        List<Wanted> wanted = wantedFor(registry, party);
        int changes = 0;
        Set<ResourceLocation> keep = new LinkedHashSet<>();
        for (Wanted one : wanted) {
            keep.add(one.characterId());
            if (grantOn(instance.get(), one.characterId(), one.chance())) {
                changes++;
                Astrum.LOGGER.info("astrum: crit_chance of {} -> {} granted on {} — she holds a party slot",
                        one.characterId(), one.chance(), player.getGameProfile().getName());
            }
        }
        changes += revokeNotWanted(instance.get(), roster, keep, player);
        return changes;
    }

    /**
     * Removes every crit grant on {@code instance} whose character the party no longer wants.
     *
     * <h2>Why the roster is the source of candidates</h2>
     * An attribute modifier has no "enumerate by owner", so "which characters currently have a grant?" cannot be
     * asked of the player the way {@code getEffect} answers it for an effect type. The roster is the right list:
     * a grant can only exist if she was once in a party, and enrolment records a character in the roster. This is
     * the same sweep {@code ExPassives.revokeFlatNotWanted} performs for the flat EX grants, for the same reason.
     *
     * <p>A quarantined roster cannot say who to consider, and doing nothing is the safe half: a stale grant is a
     * number that is too high, while a wrong removal is a grant the player earned disappearing. The next readable
     * party edit fixes it.
     *
     * @return how many grants were removed
     */
    private static int revokeNotWanted(AttributeInstance instance, @Nullable Roster roster,
            Set<ResourceLocation> keep, ServerPlayer player) {
        if (roster == null || roster.isQuarantined()) {
            return 0;
        }
        int removed = 0;
        for (ResourceLocation characterId : roster.entries().keySet()) {
            if (keep.contains(characterId)) {
                continue;
            }
            if (revokeFrom(instance, characterId)) {
                removed++;
                Astrum.LOGGER.info("astrum: crit_chance of {} revoked from {} — she holds no party slot any more",
                        characterId, player.getGameProfile().getName());
            }
        }
        return removed;
    }

    /**
     * Removes <b>every</b> crit grant this build can name from {@code player}.
     *
     * <p>Exists so the belt path ({@code ExPassives.clear}) has one implementation of "take them all away"
     * rather than a second {@code removeModifier} literal at the caller.
     *
     * @param player the player to clear; {@code null} is a no-op
     * @return how many grants were removed
     */
    public static int clear(@Nullable ServerPlayer player) {
        java.util.Optional<AttributeInstance> instance = instanceOf(player);
        if (instance.isEmpty() || player == null) {
            return 0;
        }
        Roster roster = player.getData(Attachments.ROSTER);
        if (roster.isQuarantined()) {
            return 0;
        }
        int removed = 0;
        for (ResourceLocation characterId : roster.entries().keySet()) {
            if (revokeFrom(instance.get(), characterId)) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * One resolved grant: whose it is, and how much.
     *
     * @param characterId the character the grant belongs to, for the modifier id and the log line
     * @param chance      the fraction she declares
     */
    record Wanted(ResourceLocation characterId, double chance) {
    }
}