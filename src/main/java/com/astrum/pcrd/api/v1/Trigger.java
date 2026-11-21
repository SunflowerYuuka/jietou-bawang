package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import java.util.Locale;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.ApiStatus;

/**
 * Event that can attach an {@link EffectHook} to a character.
 */
@ApiStatus.Experimental
public enum Trigger implements StringRepresentable {
    MELEE_HIT,
    CRIT,
    PROJECTILE_HIT,
    KILL,
    LOW_HEALTH,
    COMBO_N,
    BLOCK,
    STACK_FULL,

    /**
     * <b>「战斗开始」</b>: the first blow of a fight — 雪菲's 特殊技能1 「获得4个【冰龙之印】」, and the reason
     * every character's EX was recorded as unfireable.
     *
     * <h2>What "a fight begins" means here</h2>
     * The fact is true on <b>exactly one hit per fight</b>: the first blow the player <em>lands</em> after a gap
     * of {@code TriggerRuntime.BATTLE_START_WINDOW_TICKS} (600 ticks, 30 s) since the last blow she landed.
     * Continuous combat therefore never re-fires it — a trigger that fired every N seconds would be a buff on a
     * timer, not a fight's opening. The boundary is the player's <b>own</b> blows, because that is the only
     * moment the hook pipeline runs at all: the event this rides is the same landed hit that already evaluates
     * {@link #MELEE_HIT} / {@link #PROJECTILE_HIT}, so a {@code battle_start} hook sees a real target and runs in
     * the character's own list order (a grant written before a skill's spend is therefore already in the tally
     * when that skill reads it).
     *
     * <h2>Why not login, respawn, or a dimension change</h2>
     * All three are moments the player experiences, and none of them is a fight. Login is the worst of the three
     * — the marks an effect grants are saved with the player, so a logout/login would refill a tally that was
     * meant to last one engagement — and a death would hand out a fresh grant with no combat at all. A session
     * is not a battle in Minecraft: a player builds, walks, and fights several times inside one session, so the
     * honest boundary is the combat itself.
     *
     * <h2>What a player can farm with it</h2>
     * Standing still for the window and then landing another blow counts as a new fight, i.e. another grant. That
     * is deliberate and bounded: the grant goes into the counter's own tally, whose cap is the rung's
     * ({@code Counters.capForTier}), and it expires with the effect instance. So the worst case is "somebody
     * pauses the fight to top up a mark tally", never an unbounded resource, and the trigger itself deals no
     * damage.
     *
     * <h2>The state, and why there is no tick listener</h2>
     * One runtime-only per-player attachment ({@code Attachments.BATTLE_START}, {@code serialize = false})
     * holds the tick of the last blow; the fight "ends" when the next blow compares its tick against that one,
     * not when a timer fires. That is the same lazy-state pattern {@link #COMBO_N}'s chain and the stack table
     * use, and it is why an idle player costs nothing.
     */
    BATTLE_START,

    /**
     * <b>「大招命中时」</b>: one body an ultimate's damage <b>actually landed on</b> — the trigger that lets a
     * UB carry a debuff on the enemy.
     *
     * <h2>Why an ultimate needs one</h2>
     * An {@link EffectHook} hangs off a trigger, and an ultimate has none: it fires on a key press and
     * settles its own victims. Before this value a UB could only express <em>tiered</em> effects through
     * {@code UbEffect}, and {@code apply_effect} (the one way to put a vanilla debuff such as
     * {@code minecraft:weakness} on a body) had nowhere to go — which is why 未奏希's 6★
     * 「降低敌方全体物理攻击力 18 秒」 was recorded as unimplemented rather than silently dropped.
     *
     * <h2>When it is observed, and for whom</h2>
     * The ultimate's settlement fires {@code ub.landHooks} for it <b>once per body a hit landed on</b>, after
     * {@code hurt()} answered true — the same gate the tier's victim effects and the burst use, so a target
     * that dodged, was immune or walked out of reach leaves no debuff behind. That is deliberately the same
     * shape a {@code melee_hit} hook has (one evaluation per body a swing hits): an ultimate is not one hit
     * that reaches many bodies, it is many hits, and a debuff clause's text says 「受到伤害的目标」.
     *
     * <p>It is <b>not</b> observed on a landed swing, a kill or a block. The hook lists live in
     * {@code ub.landHooks} rather than in {@code effects[]}, and {@code UbHooks.mismatch()} refuses any hook
     * there whose trigger is not this value, so a pack cannot declare a UB clause that never fires.
     */
    UB_LAND;

    public static final Codec<Trigger> CODEC = StringRepresentable.fromEnum(Trigger::values);

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
