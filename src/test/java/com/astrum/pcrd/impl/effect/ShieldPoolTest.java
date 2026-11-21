package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.impl.attach.ShieldKind;
import com.astrum.pcrd.impl.attach.ShieldLayer;
import com.astrum.pcrd.impl.attach.ShieldState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The shield coexists with every source of absorption</b> — the human's 「两者可以叠加」 and 「覆盖只会覆盖自身,
 * 不会覆盖金苹果还有其他mod的额外生命值」 — <b>and it now has two separate channels</b>
 * (「物理护盾和魔法护盾…做一下2个单独的通道」), as arithmetic.
 *
 * <h2>Two designs failed before the coexistence one, and both are pinned here</h2>
 * The first stored only the pool and reconciled it as 「our part is what the float holds beyond vanilla's
 * share」. On a real dedicated server that measured <b>wrong</b>:
 *
 * <pre>
 *   [T35] shield granted=true -&gt; absorption 4.0 (ours 4.0)
 *   [T35] after the golden apple: absorption 4.0 (raw, vanilla's share 4.0), our pool 0.0
 *   [T35] FAIL our pool survived vanilla's max() write: 0.0
 * </pre>
 *
 * <p>The cause is in vanilla's own code: {@code AbsorptionMobEffect.onEffectStarted} writes
 * {@code setAbsorptionAmount(max(current, 4 * (1 + amplifier)))} — a <b>max</b>, so an apple drunk on top of
 * our shield leaves the float at its own four points and the sum is <em>never visible</em>.
 *
 * <p>The second design remembered {@code lastWritten} and fixed that, but wrote {@code ours + vanillaShare}, so
 * <b>only vanilla's source was known</b>: any other mod's absorption was replaced by our number on the next
 * mirror. That is the defect the next group of cases pins: the share a grant may replace is <em>ours</em>, and
 * the rest of the float — read as a difference, not enumerated — belongs to whoever put it there.
 *
 * <h2>What the typed-channel group adds</h2>
 * 由衣's bwiki barrier is 「物理伤害的护盾」 and 望's is 「物理·魔法伤害的护盾」. Vanilla's absorption spends the
 * whole float on <b>any</b> damage, so the split cannot be left to it: {@code Shields.parkForTypedDamage} takes
 * the layers a hit cannot use out of the float before vanilla spends it, and the next read derives what the hit
 * cost from the float itself. The cases below pin that derivation: a physical barrier lets a magic hit through
 * untouched, a magic one is its mirror, a shared one absorbs either, two kinds on one player do not cancel each
 * other, and the specific channel pays before the shared one.
 *
 * <h2>What cannot be asserted here</h2>
 * No entity exists in a unit test, so the attribute modifier, {@code setAbsorptionAmount} and the damage event
 * itself are unasserted; these classes are the pure half. The rules that were wrong in past defects are pure
 * and are pinned, and the application is proved by the server log — a human in the game is what settles whether
 * the channels <em>feel</em> right.
 */
class ShieldPoolTest {

    /** A golden apple's grant at amplifier 0: {@code 4 × (1 + 0)}. */
    private static final float APPLE = 4.0F;

    /** ペコリーヌ's 専武 shield at the reference bar: 20% of 20 health. */
    private static final float OURS = 4.0F;

    /** Six points of absorption from <b>another mod</b> — a source this code cannot name. */
    private static final float FOREIGN = 6.0F;

    /** A game time comfortably inside a 160-tick shield granted at tick 0. */
    private static final long LIVE = 100L;

    /** {@return the ledger of a body carrying a shared shield of {@code ours} and nobody else's absorption} */
    private static ShieldState mine(float ours) {
        return ShieldState.NONE.granted(ShieldKind.BOTH, ours, 0L, 160).written(ours, 0.0F, 0.0F);
    }

    /** {@return the ledger of a body carrying one typed layer of {@code ours}, with no clock concerns} */
    private static ShieldState typed(ShieldKind kind, float ours) {
        return ShieldState.NONE.granted(kind, ours, 0L, 160).written(ours, 0.0F, 0.0F);
    }

    /** {@return {@code state} as the float the damage hook writes for a hit of that half} */
    private static ShieldState parked(ShieldState state, boolean magic) {
        float theirs = state.foreignShare(state.lastWritten(), 0.0F);
        float visible = state.coveredAmount(magic) + theirs;
        return state.parkedFor(magic, visible, theirs, 0.0F);
    }

    // ------------------------------------------------------------------ vanilla's share, as the game defines it

    /**
     * <b>The vanilla-share formula is the game's own.</b>
     *
     * <p>{@code 4 × (1 + amplifier)}, read from {@code AbsorptionMobEffect.onEffectStarted}. It is the one
     * number the golden-apple half of the design rests on, so it is pinned against the values a player
     * recognises: a level-I apple gives four hearts, a level-II eight.
     */
    @Test
    void vanillaShareIsTheGamesOwnFormula() {
        assertEquals(4.0F, ShieldPool.vanillaShare(0), 1e-6, "level I absorption = 4 hearts");
        assertEquals(8.0F, ShieldPool.vanillaShare(1), 1e-6, "level II = 8");
        assertEquals(0.0F, ShieldPool.vanillaShare(-1), 1e-6, "no effect means nothing of vanilla's");
    }

    /**
     * <b>Shield then apple — the case the server measured wrong, and the one that must not regress.</b>
     *
     * <p>Vanilla's {@code max(current, 4)} writes the float down to 4; our pool is untouched because it is not
     * in the float, so the re-mirror puts the sum back as 8.
     */
    @Test
    void aGoldenAppleAfterOurShieldDoesNotReduceTheTotal() {
        ShieldState state = mine(OURS);
        assertEquals(OURS, state.total(), 1e-6);

        // The apple: vanilla's max() leaves the float at 4, and vanilla's share is now 4.
        ShieldState afterApple = state.reconciledAgainst(APPLE, LIVE);
        float theirs = afterApple.foreignShare(APPLE, APPLE);
        assertEquals(OURS, afterApple.total(), 1e-6,
                "the shield is unchanged: the float did not fall, so nothing of ours was spent");
        assertEquals(APPLE, theirs, 1e-6,
                "vanilla's four are owed even though its max() write could not show them as a rise");
        assertEquals(OURS + APPLE, afterApple.totalWith(theirs), 1e-6,
                "so the surface shows 8 — our four plus the apple's four");
    }

    /** <b>Apple then shield.</b> Ours adds to what is already there, which is the plain reading of 叠加. */
    @Test
    void ourShieldAddsToAnExistingGoldenApple() {
        ShieldState state = ShieldState.NONE.granted(ShieldKind.BOTH, OURS, 0L, 160)
                .reconciledAgainst(APPLE, LIVE);
        float theirs = state.foreignShare(APPLE, APPLE);
        assertEquals(OURS, state.total(), 1e-6, "no loss: the float was never written by us, so it is not ours");
        assertEquals(APPLE, theirs, 1e-6);
        assertEquals(OURS + APPLE, state.totalWith(theirs), 1e-6, "8, in this order too");
        assertEquals(OURS + 2 * APPLE, ShieldPool.mirror(OURS, ShieldPool.vanillaShare(1)), 1e-6,
                "and a level-II apple is 8 of vanilla's, so 12 in total");
    }

    /**
     * <b>The apple expires.</b> Its effect is gone, so its share is no longer owed — and the four it left in the
     * float are <em>not</em> re-read as some other mod's grant.
     */
    @Test
    void ourShareIsWhatRemainsWhenTheAppleExpires() {
        ShieldState state = mine(OURS).written(OURS + APPLE, APPLE, APPLE);
        assertEquals(OURS + APPLE, state.totalWith(state.foreignShare(OURS + APPLE, APPLE)), 1e-6,
                "8 while the apple is up");
        // The apple is removed: vanillaShareOf now answers 0 while the float still holds 8.
        ShieldState afterExpiry = state.reconciledAgainst(OURS + APPLE, LIVE);
        assertEquals(OURS, afterExpiry.total(), 1e-6,
                "the shield is still ours: the float never fell, so nothing of ours was spent");
        assertEquals(0.0F, afterExpiry.foreignShare(OURS + APPLE, 0.0F), 1e-6,
                "and the apple's four are not re-claimed as a foreign grant that nobody made");
        assertEquals(OURS, afterExpiry.totalWith(0.0F), 1e-6, "our four hearts remain");
        // Once the mirror has written 4, a later observation of 4 is also no loss.
        ShieldState afterMirror = afterExpiry.written(OURS, 0.0F, 0.0F).reconciledAgainst(OURS, LIVE + 1);
        assertEquals(OURS, afterMirror.total(), 1e-6, "stable across ticks");
    }

    // ------------------------------------------------------------------ the other mods, whoever they are

    /**
     * <b>Another source's absorption survives our grant</b> — the defect the coexistence design exists to fix.
     *
     * <p>The float is larger than our share and nothing in it is vanilla's, so the old mirror — which recomputed
     * the other side from {@code vanillaShare} alone — wrote our four <em>over</em> their six.
     */
    @Test
    void anotherModsAbsorptionIsNotReplacedByOurGrant() {
        ShieldState after = ShieldState.NONE.granted(ShieldKind.BOTH, OURS, 0L, 160);
        float theirs = after.foreignShare(FOREIGN, 0.0F);
        assertEquals(FOREIGN, theirs, 1e-6,
                "the whole float was somebody else's before our grant, so all six are theirs");
        assertEquals(FOREIGN + OURS, after.totalWith(theirs), 1e-6, "our four add to their six: ten, not four");

        ShieldState written = after.written(FOREIGN + OURS, FOREIGN, 0.0F);
        ShieldState regranted = written.granted(ShieldKind.BOTH, OURS, LIVE, 160);
        assertEquals(FOREIGN, regranted.foreignShare(FOREIGN + OURS, 0.0F), 1e-6,
                "a re-grant replaces our share, never theirs");
        assertEquals(FOREIGN + OURS, regranted.totalWith(regranted.foreignShare(FOREIGN + OURS, 0.0F)), 1e-6);
    }

    /**
     * <b>A foreign grant made while our shield is up is kept too.</b>
     *
     * <p>Their mod wrote the float up from 4 to 10. That rise is the only evidence the code has, and it is
     * enough: the six become theirs, our four stay ours, and the tick keeps writing 10 instead of writing a 4
     * that would delete their work twenty times a second.
     */
    @Test
    void aForeignGrantOnTopOfOurShieldIsKept() {
        ShieldState state = mine(OURS);
        ShieldState seen = state.reconciledAgainst(FOREIGN + OURS, LIVE);
        assertEquals(OURS, seen.total(), 1e-6, "the float rose, so nothing of ours was spent");
        float theirs = seen.foreignShare(FOREIGN + OURS, 0.0F);
        assertEquals(FOREIGN, theirs, 1e-6, "the rise is theirs");
        assertEquals(FOREIGN + OURS, seen.totalWith(theirs), 1e-6);
    }

    /**
     * <b>Releasing our shield keeps every other source.</b>
     *
     * <p>What {@code Shields.clear} writes when our effect ends: the other side of the float, computed by the
     * same rule. The old code wrote {@code min(observed, vanillaShare)}, which dropped everything that was
     * neither ours nor the apple's.
     */
    @Test
    void releasingOurShieldKeepsEveryOtherSource() {
        ShieldState state = mine(OURS).written(FOREIGN + OURS, FOREIGN, 0.0F);
        ShieldState released = state.reconciledAgainst(FOREIGN + OURS, LIVE);
        assertEquals(FOREIGN, released.foreignShare(FOREIGN + OURS, 0.0F), 1e-6,
                "their six are what our expiry leaves behind");

        ShieldState three = mine(OURS).written(OURS + APPLE + FOREIGN, APPLE + FOREIGN, APPLE);
        ShieldState gone = three.reconciledAgainst(OURS + APPLE + FOREIGN, LIVE);
        assertEquals(APPLE + FOREIGN, gone.foreignShare(OURS + APPLE + FOREIGN, APPLE), 1e-6);
    }

    // ------------------------------------------------------------------ damage, and the traps it sets

    /**
     * <b>Damage spends the float, and our shield pays first.</b> This is what {@link ShieldPool#lossSince}
     * measures — a difference, because that is the one thing the float can report.
     */
    @Test
    void damageIsMeasuredAsTheDifferenceSinceOurLastWrite() {
        ShieldState state = ShieldState.NONE.granted(ShieldKind.BOTH, 10.0F, 0L, 160)
                .written(10.0F, 0.0F, 0.0F);
        assertEquals(0.0F, ShieldPool.lossSince(10.0F, 10.0F), 1e-6, "nothing spent yet");
        assertEquals(4.0F, ShieldPool.lossSince(10.0F, 6.0F), 1e-6, "4 of damage");
        assertEquals(10.0F, ShieldPool.lossSince(10.0F, 0.0F), 1e-6, "all of it spent");
        assertEquals(0.0F, ShieldPool.lossSince(10.0F, 14.0F), 1e-6,
                "a float that ROSE (a foreign grant) is not a negative loss");

        assertEquals(6.0F, state.reconciledAgainst(6.0F, LIVE).total(), 1e-6, "4 spent, 6 left");
        assertEquals(0.0F, state.reconciledAgainst(0.0F, LIVE).total(), 1e-6, "fully spent");
    }

    /**
     * <b>A hit our shield can pay does not touch anybody else's absorption, and one it cannot pay takes the
     * overflow from them.</b>
     *
     * <p>「自身」 first is the documented order, and it is the order that keeps a foreign grant from being
     * destroyed by a hit our shield was there to absorb.
     */
    @Test
    void damagePaysOurShieldBeforeAnyOtherSource() {
        ShieldState state = mine(OURS).written(FOREIGN + OURS, FOREIGN, 0.0F);   // ours 4 + theirs 6 = 10

        ShieldState smallHit = state.reconciledAgainst(7.0F, LIVE);             // 3 of damage
        assertEquals(1.0F, smallHit.total(), 1e-6, "our four paid the three");
        assertEquals(FOREIGN, smallHit.foreignShare(7.0F, 0.0F), 1e-6, "their six are untouched");
        assertEquals(7.0F, smallHit.totalWith(smallHit.foreignShare(7.0F, 0.0F)), 1e-6);

        ShieldState bigHit = state.reconciledAgainst(5.0F, LIVE);               // 5 of damage
        assertEquals(0.0F, bigHit.total(), 1e-6, "our four were spent, and one point of theirs with them");
        assertEquals(5.0F, bigHit.foreignShare(5.0F, 0.0F), 1e-6, "the overflow is the only part they lose");
    }

    /**
     * <b>A tick must not refill a shield that damage has spent.</b>
     *
     * <p>This is the second trap of a naive {@code write pool + theirs}: the effect ticks 20 times a second, so a
     * write that ignores that the float fell would put the shield back every tick and the player would be
     * invulnerable for eight seconds.
     */
    @Test
    void tickingCannotRefillASpentShield() {
        ShieldState state = mine(OURS);
        ShieldState afterHit = state.reconciledAgainst(0.0F, LIVE);
        assertEquals(0.0F, afterHit.total(), 1e-6);
        assertFalse(afterHit.isPresent(), "so the effect's tick has nothing to mirror, and returns");
        assertEquals(0.0F, afterHit.reconciledAgainst(0.0F, LIVE + 1).total(), 1e-6,
                "a tick must not restore what damage took");
        assertEquals(0.0F, afterHit.totalWith(afterHit.foreignShare(0.0F, 0.0F)), 1e-6,
                "and the mirror writes nothing");
    }

    /**
     * <b>A foreign write DOWN is indistinguishable from damage, and is documented as such.</b>
     *
     * <p>Absorption is one float: when another mod sets it lower, nothing in the value says whether those points
     * were spent by a hit or taken by a mod. Damage is paid by our shield first, so our shield pays this too.
     */
    @Test
    void aForeignWriteDownIsArithmeticallyDamage() {
        ShieldState clobbered = mine(OURS).reconciledAgainst(2.0F, LIVE);
        assertEquals(2.0F, clobbered.total(), 1e-6,
                "two of our four are gone: the float fell by two and nothing can say who took them");
    }

    // ------------------------------------------------------------------ the two channels

    /**
     * <b>A physical barrier absorbs a physical hit and lets a magical one through untouched</b> — 由衣's
     * 「无效【45+45*技能等级】物理伤害的护盾」, which the single-pool {@code SHIELD} could not express.
     *
     * <p>The mechanism is the parking rule: a magic hit takes the physical layer <b>out</b> of the float, so
     * vanilla has nothing of ours to spend and the bolt reaches health. The read that follows then derives what
     * the hit cost from the float — and there is nothing of ours in it, so nothing is spent.
     */
    @Test
    void aPhysicalShieldBlocksAPhysicalHitAndLetsAMagicOneThrough() {
        ShieldState state = typed(ShieldKind.PHYS, 10.0F);

        // A magic hit: the physical layer is parked, so the float it leaves behind holds none of our points.
        ShieldState parked = parked(state, true);
        assertTrue(state.parksFor(true), "a physical barrier has to leave the float for a magic hit");
        assertEquals(0.0F, parked.coveredAmount(true), 1e-6, "it can absorb none of the bolt");
        assertEquals(0.0F, parked.lastWritten(), 1e-6, "so the float it writes is everybody else's share: zero");
        ShieldState afterMagic = parked.reconciledAgainst(0.0F, LIVE);
        assertEquals(10.0F, afterMagic.total(), 1e-6, "the bolt spent none of the barrier");

        // And a physical hit parks nothing: every layer covers it, so vanilla's own absorption spends it.
        assertFalse(state.parksFor(false), "nothing needs parking for the half it covers");
        ShieldState afterPhysical = state.reconciledAgainst(6.0F, LIVE);
        assertEquals(6.0F, afterPhysical.total(), 1e-6, "the physical hit spent four of the ten, so six are left");
    }

    /** <b>A magic barrier is the exact mirror</b> — the channel is a split, not a one-way special case. */
    @Test
    void aMagicShieldIsTheMirror() {
        ShieldState state = typed(ShieldKind.MAGIC, 10.0F);
        assertTrue(state.parksFor(false), "the magic barrier leaves the float for a physical hit");
        assertFalse(state.parksFor(true), "and stays for a magical one");
        assertEquals(10.0F, state.coveredAmount(true), 1e-6);
        assertEquals(0.0F, state.coveredAmount(false), 1e-6);
        ShieldState afterMagic = state.reconciledAgainst(6.0F, LIVE);
        assertEquals(6.0F, afterMagic.total(), 1e-6, "the magic hit spent four of the ten, so six are left");
        ShieldState afterPhysical = parked(state, false).reconciledAgainst(0.0F, LIVE);
        assertEquals(10.0F, afterPhysical.total(), 1e-6, "the physical hit spent none");
    }

    /**
     * <b>A shared barrier absorbs either half from one pool</b> — 望's 「物理·魔法伤害的护盾」.
     *
     * <p>One layer of 27 is worth <b>27</b>, not 54: 「物理·魔法」 describes <em>what</em> the barrier absorbs,
     * not that there are two of them. A hit of either type spends the same pool.
     */
    @Test
    void aBothShieldBlocksEitherHalf() {
        ShieldState state = typed(ShieldKind.BOTH, 10.0F);
        assertEquals(10.0F, state.coveredAmount(true), 1e-6, "magic is absorbed");
        assertEquals(10.0F, state.coveredAmount(false), 1e-6, "and so is physical, from the same ten");
        assertFalse(state.parksFor(true), "nothing to park: the pool covers every half");
        assertFalse(state.parksFor(false));
        assertEquals(9.0F, state.reconciledAgainst(9.0F, LIVE).total(), 1e-6, "one point of magic spent it");
        assertEquals(9.0F, state.reconciledAgainst(9.0F, LIVE).coveredAmount(true), 1e-6,
                "and the whole remaining nine still absorb magic");
    }

    /**
     * <b>Two kinds on one player do not cancel each other</b> — 由衣 and 望 in the same party, both barriers on
     * one body.
     *
     * <p>The two layers have their own amounts and their own clocks, and each grant replaced <b>only its own
     * channel</b>. Neither one's expiry takes the other's points with it.
     */
    @Test
    void twoDifferentKindsOnOnePlayerDoNotCancelEachOther() {
        ShieldState phys = typed(ShieldKind.PHYS, 4.0F);
        ShieldState both = phys.granted(ShieldKind.BOTH, 10.0F, 0L, 160);
        assertEquals(4.0F, layerPool(both, ShieldKind.PHYS), 1e-6, "the physical four are still there");
        assertEquals(10.0F, layerPool(both, ShieldKind.BOTH), 1e-6, "and the shared ten arrived beside them");
        assertEquals(14.0F, both.total(), 1e-6, "ten shared plus four physical");
        assertEquals(2, both.layers().size(), "two layers, not one merged pool");

        // Each layer keeps its own deadline: 由衣's 8 s barrier must not be extended to 望's 19.5 s.
        ShieldState differentClocks = phys.granted(ShieldKind.PHYS, 4.0F, 0L, 160)
                .granted(ShieldKind.BOTH, 10.0F, 40L, 390).written(14.0F, 0.0F, 0.0F);
        assertEquals(160L, differentClocks.layers().get(0).expiresAtTick());
        assertEquals(430L, differentClocks.layers().get(1).expiresAtTick(),
                "two clocks, so one barrier cannot cut or extend the other");
        ShieldState afterPhysExpiry = differentClocks.reconciledAgainst(14.0F, 200L);
        assertEquals(10.0F, afterPhysExpiry.total(), 1e-6,
                "the physical barrier is over, the shared one still holds its ten");
        assertEquals(0.0F, afterPhysExpiry.foreignShare(14.0F, 0.0F), 1e-6,
                "and the expired four are not re-claimed as somebody else's");
    }

    /**
     * <b>A grant replaces only its own channel.</b> 由衣 re-grants while 望's barrier is up: the physical layer
     * is replaced, the shared layer is untouched — 「覆盖只会覆盖自身」 one level up.
     */
    @Test
    void aTypedGrantReplacesOnlyItsOwnChannel() {
        ShieldState start = typed(ShieldKind.BOTH, 10.0F).granted(ShieldKind.PHYS, 4.0F, 0L, 160);
        ShieldState regranted = start.granted(ShieldKind.PHYS, 6.0F, 50L, 160);
        assertEquals(6.0F, layerPool(regranted, ShieldKind.PHYS), 1e-6, "the physical channel is replaced");
        assertEquals(10.0F, layerPool(regranted, ShieldKind.BOTH), 1e-6, "the shared channel is not");
        assertEquals(16.0F, regranted.total(), 1e-6);
        assertEquals(2, regranted.layers().size(), "still one layer per kind, never two of one");
    }

    /**
     * <b>The type-restricted channel pays before the shared pool.</b>
     *
     * <p>With 由衣's physical four and 望's shared ten on one body, a physical hit spends the four first: it is
     * the barrier that can <em>only</em> absorb that half, so spending it first leaves the shared pool covering
     * magic for longer.
     */
    @Test
    void theSpecificChannelPaysBeforeTheSharedOne() {
        ShieldState state = typed(ShieldKind.BOTH, 10.0F).granted(ShieldKind.PHYS, 4.0F, 0L, 160)
                .written(14.0F, 0.0F, 0.0F);
        ShieldState afterHit = state.reconciledAgainst(9.0F, LIVE);   // 5 of physical
        assertEquals(0.0F, layerPool(afterHit, ShieldKind.PHYS), 1e-6, "the four paid first");
        assertEquals(9.0F, layerPool(afterHit, ShieldKind.BOTH), 1e-6, "and the shared pool paid one");
    }

    /**
     * <b>A parked hit leaves the channel it cannot use untouched</b>, however big the hit is.
     *
     * <p>The stale-shield defect in mirror image: without parking, a magic bolt bigger than the shared barrier
     * would have spent the physical four as well, which is exactly the 「不分类」 defect the two channels fix.
     */
    @Test
    void aParkedHitLeavesTheOtherChannelUntouched() {
        ShieldState state = typed(ShieldKind.BOTH, 10.0F).granted(ShieldKind.PHYS, 4.0F, 0L, 160)
                .written(14.0F, 0.0F, 0.0F);
        ShieldState afterHugeMagicHit = parked(state, true).reconciledAgainst(0.0F, LIVE);
        assertEquals(4.0F, layerPool(afterHugeMagicHit, ShieldKind.PHYS), 1e-6,
                "the physical barrier did not pay the bolt");
        assertEquals(0.0F, layerPool(afterHugeMagicHit, ShieldKind.BOTH), 1e-6,
                "the shared pool paid all ten, and the rest of the bolt reached health");
        assertEquals(ShieldKind.ALL, afterHugeMagicHit.visibleKinds(), "and every layer is back in the float");
    }

    // ------------------------------------------------------------------ replace, not accumulate

    /**
     * Two grants in a row <b>do not add</b>: the second replaces the first.
     *
     * <p>This test asserted {@code 8.0F} and its name said 「add」. It was pinning the defect the human then hit
     * in game — 「有个护盾效果能无限叠加，我不断挥砍能不断叠加额外生命值」.
     */
    @Test
    void aSecondGrantReplacesTheFirst() {
        float one = ShieldPool.granted(0.0F, 4.0F);
        float two = ShieldPool.granted(one, 4.0F);
        assertEquals(4.0F, two, 1e-6, "the second grant is the shield, not a second helping of it");
    }

    /** <b>Ten grants are worth one shield — and they still cannot accumulate on top of somebody else's share.</b> */
    @Test
    void repeatedGrantsDoNotAccumulate() {
        ShieldState state = ShieldState.NONE;
        for (int swing = 0; swing < 10; swing++) {
            // Before the first swing only the other source's six are on the entity; afterwards ten.
            float observed = swing == 0 ? FOREIGN : FOREIGN + OURS;
            ShieldState reconciled = state.reconciledAgainst(observed, swing);
            ShieldState next = reconciled.granted(ShieldKind.BOTH, OURS, swing, 160);
            float theirs = next.foreignShare(observed, 0.0F);
            assertEquals(FOREIGN, theirs, 1e-6, "their six survive swing " + swing);
            state = next.written(next.totalWith(theirs), theirs, 0.0F);
        }
        assertEquals(OURS, state.total(), 1e-6, "a fast weapon must not grow the shield");
        assertEquals(FOREIGN + OURS, state.lastWritten(), 1e-6,
                "and ten swings leave the same ten points of absorption, not forty");
    }

    /**
     * A grant <b>replaces</b> its layer and sets its own deadline; the later grant wins on both counts.
     */
    @Test
    void aGrantReplacesThePoolAndSetsItsOwnDeadline() {
        ShieldState first = ShieldState.NONE.granted(ShieldKind.BOTH, 4.0F, 0L, 160);
        ShieldState second = first.granted(ShieldKind.BOTH, 4.0F, 50L, 160);
        assertEquals(4.0F, second.total(), 1e-6, "the second grant replaces the first, it does not add to it");
        assertEquals(210L, second.expiresAtTick(), "the second cast's clock, not the first's");
    }

    // ------------------------------------------------------------------ hostile input and the edges

    /** A grant larger than the ceiling is clamped, so the mirror can always show what we hold. */
    @Test
    void thePoolIsCappedAtTheAttributesCeiling() {
        assertEquals(2048.0F, ShieldPool.granted(0.0F, 4000.0F), 1e-3,
                "a pool beyond 2048 could never be mirrored, so the player would see less than we hold");
        assertEquals(2048.0F, ShieldPool.total(2000.0F, 1000.0F), 1e-3, "and neither can the sum");
    }

    /** Hostile input is flattened rather than poisoning every later sum. */
    @Test
    void hostileInputIsFlattened() {
        assertEquals(4.0F, ShieldPool.total(Float.NaN, 4.0F), 1e-6,
                "a NaN pool is no shield of ours, but the other four hearts are really there, so they show");
        assertEquals(0.0F, ShieldPool.total(-3.0F, -4.0F), 1e-6);
        assertEquals(4.0F, ShieldPool.total(-3.0F, 4.0F), 1e-6);
        assertEquals(0.0F, ShieldPool.granted(0.0F, Float.NaN), 1e-6);
        assertEquals(0.0F, ShieldPool.granted(0.0F, -5.0F), 1e-6);
        assertEquals(0.0F, ShieldPool.lossSince(Float.NaN, 4.0F), 1e-6, "no write, so no loss");
        assertEquals(10.0F, ShieldPool.lossSince(10.0F, Float.NaN), 1e-6,
                "a NaN float reads as zero absorption, so all ten of ours were spent");
        assertEquals(4.0F, ShieldPool.foreignShare(Float.NaN, 0.0F, 0.0F, 4.0F, 0.0F), 1e-6,
                "a NaN float does not lose vanilla's four");
        assertEquals(0.0F, ShieldPool.foreignShare(Float.NaN, 0.0F, Float.NaN, -1.0F, Float.NaN), 1e-6,
                "and nothing hostile can invent a share");
        assertFalse(ShieldPool.isGrantable(0.0F));
        assertFalse(ShieldPool.isGrantable(Float.NaN));
        assertTrue(ShieldPool.isGrantable(0.5F));
        // A hostile layer is dropped rather than summed, so nothing hostile can inflate the total.
        assertEquals(0.0F, new ShieldState(java.util.List.of(new ShieldLayer(ShieldKind.PHYS, Float.NaN, 99L)),
                0.0F, 0.0F, 0.0F, ShieldKind.ALL).total(), 1e-6);
    }

    /** A null entity answers zero rather than throwing, because every caller is on a damage path. */
    @Test
    void aMissingEntityReadsAsZero() {
        assertEquals(0.0F, ShieldPool.absorptionOf(null), 1e-6);
        assertEquals(0.0F, ShieldPool.vanillaShareOf(null), 1e-6);
        assertEquals(ShieldState.NONE, Shields.stateOf(null), "and the ledger read is null-safe too");
    }

    /** {@link ShieldState} answers {@code NONE} once its deadline has passed — lazily, with no listener. */
    @Test
    void anExpiredStateIsNone() {
        ShieldState state = ShieldState.NONE.granted(ShieldKind.BOTH, 4.0F, 100L, 160);
        assertTrue(state.isPresent());
        assertEquals(4.0F, state.total(), 1e-6);
        assertEquals(4.0F, state.reconciledAgainst(4.0F, 200L).total(), 1e-6, "still live at tick 200");
        assertEquals(ShieldState.NONE, state.reconciledAgainst(4.0F, 260L),
                "the deadline is in the value, so every reader is correct with no removal event");
    }

    /**
     * <b>A stale pool must not eat a later golden apple.</b>
     *
     * <p>The defect the deadline exists to prevent: a pool of 10 that outlived its effect would be reconciled
     * against a later apple's float and claim those hearts as its own.
     */
    @Test
    void aStalePoolDoesNotClaimALaterGoldenApple() {
        ShieldState expired = new ShieldState(java.util.List.of(new ShieldLayer(ShieldKind.BOTH, 10.0F, 100L)),
                10.0F, 0.0F, 0.0F, ShieldKind.ALL);
        assertEquals(ShieldState.NONE, expired.reconciledAgainst(APPLE, 500L),
                "the pool is past its deadline, so the four hearts are vanilla's, not ours");
    }

    /** A negative or {@code NaN} amount is no layer at all, not a shield that cannot be drawn. */
    @Test
    void aHostilePoolIsZero() {
        assertEquals(0.0F, new ShieldState(java.util.List.of(), -5.0F, 0.0F, 0.0F, ShieldKind.ALL).total(), 1e-6);
        assertEquals(0.0F, new ShieldState(java.util.List.of(new ShieldLayer(ShieldKind.BOTH, Float.NaN, 0L)),
                0.0F, 0.0F, 0.0F, ShieldKind.ALL).total(), 1e-6);
        assertEquals(0.0F, new ShieldState(java.util.List.of(), 4.0F, Float.NaN, -2.0F, ShieldKind.ALL).foreign(),
                1e-6, "a hostile ledger cannot invent somebody else's share either");
        assertFalse(new ShieldState(java.util.List.of(), 0.0F, 0.0F, 0.0F, ShieldKind.ALL).isPresent());
    }

    /** {@return the amount the layer of {@code kind} holds, or {@code 0} when there is none} */
    private static float layerPool(ShieldState state, ShieldKind kind) {
        for (ShieldLayer layer : state.layers()) {
            if (layer.kind() == kind) {
                return layer.pool();
            }
        }
        return 0.0F;
    }
}