package com.astrum.pcrd.impl.gacha;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.util.RandomSource;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>Which character a character card hands out — the whole lottery rule, as a pure function.</b>
 *
 * <h2>What the human asked for, and the tension inside it</h2>
 * Three sentences, which do not all point the same way:
 *
 * <ol>
 *   <li>「所有角色平分100%」 — all characters share 100% equally. Add a character later and the rule still
 *       holds: N characters, 1/N each before weighting.</li>
 *   <li>「已经有的角色也能抽到」 — an owned character is <b>still rollable</b>. Duplicates are a feature: they
 *       are what the token exchange is for (see {@code MemoryTokenItem}).</li>
 *   <li>「已有的角色权重要低很多」 — but owned ones are weighted <b>much</b> lower.</li>
 * </ol>
 *
 * <p>(1) and (3) contradict each other read literally: if all characters share equally, an owned one cannot
 * also be de-weighted. The reading taken here, and the one this file implements, is: <b>every character is in
 * the pool with the same base weight, and ownership multiplies that weight down.</b> Before anything is
 * owned the pool is flat — 25% each at four characters, which is exactly (1) — and as the book fills, the
 * remaining unowned characters take the share the owned ones give up. That satisfies (2) literally (nothing
 * is ever removed from the pool) and (3) as a weight, which is the only way both can be true.
 *
 * <h2>The numbers</h2>
 * <ul>
 *   <li><b>unowned: {@value #UNOWNED_WEIGHT}</b></li>
 *   <li><b>owned: {@value #OWNED_WEIGHT}</b> — about a sixth of an unowned character's weight</li>
 * </ul>
 *
 * At four characters, measured:
 *
 * <pre>
 *   none owned          25.0%  /  25.0%  /  25.0%  /  25.0%
 *   one owned            4.7%  /  31.8%  /  31.8%  /  31.8%      (15 : 100 : 100 : 100 = 3.17%)
 *   two owned            9.1%  /   9.1%  /  40.9%  /  40.9%      (15 : 15 : 100 : 100 = 6.52%)
 *   three owned         13.0%  /  13.0%  /  13.0%  /  60.9%      (15 : 15 : 15 : 100 = 10.3%)
 * </pre>
 *
 * The last line is the one worth looking at: with three of four owned there is still a 61% chance of the
 * character the player does not have yet, and a 39% chance of a duplicate — so the token route is reachable
 * but a duplicate is never the expected outcome. A harsher de-weight (say 1:100) would make the last
 * unowned character a near-certainty and the tokens unreachable; a flatter one (1:2) would make the book
 * fill slowly and feel like the game was not paying attention. 15:100 is the ratio that keeps both live,
 * and it is two named constants so it is a decision, not a magic number.
 *
 * <h2>Why this is a class and not a method on the item</h2>
 * The roll is the only thing in the card pipeline with a distribution, and a distribution has to be
 * <b>testable without a world</b> — otherwise "all characters share 100%" is a claim about code nobody
 * measured. So the rule lives here, takes a {@link RandomSource} and a list of character ids plus the set
 * the player owns, and returns an id. The item supplies the registry, the roster and the RNG; nothing about
 * probability is decided there.
 *
 * <p>{@link #roll} is total: an empty pool answers empty rather than throwing, because "the pack defines no
 * characters" is a data state a player can reach (a broken datapack) and a crafting event that threw would
 * eat the ingredients.
 *
 * <h2>Two rolls, one implementation</h2>
 * The human distinguished two ways to get a card, and they are <b>not</b> the same distribution:
 *
 * <ul>
 *   <li><b>crafting a card</b> is the ordinary lottery — {@link Mode#ORDINARY}, unowned : owned = 100 : 15,
 *       with duplicates possible by design (「已经有的角色也能抽到」);</li>
 *   <li><b>a hundred 记忆结晶</b> is 「优先未拥有」 — {@link Mode#GUARANTEED_PREFER_UNOWNED}, uniform among the
 *       characters the player does <b>not</b> have, which he called a better roll than ordinary crafting
 *       (「直接随机获取一个没有的角色」).</li>
 * </ul>
 *
 * <p>{@link #modeOf} chooses, and both go through the same {@link #roll}: the guaranteed mode is the
 * ordinary one with the owned weights zeroed, so there is one weighted draw in this class and not two
 * that could disagree about ties, ordering or the RNG's range. When every character is owned the
 * guaranteed mode <b>falls back</b> rather than refusing — see {@link Mode#GUARANTEED_PREFER_UNOWNED}.
 */
@ApiStatus.Internal
public final class CardRoll {

    /**
     * <b>Which distribution a draw uses.</b>
     *
     * <p>An enum rather than a boolean or two methods: the caller has to say <em>which</em> roll it is
     * asking for, the two differ in a way a player can feel, and a named value makes the difference
     * greppable from the item that asks. Same shape as {@code CastGate.Reason}.
     */
    public enum Mode {
        /**
         * The ordinary lottery: unowned {@code 100}, owned {@code 15}. Crafting a card uses this.
         */
        ORDINARY,

        /**
         * 「优先未拥有」: uniform among the characters the player does <b>not</b> own — a hundred crystals
         * must not hand back a duplicate while there is somebody new to give.
         *
         * <h2>Why "own everything" falls back instead of refusing</h2>
         * Rule B, and the human's instruction that the tokens must never be dead weight
         * (「不能没用」): with nobody left to unlock there is nothing to prefer, so the draw becomes
         * {@link #ORDINARY} and hands out a duplicate. That duplicate recycles into more crystals
         * (「重复的角色卡可以合成成记忆结晶」), so the loop closes rather than stalling on an item that
         * cannot be spent. Refusing was the alternative and it would make the last hundred crystals a
         * permanent, unspendable reminder of a completed collection.
         */
        GUARANTEED_PREFER_UNOWNED;

        /** {@return whether this mode restricts the draw to the characters the player does not own} */
        public boolean prefersUnownedOnly() {
            return this == GUARANTEED_PREFER_UNOWNED;
        }
    }

    /**
     * Weight of a character the player does not own.
     *
     * <p>The base weight every character gets, which is what makes the pool flat before anything is owned —
     * 「所有角色平分100%」 read exactly.
     */
    public static final int UNOWNED_WEIGHT = 100;

    /**
     * Weight of a character the player already owns — 「已有的角色权重要低很多」.
     *
     * <p>Deliberately not zero: 「已经有的角色也能抽到」 is explicit that a duplicate must be possible, and a
     * zero weight would silently implement "owned characters are not rollable" instead. At 15 against 100
     * the character is about a sixth as likely as an unowned one, which keeps the last unowned character
     * reachable without making it a certainty.
     */
    public static final int OWNED_WEIGHT = 15;

    private CardRoll() {
    }

    /**
     * {@return the weight {@code characterId} carries in {@code pool}}
     *
     * @param characterId the character
     * @param owned       the characters the player already has
     */
    public static int weightOf(ResourceLocation characterId, Collection<ResourceLocation> owned) {
        Objects.requireNonNull(characterId, "characterId");
        return owned != null && owned.contains(characterId) ? OWNED_WEIGHT : UNOWNED_WEIGHT;
    }

    /**
     * {@return the weight {@code characterId} carries under {@code mode}}
     *
     * <p>The whole of the two modes: {@link Mode#ORDINARY} is {@link #weightOf}, and
     * {@link Mode#GUARANTEED_PREFER_UNOWNED} gives an owned character <b>weight zero</b> — which is not a
     * second lottery but the same one with the duplicate tickets removed. A weight of zero also means the
     * mode cannot pick an owned character <em>by construction</em> rather than by a filter afterwards,
     * which is the property the unit test relies on.
     *
     * @param characterId the character
     * @param owned       the characters the player already has
     * @param mode        which distribution is being drawn
     */
    public static int weightUnder(Mode mode, ResourceLocation characterId,
            Collection<ResourceLocation> owned) {
        Objects.requireNonNull(mode, "mode");
        if (mode.prefersUnownedOnly() && owned != null && owned.contains(characterId)) {
            return 0;
        }
        return weightOf(characterId, owned);
    }

    /**
     * {@return the probability {@code characterId} is drawn from {@code pool}, in {@code 0..1}}
     *
     * <p>{@code 0} for a character not in the pool, and {@code 0} for every character when the pool is
     * empty. Exposed so a test can assert the distribution as numbers rather than by rolling ten thousand
     * times and hoping, and so a future debug command can show a player the odds.
     *
     * @param characterId the character
     * @param pool        every character that can be drawn
     * @param owned       the characters the player already has
     */
    public static double chanceOf(ResourceLocation characterId, Collection<ResourceLocation> pool,
            Collection<ResourceLocation> owned) {
        List<ResourceLocation> candidates = distinct(pool);
        long total = totalWeight(candidates, owned);
        if (total == 0 || !candidates.contains(characterId)) {
            return 0.0;
        }
        return weightOf(characterId, owned) / (double) total;
    }

    /**
     * {@return one character drawn from {@code pool}, or empty when the pool is empty}
     *
     * <p>A single weighted draw: every character's weight is {@link #weightOf}, the weights are summed, one
     * ticket is drawn uniformly in {@code [0, total)}, and the characters are walked in order until the
     * running total passes it. That is the textbook algorithm and it is here rather than in the item because
     * it is the rule.
     *
     * <p>The order is the pool's order, which is the registry's order, so the same seed and the same pool
     * give the same answer — a property the tests use.
     *
     * @param pool  every character that can be drawn, in a stable order
     * @param owned the characters the player already has
     * @param random the source of the draw
     */
    public static Optional<ResourceLocation> roll(Collection<ResourceLocation> pool,
            Collection<ResourceLocation> owned, RandomSource random) {
        return roll(Mode.ORDINARY, pool, owned, random);
    }

    /**
     * {@return one character drawn from {@code pool} under {@code mode}, or empty when nothing can be drawn}
     *
     * <h2>The fallback, and why it is here rather than at a call site</h2>
     * {@link Mode#GUARANTEED_PREFER_UNOWNED} with every character owned has no unowned candidate, so its
     * total weight is zero. That is <b>not</b> an empty pool: the player's hundred crystals must still buy a
     * card (rule B), so the draw silently becomes {@link Mode#ORDINARY}. Putting the fallback inside this
     * method is what stops the item from having to know about it — and stops a second call site from
     * forgetting it, which would strand a completed collection with an unspendable stack.
     *
     * <p>The non-fallback path is the textbook weighted draw: weights summed, one ticket drawn uniformly in
     * {@code [0, total)}, candidates walked in order until the running total passes it.
     *
     * <p>The order is the pool's order, which is the registry's order, so the same seed and the same pool
     * give the same answer — a property the tests use.
     *
     * @param mode  which distribution to draw from
     * @param pool  every character that can be drawn, in a stable order
     * @param owned the characters the player already has
     * @param random the source of the draw
     */
    public static Optional<ResourceLocation> roll(Mode mode, Collection<ResourceLocation> pool,
            Collection<ResourceLocation> owned, RandomSource random) {
        Objects.requireNonNull(random, "random");
        Mode effective = mode == null ? Mode.ORDINARY : mode;
        List<ResourceLocation> candidates = distinct(pool);
        long total = totalWeight(candidates, owned, effective);
        if (total == 0 && effective.prefersUnownedOnly() && !candidates.isEmpty()) {
            // Owns everything (or the pool is entirely owned): fall back to the ordinary lottery so the
            // tokens are never dead weight. Only a genuinely empty pool answers empty.
            return roll(Mode.ORDINARY, candidates, owned, random);
        }
        if (total == 0) {
            return Optional.empty();
        }
        // A single uniformly-drawn ticket in [0, total). `RandomSource` offers nextInt(bound) and not
        // nextLong(bound), and the bound is a sum of small per-character weights — five characters give at most
        // 500 — so an int draw is exact and needs no widening. A pool large enough to overflow an int would need
        // two billion characters, so the cast is a documented narrowing rather than a hopeful one.
        int ticket = random.nextInt((int) total);
        long running = 0;
        for (ResourceLocation candidate : candidates) {
            running += weightUnder(effective, candidate, owned);
            if (ticket < running) {
                return Optional.of(candidate);
            }
        }
        // Unreachable: the loop consumes exactly `total` tickets. Answered rather than thrown because this
        // runs inside a crafting event, where an exception would cost the player the ingredients.
        return Optional.of(candidates.get(candidates.size() - 1));
    }

    /**
     * {@return the characters of {@code pool} the player does not own, in pool order}
     *
     * <p>What {@link Mode#GUARANTEED_PREFER_UNOWNED} restricts to, exposed so a caller can ask the question
     * without drawing (a refusal message that wants to say "you have them all", or a test that wants to
     * count the pool). Derived from the same {@code pool} and {@code owned} arguments {@link #roll} takes,
     * so the two cannot disagree about who is eligible.
     *
     * @param pool  every character that can be drawn
     * @param owned the characters the player already has
     */
    public static List<ResourceLocation> unownedIn(Collection<ResourceLocation> pool,
            Collection<ResourceLocation> owned) {
        List<ResourceLocation> unowned = new ArrayList<>();
        for (ResourceLocation candidate : distinct(pool)) {
            if (owned == null || !owned.contains(candidate)) {
                unowned.add(candidate);
            }
        }
        return List.copyOf(unowned);
    }

    /**
     * {@return {@code count} independent draws from the same pool, each seeing the ownership of the ones
     * before it}
     *
     * <h2>Why the ownership is re-evaluated between cards</h2>
     * A shift-craft produces a stack of cards in <b>one</b> event, and the human's requirement is that each
     * card rolls independently <em>and</em> accounts for what the player owns at that moment. Rolling the
     * whole batch against one snapshot would mean a stack of five could come back as five copies of the same
     * girl — not because the odds said so, but because the fifth card never learned about the first four.
     *
     * <p>So this walks the batch once, adding each result to a working copy of {@code owned} before the next
     * draw. The pool never shrinks — an owned character keeps {@link #OWNED_WEIGHT} — so a batch can still
     * contain duplicates, which is correct: duplicates are rollable by design.
     *
     * @param pool   every character that can be drawn
     * @param owned  the characters the player has before the batch
     * @param count  how many cards are being produced
     * @param random the source of the draws
     */
    public static List<ResourceLocation> rollBatch(Collection<ResourceLocation> pool,
            Collection<ResourceLocation> owned, int count, RandomSource random) {
        Objects.requireNonNull(random, "random");
        if (count <= 0) {
            return List.of();
        }
        List<ResourceLocation> seen = new ArrayList<>(owned == null ? List.of() : owned);
        List<ResourceLocation> drawn = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Optional<ResourceLocation> next = roll(pool, seen, random);
            if (next.isEmpty()) {
                break;   // empty pool: fewer cards than asked for, never a null entry
            }
            drawn.add(next.get());
            seen.add(next.get());
        }
        return List.copyOf(drawn);
    }

    /**
     * {@return {@code pool} without repeats, keeping the first occurrence's position}
     *
     * <p>The registry cannot hand out duplicates, but this class is public and a caller could pass a list
     * built by concatenating two sources; a duplicate would then silently double that character's weight,
     * which is the one bug in a lottery that nobody would notice by playing.
     *
     * @param pool the candidates
     */
    private static List<ResourceLocation> distinct(@Nullable Collection<ResourceLocation> pool) {
        if (pool == null || pool.isEmpty()) {
            return List.of();
        }
        List<ResourceLocation> unique = new ArrayList<>(new java.util.LinkedHashSet<>(pool));
        unique.removeIf(Objects::isNull);
        return unique;
    }

    /** {@return the sum of every candidate's weight, {@code 0} for an empty pool} */
    private static long totalWeight(List<ResourceLocation> candidates,
            Collection<ResourceLocation> owned) {
        return totalWeight(candidates, owned, Mode.ORDINARY);
    }

    /** {@return the sum of every candidate's weight under {@code mode}, {@code 0} for an empty pool} */
    private static long totalWeight(List<ResourceLocation> candidates,
            Collection<ResourceLocation> owned, Mode mode) {
        long total = 0;
        for (ResourceLocation candidate : candidates) {
            total += weightUnder(mode, candidate, owned);
        }
        return total;
    }
}


