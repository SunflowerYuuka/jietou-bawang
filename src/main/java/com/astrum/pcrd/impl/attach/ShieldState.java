package com.astrum.pcrd.impl.attach;

import com.astrum.pcrd.impl.effect.ShieldPool;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>Our share of one entity's absorption, per damage channel, plus everybody else's</b> — the ledger that
 * lets a shield coexist with vanilla's <em>and</em> with any other mod's, and that now tells physical damage
 * from magical.
 *
 * <h2>Why this lives on the entity and not in the effect instance</h2>
 * A {@code MobEffectInstance} carries exactly one per-instance number, the integer amplifier, and that field
 * is used for the tier (see {@code EffectTiers}). Absorption is a float measured against the receiver's max
 * health, so it cannot travel there without either losing its precision or stealing the field the tier needs.
 * So it is entity state, and {@link Attachments#SHIELD_POOL} registers it.
 *
 * <h2>The channels, and why they are layers</h2>
 * 「物理护盾和魔法护盾…做一下2个单独的通道。」 A layer is one channel's worth of our shield:
 * {@link ShieldKind#PHYS} absorbs physical damage only, {@link ShieldKind#MAGIC} magical only, and
 * {@link ShieldKind#BOTH} either half from one pool (which is what 「物理·魔法伤害的护盾」 means — a barrier of
 * 27 points is worth 27, not 54). Each layer carries its own deadline, and a grant replaces <b>only the layer
 * of its own kind</b> — the human's 「覆盖只会覆盖自身」 applied one level up, so 由衣's physical barrier and
 * 望's shared one can be on the same player without cancelling each other.
 *
 * <h2>What the three scalar fields are for, and what each one fixes</h2>
 * <dl>
 *   <dt>{@code lastWritten}</dt>
 *   <dd>the total float this mod last wrote <em>or last measured</em>, so damage can be measured as a
 *       difference. Without it the ledger cannot tell 「an apple clobbered the float」 from 「damage took our
 *       shield」 — the defect a dedicated-server probe measured as
 *       {@code [T35] FAIL our pool survived vanilla's max() write: 0.0}.</dd>
 *
 *   <dt>{@code foreign}</dt>
 *   <dd><b>everyone else's share of that float</b> — vanilla's, another mod's, or both. The human's
 *       instruction: 「如果会覆盖掉别人的，就做成新的效果，覆盖只会覆盖自身，不会覆盖金苹果还有其他mod的额外生命值」.
 *       It has to be <em>stored</em> rather than re-derived for one reason: a grant <b>replaces</b> our pool,
 *       so a float read <em>after</em> our own write can no longer say how much of it was there before us.</dd>
 *
 *   <dt>{@code vanillaSeen}</dt>
 *   <dd>what vanilla's {@code absorption} contributed when {@code foreign} was last accounted for. Vanilla
 *       writes the float with {@code max(current, grant)}, so a golden apple's four points are <b>not</b>
 *       visible as a rise. When that effect then ends, the float may still hold those four and a residue is
 *       indistinguishable from a mod that just granted four; comparing this field with the live effect is what
 *       tells the two apart.</dd>
 * </dl>
 *
 * <h2>{@code visibleKinds}: the float is not always the whole of our share</h2>
 * The typed split cannot be expressed by vanilla's absorption at all: {@code actuallyHurt} spends the float
 * on <b>any</b> damage, so a physical-only barrier mirrored into it would absorb a magic bolt. The answer is
 * the damage hook: for a hit of one type, the layers that do <b>not</b> cover it are taken out of the float
 * for that hit ({@code ShieldPool} parking), vanilla then spends only the covering layers plus everybody
 * else's share, and the next read puts the parked layers back.
 *
 * <p>{@code visibleKinds} is that fact: the mask of layer kinds currently inside the float. It is
 * {@link ShieldKind#ALL} in the ordinary case, and while a hit is being paid it is the mask of the kinds that
 * cover it. {@link #reconciledAgainst} reads the float <b>directly</b> while the mask is partial — the float
 * then holds only the covering layers and everybody else's share, so the covered part is
 * {@code min(visible, observed)} and the rest is theirs. That is exact, needs no estimate, and needs no event.
 *
 * <h2>Why the deadline is part of the value</h2>
 * A layer has to stop being ours at exactly the moment the effect that granted it expires, or a later golden
 * apple would be silently claimed by a pool nobody can see. Carrying the deadline in the layer means
 * {@link #reconciledAgainst} drops it by itself, so every reader is correct with no event and no tick source.
 *
 * <h2>What cannot be recovered, stated rather than hidden</h2>
 * Absorption is <b>one float</b>. A foreign writer that puts it <em>below</em> what it held — another mod
 * calling {@code setAbsorptionAmount} with a smaller number, or its own shield breaking — is arithmetically
 * identical to damage, and damage is paid by our shield first. So a foreign write-down also spends our shield.
 * A foreign grant that is <b>not</b> a rise — a mod doing its own {@code max()}, the way vanilla's apple does —
 * is likewise invisible, and only vanilla's effect can be read back (that is {@code ShieldPool.vanillaShareOf}).
 *
 * <h2>Why it is not serialized</h2>
 * The shield exists for a few seconds, so a ledger restored from disk would describe a shield the game has
 * already expired. Being unserialized also makes {@code copyOnDeath()} unreachable, which is the behaviour we
 * want — dying drops your shield.
 *
 * @param layers       our shield, one entry per {@link ShieldKind}, in kind order (specific before shared)
 * @param lastWritten  the absorption float this mod last wrote or last measured
 * @param foreign      everyone else's share of that float — vanilla's and any other mod's
 * @param vanillaSeen  what vanilla's {@code absorption} contributed when {@code foreign} was last accounted for
 * @param visibleKinds the kinds currently inside the float, from {@link ShieldKind#maskOf}; typically
 *                     {@link ShieldKind#ALL}, and only the covering kinds while a typed hit is being paid
 */
@ApiStatus.Internal
public record ShieldState(List<ShieldLayer> layers, float lastWritten, float foreign, float vanillaSeen,
        int visibleKinds) {

    /** No shield of ours: the state of a body this mod has never shielded. */
    public static final ShieldState NONE = new ShieldState(List.of(), 0.0F, 0.0F, 0.0F, ShieldKind.ALL);

    /** The order layers are stored and paid in: the type-restricted channels first, the shared one last. */
    private static final Comparator<ShieldLayer> BY_KIND = Comparator.comparingInt(layer -> layer.kind().ordinal());

    public ShieldState {
        if (layers == null || layers.isEmpty()) {
            layers = List.of();
        } else {
            List<ShieldLayer> sane = new ArrayList<>(layers.size());
            for (ShieldLayer layer : layers) {
                if (layer != null && layer.pool() > 0.0F) {
                    sane.add(layer);
                }
            }
            sane.sort(BY_KIND);
            layers = List.copyOf(sane);
        }
        if (!Float.isFinite(lastWritten) || lastWritten < 0.0F) {
            lastWritten = 0.0F;
        }
        if (!Float.isFinite(foreign) || foreign < 0.0F) {
            foreign = 0.0F;
        }
        if (!Float.isFinite(vanillaSeen) || vanillaSeen < 0.0F) {
            vanillaSeen = 0.0F;
        }
        visibleKinds &= ShieldKind.ALL;
    }

    /**
     * {@return this state with the layer of {@code kind} <b>replaced</b> by a shield worth {@code amount}}
     *
     * <p>Named {@code granted} rather than {@code plus} because it used to add: holding attack re-fired the
     * shield every swing and the yellow hearts piled up without limit — 「有个护盾效果能无限叠加」. A shield is a
     * state that is restored, not a resource that accumulates.
     *
     * <p><b>Only the same kind is replaced.</b> A physical grant leaves a shared barrier's layer alone, and a
     * shared grant leaves a physical-only barrier alone: 「覆盖只会覆盖自身」, and the reason
     * {@link ShieldKind} is a layer key rather than a flag on one pool.
     *
     * @param kind  which channel the grant fills
     * @param amount shield the cast is worth, in health points
     * @param now   the current game time, in level ticks
     * @param ticks how long this grant lasts
     */
    public ShieldState granted(ShieldKind kind, float amount, long now, int ticks) {
        ShieldKind channel = kind == null ? ShieldKind.BOTH : kind;
        List<ShieldLayer> next = new ArrayList<>(layers.size() + 1);
        float previous = 0.0F;
        for (ShieldLayer layer : layers) {
            if (layer.kind() == channel) {
                previous = layer.pool();
                continue;   // replaced below
            }
            next.add(layer);
        }
        float pool = ShieldPool.granted(previous, amount);
        if (pool > 0.0F) {
            next.add(new ShieldLayer(channel, pool, now + Math.max(0, ticks)));
        }
        next.sort(BY_KIND);
        // The float the mirror will write holds every layer again, so the mask is whole from here on.
        return new ShieldState(next, lastWritten, foreign, vanillaSeen, ShieldKind.ALL);
    }

    /**
     * {@return this state reconciled against the entity's current float}
     *
     * <h2>Two readings, and why the parked one is not a guess</h2>
     * <ol>
     *   <li><b>The whole of our share is in the float</b> ({@code visibleKinds == ALL}) — the ordinary case.
     *       The float fell by the damage; our layers pay it first (the documented order), the overflow is
     *       everybody else's, and a rise is theirs too (a foreign grant), except for the four points that
     *       vanished with vanilla's apple ({@code vanillaSeen}).</li>
     *   <li><b>Only the covering layers are in the float</b> — a typed hit was just paid. The float then holds
     *       <em>only</em> our covering layers and everybody else's share, so the covered part is
     *       {@code min(visible, observed)} and everything above it is theirs. The parked layers are untouched
     *       and the mask goes back to whole, which is what makes the next mirror put them back.</li>
     * </ol>
     *
     * <p>Never throws and never answers a pool larger than what the float can account for.
     *
     * @param observedTotal the entity's absorption float right now
     * @param now           the current game time, in level ticks
     */
    public ShieldState reconciledAgainst(float observedTotal, long now) {
        List<ShieldLayer> live = List.of();
        if (!layers.isEmpty()) {
            List<ShieldLayer> kept = new ArrayList<>(layers.size());
            for (ShieldLayer layer : layers) {
                if (now < layer.expiresAtTick()) {
                    kept.add(layer);
                }
            }
            live = List.copyOf(kept);
        }
        if (live.isEmpty()) {
            // Every grant is over. Whatever is in the float is somebody else's or a later grant's, which will
            // set its own ledger — so the honest answer is "nothing of ours".
            return NONE;
        }
        float observed = Float.isFinite(observedTotal) && observedTotal > 0.0F ? observedTotal : 0.0F;
        float loss = ShieldPool.lossSince(lastWritten, observed);
        if (visibleKinds != ShieldKind.ALL) {
            // The float is parked: it holds exactly the covering layers plus everybody else's share.
            float visible = ShieldPool.totalOf(live, visibleKinds);
            List<ShieldLayer> charged = ShieldPool.spread(live, loss, visibleKinds);
            float remainingVisible = ShieldPool.totalOf(charged, visibleKinds);
            float theirs = Math.max(0.0F, observed - remainingVisible);
            float total = ShieldPool.totalOf(charged, ShieldKind.ALL);
            return new ShieldState(charged, total + theirs, theirs, vanillaSeen, ShieldKind.ALL);
        }
        float before = ShieldPool.totalOf(live, ShieldKind.ALL);
        List<ShieldLayer> charged = ShieldPool.spread(live, loss, ShieldKind.ALL);
        float risen = Math.max(0.0F, observed - lastWritten);
        float overflow = Math.max(0.0F, loss - before);
        float theirs = Math.max(0.0F, foreign + risen - overflow);
        return new ShieldState(charged, observed, theirs, vanillaSeen, ShieldKind.ALL);
    }

    /**
     * {@return everybody else's share of the float right now — the number a new grant must add to, not replace}
     *
     * <p>The derivation lives in {@link ShieldPool#foreignShare} so it is one readable expression with a test on
     * it; this is the call that feeds it the ledger's own numbers.
     *
     * @param observedTotal the entity's absorption float right now
     * @param vanillaNow    what vanilla's {@code absorption} contributes right now
     */
    public float foreignShare(float observedTotal, float vanillaNow) {
        return ShieldPool.foreignShare(observedTotal, lastWritten, foreign, vanillaNow, vanillaSeen);
    }

    /**
     * {@return this state remembering that the float now holds {@code total}, of which {@code theirShare} is
     *         everybody else's}
     *
     * <p>Called by the mirror, after it has written {@code total}. This is the only place the ledger's numbers
     * are settled, which is what keeps the layers and {@code lastWritten} describing the same float, and it
     * also puts every layer back inside the float ({@code visibleKinds} whole).
     *
     * @param total      the absorption float this mod just wrote
     * @param theirShare everybody else's share of it, from {@link ShieldPool#foreignShare}
     * @param vanillaNow what vanilla's {@code absorption} contributes, so a later expiry can be told apart from
     *                   a foreign grant
     */
    public ShieldState written(float total, float theirShare, float vanillaNow) {
        return new ShieldState(layers, Float.isFinite(total) && total > 0.0F ? total : 0.0F, theirShare,
                vanillaNow, ShieldKind.ALL);
    }

    /**
     * {@return this state with the layers a hit of one type cannot use taken out of the float}
     *
     * <p>The damage hook calls this before vanilla spends the float: only the layers that <b>cover</b> the
     * incoming damage stay in it, so a magic bolt cannot spend a physical-only barrier. The next read
     * ({@link #reconciledAgainst}) derives the split from the float without any further help — see
     * {@code visibleKinds}.
     *
     * @param magic    the classifier's answer for the hit, from {@code DamageReduction.isMagic}
     * @param total    the absorption float that was written (the covering layers plus everybody else's share)
     * @param their    everybody else's share at the moment of the write
     * @param vanillaNow vanilla's share at the moment of the write
     */
    public ShieldState parkedFor(boolean magic, float total, float their, float vanillaNow) {
        int visible = 0;
        for (ShieldLayer layer : layers) {
            if (layer.covers(magic)) {
                visible |= layer.kind().mask();
            }
        }
        return new ShieldState(layers, Float.isFinite(total) && total > 0.0F ? total : 0.0F, their,
                vanillaNow, visible);
    }

    /**
     * {@return whether a hit of one type leaves any of our layers outside the float}
     *
     * <p>{@code false} is the ordinary case for a shared barrier: nothing is parked, the float is written by
     * nobody, and vanilla spends it the way it always did. {@code true} is what makes the typed split real.
     *
     * @param magic the classifier's answer for the hit
     */
    public boolean parksFor(boolean magic) {
        return coveredAmount(magic) != total();
    }

    /** {@return how much of our shield a hit of one type can absorb} */
    public float coveredAmount(boolean magic) {
        float sum = 0.0F;
        for (ShieldLayer layer : layers) {
            if (layer.covers(magic)) {
                sum += layer.pool();
            }
        }
        return ShieldPool.total(sum, 0.0F);
    }

    /** {@return the absorption the HUD should show: our layers plus everybody else's share} */
    public float totalWith(float theirShare) {
        return ShieldPool.total(total(), theirShare);
    }

    /** {@return the absorption all our layers hold together} */
    public float total() {
        return ShieldPool.totalOf(layers, ShieldKind.ALL);
    }

    /** {@return whether this entity holds any of our shield} */
    public boolean isPresent() {
        return total() > 0.0F;
    }

    /** {@return whether every one of our layers has expired at {@code now}} */
    public boolean isExpired(long now) {
        for (ShieldLayer layer : layers) {
            if (now < layer.expiresAtTick()) {
                return false;
            }
        }
        return true;
    }

    /** {@return the deadline of the last layer to expire, or {@code 0} for none} */
    public long expiresAtTick() {
        long last = 0L;
        for (ShieldLayer layer : layers) {
            last = Math.max(last, layer.expiresAtTick());
        }
        return last;
    }
}