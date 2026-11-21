package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.impl.InternalIds;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>「控制免疫」: the one table of what "control" is, and the two gates that honour it.</b>
 *
 * <h2>What control covers here, decided rather than assumed</h2>
 * <b>Panic ({@link EffectType#PANIC}) only.</b> Panic is the mod's one <em>hard control</em>: a panicking body
 * cannot attack, interact, or move under its own power, and {@code PanicRules} states exactly what that can
 * and cannot mean on a server. Slowness and weakness are deliberately <b>not</b> control: they change a
 * <em>stat</em> (how fast, how hard) while the holder still acts, so they are debuffs. Treating them as
 * control would make 控制免疫 a second, broader immunity than 「免疫控制」 says, and it would silently overlap
 * the debuff/cleanse design space the project keeps separate.
 *
 * <p>The classification is {@link #controllableTypes()}; a future control effect (a silence, a fear, a
 * disarm) is added to that one table and both gates below pick it up. That is the same shape
 * {@code EffectType.partyScoped()} uses, and for the same reason: the question "is this a control" has one
 * answer, not one per reader.
 *
 * <h2>The two gates</h2>
 * <ol>
 *   <li><b>Application</b> — {@code TieredEffects}' panic arm asks {@link #blocks(EffectType, boolean)} before
 *       adding the instance, so a panic never lands on an immune holder (and cannot refresh an existing one).</li>
 *   <li><b>Action</b> — {@code PanicControl.isPanicking} answers {@code false} for an immune holder, so a
 *       panic that was already running when the immunity arrived stops refusing attacks and interactions for
 *       the immunity's duration.</li>
 * </ol>
 *
 * <p>Neither gate touches damage. That is the property this channel exists for: 「控制免疫，单独开一个通道」 is
 * not 「无敌」 — resistance V would have been all-damage immunity, “凭空多给一条原文没写的效果”.
 *
 * <h2>What it honestly cannot do</h2>
 * A control that does not go through {@link EffectType#PANIC} — a third-party mod's stun with its own effect
 * type — is not covered: this mod can only answer for the controls it implements. The limitation is stated
 * rather than guessed, and {@code controllableTypes()} is where a new one would be added.
 */
@ApiStatus.Internal
public final class ControlImmunity {

    /** Registry path: {@code astrum:control_immunity}. */
    public static final String PATH = "control_immunity";

    /** {@code astrum:control_immunity}. */
    public static final ResourceLocation ID = InternalIds.id(PATH);

    /** Particle tint: a pale violet halo, distinct from panic's sickly dark violet. */
    public static final int COLOUR = 0x9B6AD8;

    /**
     * Every effect this mod classifies as <b>control</b>.
     *
     * <p>One member today. A set rather than {@code type == PANIC} at each call site so that both gates, the
     * report and any future test read the same declaration, and so adding a second control is a one-line data
     * change rather than a hunt for every {@code == PANIC}.
     */
    private static final Set<EffectType> CONTROL_TYPES = Set.of(EffectType.PANIC);

    private ControlImmunity() {
    }

    /** {@return the effects control immunity blocks, for a message, an assertion or a future gate} */
    public static Set<EffectType> controllableTypes() {
        return CONTROL_TYPES;
    }

    /** {@return whether {@code type} is a control, i.e. whether immunity is allowed to block it} */
    public static boolean covers(@Nullable EffectType type) {
        return type != null && CONTROL_TYPES.contains(type);
    }

    /**
     * {@return whether an immune holder is protected from {@code type}}
     *
     * <p><b>The rule, pure.</b> A damage type, a stat debuff and a buff all answer {@code false} even for an
     * immune holder — the "does not block damage" half of this channel's contract is this truth table.
     *
     * @param type   the effect being applied
     * @param immune whether the target holds {@code astrum:control_immunity}
     */
    public static boolean blocks(@Nullable EffectType type, boolean immune) {
        return immune && covers(type);
    }

    /** {@return whether {@code entity} currently holds the immunity} */
    public static boolean holds(@Nullable LivingEntity entity) {
        if (entity == null) {
            return false;
        }
        Optional<Holder<MobEffect>> holder = PartyEffects.controlImmunityHolder();
        return holder.isPresent() && entity.hasEffect(holder.get());
    }

    /**
     * {@return whether {@code type} may be applied to {@code entity}}
     *
     * <p>The application gate: {@code true} for an immune holder and every non-control, which is why it is
     * written as "not blocked" rather than as a test callers have to invert correctly at each site.
     */
    public static boolean permits(@Nullable LivingEntity entity, @Nullable EffectType type) {
        return !blocks(type, holds(entity));
    }
}