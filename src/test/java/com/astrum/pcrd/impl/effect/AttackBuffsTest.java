package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.impl.combat.AllyMode;
import com.astrum.pcrd.impl.combat.AllySelector;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ultimate's attack buff reaches the <b>party</b>, not just the caster — and the reach rule is the
 * one that already existed.
 *
 * <h2>Why this changed</h2>
 * The wiki's 「特大幅度提升<em>自己</em>的物理攻击」 was read as a strictly self buff here, and the code
 * said so in a javadoc that argued the point. The game disagrees: verified against gamewith's 星河一天
 * entry, the party-wide attack boost is <b>part of the ultimate itself</b> rather than a CHAIN effect,
 * i.e. an area effect in the game's own terms — 「游戏里也就是范围内的效果」. So the receiver set is the
 * caster <em>plus every ally inside the ultimate's effect radius</em>.
 *
 * <h2>Why this test has no {@code ServerPlayer} in it</h2>
 * {@link AttackBuffs#receivers} is the whole receiver rule, expressed over {@link AllySelector}'s plain
 * candidate data. That is the point: "a caster plus two allies inside the radius all gain it, one
 * outside gains nothing" is exactly the question, and asking it of a live world would have made it
 * unassertable — which is how a caster-only implementation survived a green suite in the first place.
 *
 * <p>The one thing deliberately <b>not</b> re-implemented here is the ally test itself: every case
 * below goes through the same kernel {@code area_charge} uses, so a change to
 * {@code multiplayer.allyMode} or to {@link AllySelector} moves both features together, as it must.
 */
class AttackBuffsTest {

    private static final UUID CASTER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID NEAR_ONE = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID NEAR_TWO = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID FAR = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    /** A caster at 0, two allies at 3 and 6, one at 40 — all team-less, i.e. the LAN case. */
    private static List<AllySelector.Candidate> party() {
        return List.of(
                new AllySelector.Candidate(CASTER, true, Optional.empty(), 0.0),
                new AllySelector.Candidate(NEAR_ONE, false, Optional.empty(), 3.0),
                new AllySelector.Candidate(NEAR_TWO, false, Optional.empty(), 6.0),
                new AllySelector.Candidate(FAR, false, Optional.empty(), 40.0));
    }

    private static List<UUID> ids(List<AllySelector.Candidate> candidates) {
        return candidates.stream().map(AllySelector.Candidate::id).toList();
    }

    /**
     * The headline promise: a caster and two allies inside the radius all gain the buff; the one outside
     * gains nothing.
     *
     * <p>Breaks if: the receiver rule becomes caster-only again (2 of the 4 ids vanish), the radius
     * comparison loses its inclusivity (<em>not</em> caught here, see
     * {@link #theBoundaryItselfIsInside}), or {@code includeSelf} is dropped (the caster vanishes).
     */
    @Test
    void aCasterAndTwoAlliesInRangeAllGainItAndOneOutsideGainsNothing() {
        List<UUID> receivers = ids(AttackBuffs.receivers(party(), CASTER,
                AllyMode.ALL_PLAYERS, AttackBuffs.PARTY_RADIUS_DEFAULT));

        assertEquals(3, receivers.size(), "施法者 + 范围内两名队友 = 3 人，范围外的不算");
        assertTrue(receivers.contains(CASTER), "自己 must be in the set — the text is 自己 AND the party");
        assertTrue(receivers.contains(NEAR_ONE));
        assertTrue(receivers.contains(NEAR_TWO));
        assertFalse(receivers.contains(FAR), "40 格外的队友不得获得 buff");
    }

    /** The boundary is inclusive, because {@code AllySelector} says so and this must not differ. */
    @Test
    void theBoundaryItselfIsInside() {
        List<AllySelector.Candidate> exactly = List.of(
                new AllySelector.Candidate(CASTER, true, Optional.empty(), 0.0),
                new AllySelector.Candidate(NEAR_ONE, false, Optional.empty(), 8.0));
        assertEquals(2, ids(AttackBuffs.receivers(exactly, CASTER, AllyMode.ALL_PLAYERS, 8.0)).size(),
                "边界上的队友算在范围内，与 AllySelector 的 inclusive 比较一致");
        assertEquals(1, ids(AttackBuffs.receivers(exactly, CASTER, AllyMode.ALL_PLAYERS, 7.99)).size(),
                "7.99 格就够不到了");
    }

    /**
     * A solo world degrades to the old behaviour rather than to no buff at all — the reason
     * {@code includeSelf} is {@code true} and not a parameter.
     */
    @Test
    void aWorldWithNobodyElseStillBuffsTheCaster() {
        List<AllySelector.Candidate> alone = List.of(
                new AllySelector.Candidate(CASTER, true, Optional.empty(), 0.0));
        assertEquals(List.of(CASTER), ids(AttackBuffs.receivers(alone, CASTER,
                AllyMode.ALL_PLAYERS, AttackBuffs.PARTY_RADIUS_DEFAULT)));
    }

    /** A radius of zero is the documented way to write a self-only buff; it reaches exactly herself. */
    @Test
    void aZeroRadiusIsASelfOnlyBuff() {
        assertEquals(List.of(CASTER), ids(AttackBuffs.receivers(party(), CASTER,
                AllyMode.ALL_PLAYERS, 0.0)));
    }

    /**
     * The policy is {@code AllySelector}'s, so a table with no teams is not treated as a table of
     * strangers — the §7.4 trap, asserted here because this feature now depends on it too.
     */
    @Test
    void teamlessAlliesAreStillAllies() {
        // Far enough that distance cannot be the deciding factor: only the policy differs.
        assertEquals(4, ids(AttackBuffs.receivers(party(), CASTER, AllyMode.ALL_PLAYERS, 100.0)).size(),
                "ALL_PLAYERS：无队伍的邻居也是队友，全部 4 人选中");
        assertEquals(1, ids(AttackBuffs.receivers(party(), CASTER, AllyMode.SAME_TEAM_ONLY, 100.0))
                .size(), "同一份数据换成 SAME_TEAM_ONLY 就只剩自己 —— 队伍策略确实是共用的内核在判");
    }

    /** And {@code SAME_TEAM_ONLY} still means what it says, through the same kernel. */
    @Test
    void sameTeamOnlyStillFilters() {
        List<AllySelector.Candidate> teams = List.of(
                new AllySelector.Candidate(CASTER, true, Optional.of("red"), 0.0),
                new AllySelector.Candidate(NEAR_ONE, false, Optional.of("blue"), 3.0),
                new AllySelector.Candidate(NEAR_TWO, false, Optional.of("red"), 3.0));
        assertEquals(List.of(CASTER, NEAR_TWO),
                ids(AttackBuffs.receivers(teams, CASTER, AllyMode.SAME_TEAM_ONLY, 8.0)));
    }

    /**
     * {@code radiusFor} is the declared-else-default rule, written once.
     *
     * <p>Three cases, and the third is the one worth arguing: a <b>nonsense</b> radius on a buff falls
     * back to the party default instead of buffing nobody, because a buff that silently reaches nobody
     * has no symptom while an over-wide one is visible and correctable. That is the opposite of
     * {@code UbDamage.plan}'s reading of a nonsense <em>damage</em> radius, where "reaches nobody" is
     * the correct fail-closed answer — the two are different questions.
     */
    @Test
    void theRadiusIsTheDeclaredOneElseTheNamedDefault() {
        assertEquals(AttackBuffs.PARTY_RADIUS_DEFAULT, AttackBuffs.radiusFor(OptionalDouble.empty()),
                "未声明 = 常量默认值，而不是某个字面量");
        assertEquals(12.5, AttackBuffs.radiusFor(OptionalDouble.of(12.5)), "声明了就用声明的");
        assertEquals(0.0, AttackBuffs.radiusFor(OptionalDouble.of(0.0)),
                "0 是合法的『只加自己』，不是错误");
        assertEquals(AttackBuffs.PARTY_RADIUS_DEFAULT, AttackBuffs.radiusFor(OptionalDouble.of(-3.0)),
                "负数不是半径：回退到默认并 WARN，而不是静默地谁都不加");
        assertEquals(AttackBuffs.PARTY_RADIUS_DEFAULT,
                AttackBuffs.radiusFor(OptionalDouble.of(Double.NaN)));
        assertEquals(AttackBuffs.PARTY_RADIUS_DEFAULT, AttackBuffs.radiusFor(null));
    }

    /**
     * The default is a party <em>spacing</em>, not a sword length: melee reach would only ever reach the
     * caster, and the whole point of this change is that it does not.
     */
    @Test
    void theDefaultIsWiderThanMeleeReach() {
        assertTrue(AttackBuffs.PARTY_RADIUS_DEFAULT >= 8.0,
                "队友在打架时通常站得比 3 格近战距离远：" + AttackBuffs.PARTY_RADIUS_DEFAULT);
    }

    /** A receiver set is never empty when the caster is a candidate; a null-ish list degrades safely. */
    @Test
    void degenerateInputsAnswerAnEmptySetRatherThanThrowing() {
        assertEquals(List.of(), AttackBuffs.receivers(null, CASTER, AllyMode.ALL_PLAYERS, 8.0));
        assertEquals(List.of(), AttackBuffs.receivers(party(), null, AllyMode.ALL_PLAYERS, 8.0));
        assertEquals(List.of(), AttackBuffs.receivers(party(), CASTER, AllyMode.ALL_PLAYERS, Double.NaN));
    }

    /**
     * The buff's numbers travel as a ratio and a duration, and the amplifier encoding is exact for the
     * shipped one — the two facts a party-wide application must not change.
     */
    @Test
    void theShippedBuffIsRepresentableExactly() {
        assertTrue(AttackBuffRules.isRepresentable(1.5), "1.5 -> +50%，amplifier 50，精确");
        assertEquals(50, AttackBuffRules.amplifierFor(1.5));
        assertEquals(18.0, AttackBuffRules.ticksFor(18.0) / (double) AttackBuffRules.TICKS_PER_SECOND,
                "18 秒仍是 360 tick：宽度改了，时长不该改");
        assertNotEquals(0, AttackBuffRules.amplifierFor(1.1),
                "技能2 的 +10% 也要能表达，否则 attack_buff 会被 WARN 成 0");
    }
}
