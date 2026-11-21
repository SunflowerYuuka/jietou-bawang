package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.attach.ShieldKind;
import com.astrum.pcrd.impl.attach.ShieldLayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * How our shield <b>coexists with vanilla's absorption</b> instead of being cancelled by it.
 *
 * <h2>The requirement, in the human's words</h2>
 * 「做成类似MC原版金苹果效果的那个生命吸收，但是两者可以叠加」 — make it like vanilla's golden-apple
 * absorption, but <b>the two stack</b>.
 *
 * <h2>Why vanilla cannot do this, measured from the 1.21.1 sources</h2>
 * Absorption is <b>one float</b> on the entity: {@code LivingEntity.absorptionAmount}, read through
 * {@code getAbsorptionAmount()} and written through {@code setAbsorptionAmount(float)}. There is no
 * per-source pool, no list of shields, and nothing that adds. Three places therefore destroy a second shield,
 * and each needs its own answer:
 *
 * <ol>
 *   <li><b>{@code AbsorptionMobEffect.onEffectStarted}</b> does
 *       {@code setAbsorptionAmount(max(current, 4 * (1 + amplifier)))} — a <b>max</b>. A golden apple drunk
 *       while our shield is up would leave the float at the apple's own four points where the sum should be.</li>
 *   <li><b>{@code LivingEntity.onAttributeUpdated}</b> clamps the float down whenever
 *       {@code MAX_ABSORPTION} falls ({@code if (getAbsorptionAmount() > f1) setAbsorptionAmount(f1)}).
 *       Vanilla's effect carries an {@code ADD_VALUE +4/level} modifier on that attribute, so <b>the apple
 *       expiring</b> lowers the ceiling and would clamp away a shield that lived only in the float.</li>
 *   <li><b>Damage</b> eats the float before health ({@code actuallyHurt}:
 *       {@code setAbsorptionAmount(max(0, amount - absorbed))}), which is the behaviour we want — but it
 *       means the float is not a stable place to remember how much of it was ours.</li>
 * </ol>
 *
 * <h2>The design: our pool stored, <em>everyone else's</em> share derived from the float</h2>
 * Our pool is remembered separately (the {@code astrum:shield_pool} attachment). The rest of the float belongs
 * to whomever put it there, and the only rule that can cover <b>an unknown mod</b> is the one that never names
 * it: what the float holds beyond our own share is theirs. {@link #foreignShare} is that rule —
 *
 * <pre>
 *   theirs = max(0, observed - ours)          // whoever they are: vanilla, another mod, both
 *   write  = ours + theirs
 * </pre>
 *
 * <p>Two corrections are needed on top of that line, and both come from the float's one real limitation — a
 * writer that does not add is invisible:
 *
 * <ul>
 *   <li><b>vanilla's {@code max()} write</b> — a golden apple drunk on top of our shield leaves the float at
 *       its own four points, so the four it granted are <em>not</em> visible as a difference and
 *       {@code max(0, observed - ours)} would answer zero. Vanilla's share is therefore also read off its
 *       effect ({@link #vanillaShareOf}, exactly {@code 4 × (1 + amplifier)}), and the larger of the two
 *       readings wins: {@code theirs = max(vanillaShare, visible beyond ours)}. That is the case that already
 *       worked and must keep working;</li>
 *   <li><b>a source that goes away</b> — when the apple's effect ends, the ceiling and the grant are gone but
 *       the float may still hold its four until somebody writes again, and the residue is indistinguishable
 *       from a mod that just granted four. The ledger therefore remembers {@code vanillaSeen}, the vanilla
 *       share it last accounted for: if that share falls, exactly the fall is subtracted from theirs rather
 *       than being re-claimed as a new foreign grant.</li>
 * </ul>
 *
 * <p>What the float still cannot express, honestly: a foreign writer that uses {@code setAbsorptionAmount} to
 * put the float <b>below</b> what it held is indistinguishable from damage, and damage is documented to be
 * paid by our shield first — so such a write-down also spends our shield. And a foreign grant that a mod makes
 * with {@code max()} while our shield is up is invisible in exactly the way vanilla's is, so it is preserved
 * only to the extent the mod's own effect can be read; only vanilla's can be, which is why
 * {@link #vanillaShareOf} is the one formula restated here rather than a general mechanism. See
 * {@code ShieldState} for the numbers that make the derivation possible across a grant.
 *
 * <h2>Why the arithmetic is static functions over floats</h2>
 * The attachment read/write and the attribute modifier need a live entity, and this project cannot see a
 * screen. So the sums, the reconciliation, the vanilla-share formula and the expiry rule are written as
 * static functions over floats — {@code ShieldPoolTest} asserts the four cases above without a world.
 */
@ApiStatus.Internal
public final class ShieldPool {

    /** Registry path of the modifier that keeps {@code MAX_ABSORPTION} high enough: {@code astrum:shield}. */
    public static final String MODIFIER_PATH = "shield";

    /**
     * The absorption vanilla's {@code absorption} effect grants at {@code amplifier}.
     *
     * <p>{@code 4 × (1 + amplifier)} — the same expression {@code AbsorptionMobEffect.onEffectStarted}
     * evaluates (read from the 21.1.265 source: {@code setAbsorptionAmount(Math.max(current, 4 * (1 +
     * amplifier)))}). Restated here rather than called, because vanilla's method is private and its class
     * package-private; the constant 4 is vanilla's own {@code ADD_VALUE} per level and the reason a golden
     * apple gives four hearts.
     *
     * @param amplifier the instance's amplifier, or {@code -1} when the entity has no absorption effect
     */
    public static float vanillaShare(int amplifier) {
        if (amplifier < 0) {
            return 0.0F;
        }
        return 4.0F * (1.0F + amplifier);
    }

    private ShieldPool() {
    }

    /**
     * {@return the total absorption to show, from our pool and vanilla's share}
     *
     * <p>The plain sum, clamped at zero and at vanilla's own hard ceiling. Written out as a named function
     * rather than an inline {@code +} so the requirement ("the two stack") has an artefact a test can point at.
     *
     * @param ours    what our shield holds
     * @param theirs  what vanilla's absorption contributes
     */
    public static float total(float ours, float theirs) {
        double sum = clean(ours) + clean(theirs);
        if (!(sum > 0.0) || !Double.isFinite(sum)) {
            return 0.0F;
        }
        return (float) Math.min(sum, 2048.0);   // MAX_ABSORPTION's own ceiling
    }

    /**
     * {@return what damage has taken away since this mod last wrote the float}
     *
     * <h2>Why the measurement is a <em>difference</em> and not a share</h2>
     * This is the fix for the bug the dedicated-server probe measured
     * ({@code [T35] FAIL our pool survived vanilla's max() write: 0.0}). Vanilla's {@code absorption} writes
     * the float with {@code max(current, grant)}, so a golden apple drunk on top of our shield leaves the float
     * at its own four points — <b>the sum is not visible and never was</b>. Any rule of the shape
     * 「our part is what the float holds beyond vanilla's」 therefore reads zero, and declares our shield gone.
     *
     * <p>What the float <em>can</em> say is how much it has <b>fallen</b> since we last wrote it, and that is
     * exactly the damage taken. So the loss is {@code max(0, lastWritten - observed)} — never negative (a
     * vanilla write that raised the float is not a refund) — and our pool pays it first.
     *
     * @param lastWritten   the float this mod last wrote
     * @param observedTotal the entity's absorption float right now
     */
    public static float lossSince(float lastWritten, float observedTotal) {
        float last = clean(lastWritten);
        float seen = clean(observedTotal);
        return Math.max(0.0F, last - seen);
    }

    /**
     * {@return what our pool should become after {@code amount} of shield is granted}
     *
     * <p><b>The new shield REPLACES the old one; it does not add to it.</b> The additive version shipped and
     * the human broke it in a minute: holding down attack re-fired the shield every swing, so a fast weapon
     * piled the yellow hearts up without limit — 「有个护盾效果能无限叠加，我不断挥砍能不断叠加额外生命值」. A
     * shield is a state, not a resource: re-applying it restores it to what the cast is worth.
     *
     * <p>Replace, not {@code max}: he asked for 「覆盖」 in as many words. A weaker cast therefore does shorten
     * a stronger shield, which is the literal reading and the one a tiered ladder makes survivable — the same
     * skill always resolves to the same tier for the same character.
     *
     * <p>Capped at vanilla's {@code MAX_ABSORPTION} hard ceiling of 2048: a pool beyond it could never be
     * mirrored and the player would be shown less than the mod thinks they have.
     *
     * @param pool   our pool now; only consulted to decide whether anything is granted at all
     * @param amount shield the cast is worth; non-positive grants nothing and keeps the pool
     */
    public static float granted(float pool, float amount) {
        float mine = clean(pool);
        float next = clean(amount);
        if (!(next > 0.0F)) {
            return mine;
        }
        return (float) Math.min(2048.0, next);
    }

    /**
     * {@return whether granting {@code amount} would be visible at all}
     *
     * <p>One answer for the executor's "is there anything to do" question, so a zero or {@code NaN} tier
     * computation is a logged no-op instead of an effect that applies nothing and says it succeeded.
     *
     * @param amount shield to grant
     */
    public static boolean isGrantable(float amount) {
        return amount > 0.0F && Float.isFinite(amount);
    }

    /**
     * {@return everyone else's share of the float — <b>ours and only ours are excluded</b>}
     *
     * <h2>Why this is the shape of the whole fix</h2>
     * The defect it removes: the mirror used to write {@code ours + vanillaShareOf(entity)}, so the only other
     * source it could see was the golden apple. Every other mod's absorption — anything that called
     * {@code setAbsorptionAmount} itself, or that vanilla's effect does not model — was <b>replaced</b> by our
     * number on the next mirror, because {@link #vanillaShareOf} simply did not know about it. Deriving the rest
     * from the float instead means a source the code has never heard of is preserved by construction: it is not
     * named, it is not asked, it is just the part of the float that is not ours.
     *
     * <h2>The three terms</h2>
     * <ol>
     *   <li><b>a foreign grant is the rise of the float</b> — measured against {@code lastWritten}, not against
     *       our pool, so a grant that <em>replaces</em> our share cannot make our own new, larger pool look
     *       like somebody else's absorption:
     *       <pre>
     *         risen  = max(0, observed - lastWritten)      // somebody wrote the float UP since our write
     *         theirs = foreignSeen + risen                 // a foreign grant is exactly that rise
     *       </pre>
     *       {@code ShieldState#reconciledAgainst} already folds this rise in — that is the read that moves
     *       {@code lastWritten}, so a mirror running after it sees zero here. The term stays because the mirror
     *       is public and must not lose a rise handed to it unreconciled.</li>
     *   <li><b>vanilla's invisible grant</b> — {@code max(vanillaNow, …)}, because a golden apple's
     *       {@code max()} write adds nothing the float can show (the case pinned by
     *       {@code ShieldPoolTest.aGoldenAppleAfterOurShieldDoesNotReduceTheTotal});</li>
     *   <li><b>a source that vanished</b> — {@code max(0, vanillaSeen - vanillaNow)} subtracted from
     *       {@code foreignSeen}, so the apple's four are not re-claimed as a foreign mod's four after its
     *       effect ends while the float still holds them.</li>
     * </ol>
     *
     * <p>Damage is <b>not</b> handled here: {@link #lossSince} already spends our shield first, and
     * {@code ShieldState#reconciledAgainst} carries the overflow into {@code foreignSeen} before this runs.
     *
     * @param observedTotal the entity's absorption float right now
     * @param lastWritten   the float this mod last wrote or last measured
     * @param foreignSeen   the share of that float the ledger attributes to everybody else
     * @param vanillaNow    what vanilla's {@code absorption} contributes now, from {@link #vanillaShareOf}
     * @param vanillaSeen   what vanilla's {@code absorption} contributed when the ledger last accounted for it
     */
    public static float foreignShare(float observedTotal, float lastWritten, float foreignSeen,
            float vanillaNow, float vanillaSeen) {
        float observed = clean(observedTotal);
        float last = clean(lastWritten);
        float seen = clean(foreignSeen);
        float vanilla = clean(vanillaNow);
        float vanished = Math.max(0.0F, clean(vanillaSeen) - vanilla);
        float theirs = Math.max(0.0F, seen - vanished);
        theirs += Math.max(0.0F, observed - last);
        if (!Float.isFinite(theirs)) {
            theirs = 0.0F;
        }
        return Math.max(vanilla, theirs);
    }

    /**
     * {@return the absorption to write so the HUD shows {@code ours + theirs}}
     *
     * <p>Also the function {@code ShieldEffect}'s tick calls, so a vanilla re-application (a second golden
     * apple, an expiry clamp) is repaired on the next tick rather than staying wrong. That tick is the
     * <b>effect's own</b> {@code applyEffectTick}, not a listener: no new tick source is introduced.
     *
     * @param ours   our pool
     * @param theirs everyone else's share, from {@link #foreignShare}
     */
    public static float mirror(float ours, float theirs) {
        return total(ours, theirs);
    }

    /**
     * {@return vanilla's absorption contribution for {@code entity}, or zero when it has none}
     *
     * <p>The one place the game is asked, so every caller agrees about how much of the float is vanilla's.
     * A missing entity, a client-side body and a body without the effect all answer zero — which is the safe
     * reading: "nothing of vanilla's" means our pool is the whole float, and the mirror leaves it alone.
     *
     * @param entity the body to ask, possibly {@code null}
     */
    public static float vanillaShareOf(@Nullable LivingEntity entity) {
        if (entity == null) {
            return 0.0F;
        }
        try {
            MobEffectInstance absorption = entity.getEffect(MobEffects.ABSORPTION);
            return absorption == null ? 0.0F : vanillaShare(absorption.getAmplifier());
        } catch (RuntimeException e) {
            Astrum.LOGGER.warn("astrum: could not read vanilla absorption; treating it as none for this "
                    + "read", e);
            return 0.0F;
        }
    }

    /**
     * {@return {@code entity}'s absorption float}, or {@code 0} for a missing entity
     *
     * <p>One null-safe read, because every caller here runs inside a damage or cast sequence where an
     * exception would cost the rest of the wheel.
     *
     * @param entity the entity to read
     */
    public static float absorptionOf(@Nullable LivingEntity entity) {
        if (entity == null) {
            return 0.0F;
        }
        try {
            return entity.getAbsorptionAmount();
        } catch (RuntimeException e) {
            Astrum.LOGGER.warn("astrum: could not read absorption; treating it as zero for this read", e);
            return 0.0F;
        }
    }

    /**
     * {@return the absorption the layers in {@code mask} hold together}
     *
     * <p>The typed split's only sum: a hit of one damage type can reach exactly the layers that cover it, so
     * "how much can this hit absorb" is this function over the same list with a narrower mask. Nothing is
     * decided here — which layers cover which half is {@link ShieldKind#covers}.
     *
     * @param layers the ledger's layers
     * @param mask   which kinds to include, from {@link ShieldKind#maskOf}
     */
    public static float totalOf(@Nullable List<ShieldLayer> layers, int mask) {
        if (layers == null || layers.isEmpty() || mask == 0) {
            return 0.0F;
        }
        double sum = 0.0;
        for (ShieldLayer layer : layers) {
            if (layer != null && (mask & layer.kind().mask()) != 0) {
                sum += clean(layer.pool());
            }
        }
        if (!(sum > 0.0) || !Double.isFinite(sum)) {
            return 0.0F;
        }
        return (float) Math.min(sum, 2048.0);
    }

    /**
     * {@return {@code layers} with {@code loss} taken out of the layers in {@code mask}, in list order}
     *
     * <h2>Why the order is the list's and not the loss's owner's claim</h2>
     * When two layers both cover a hit — 由衣's physical barrier and 望's shared one, on one player — something
     * has to say which one pays first. It is the <b>specific channel before the shared pool</b>, which is the
     * order {@code ShieldState} stores its layers in: the barrier that can only absorb this half is spent
     * first, so the shared one keeps covering the other half for longer. Nothing here decides that; it reads
     * the list, and the list is sorted by {@link ShieldKind}'s own order.
     *
     * <p>The result never has a negative pool, never exceeds {@code loss} in total, and leaves the layers
     * outside {@code mask} untouched — which is what protects a parked layer from a hit it does not cover.
     *
     * @param layers the ledger's layers
     * @param loss   the damage to charge, non-positive for none
     * @param mask   which kinds may pay, from {@link ShieldKind#maskOf}
     */
    public static List<ShieldLayer> spread(@Nullable List<ShieldLayer> layers, float loss, int mask) {
        if (layers == null || layers.isEmpty()) {
            return List.of();
        }
        float remaining = clean(loss);
        List<ShieldLayer> charged = new ArrayList<>(layers.size());
        for (ShieldLayer layer : layers) {
            if (layer == null) {
                continue;
            }
            if (remaining <= 0.0F || (mask & layer.kind().mask()) == 0) {
                charged.add(layer);
                continue;
            }
            float paid = Math.min(layer.pool(), remaining);
            remaining -= paid;
            charged.add(layer.withPool(layer.pool() - paid));
        }
        return List.copyOf(charged);
    }

    /** {@return {@code value} with non-finite and negative input flattened to zero} */
    private static float clean(float value) {
        if (!Float.isFinite(value) || value < 0.0F) {
            return 0.0F;
        }
        return value;
    }
}
