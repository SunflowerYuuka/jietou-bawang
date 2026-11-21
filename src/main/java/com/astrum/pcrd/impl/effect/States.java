package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.InternalIds;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>「状态」: a condition the caster is holding, as a real {@code MobEffect} — and the one predicate a
 * state-gated hook asks.</b>
 *
 * <h2>What a state is here, and the two rejected alternatives</h2>
 * A state is a <b>{@code MobEffectInstance} on the body</b>, named by its registry id. "Does she hold
 * 风之刃" is therefore exactly {@code LivingEntity#hasEffect}, the state's lifetime is vanilla's own effect
 * clock, and it shows on the effect bar without a row of our own. The three characters this build needs are
 * {@code astrum:wind_blade} (怜's 6★UB), {@code astrum:tea_pot} (铃莓's 二专) and {@code astrum:marker_125}
 * (矛依未's 6★UB, 「追加 1 个 ID 125，180 秒，最大 1 层」); a pack may gate on any
 * registered effect, including a vanilla one ({@code minecraft:strength}), because the question is the same
 * question.
 *
 * <p><b>Not a second tally.</b> The project already has {@code Counters} for 雪菲's marks, whose amplifier
 * <em>is</em> the count — and a state is not a count: 「获得【西式小茶壶】效果，最大一层」 is a boolean, and
 * "one layer" is the whole of it. Reusing {@code astrum:counter} would collide two characters' tallies on one
 * effect type, and a separate state book keyed by id would need its own expiry, its own logout/shutdown
 * clearing and its own "what is held right now" question — none of which vanilla's effect instance needs.
 *
 * <p><b>Not an entity attachment.</b> An attachment would be a second place that knows "state → remaining
 * ticks", would survive a world restart unless explicitly cleared, and would duplicate the one clock
 * {@code MobEffectInstance} already owns. The same reasoning {@code Counters}' javadoc gives for putting a
 * tally in an effect applies one level down.
 *
 * <h2>Why every state is its own registered type</h2>
 * Vanilla keys a living entity's effects <b>by type</b>, so two states sharing one type would merge into one
 * entry: granting 茶壶 would replace 风之刃's instance and both predicates would disagree with the player's
 * bar. Each state therefore gets its own marker registration in {@link PartyEffects}. Two states that must be
 * held <b>at the same time</b> (not a shipped case today) are two types, which is also the honest shape.
 *
 * <h2>Failure policy</h2>
 * Nothing here throws. {@link #holds} is reached from every hook firing, including a hit inside a damage
 * sequence, so an unbound registry or an unknown id must answer {@code false} ("this build cannot see that
 * state") rather than take the swing down. {@code false} is also the fail-closed answer for the gate: a hook
 * that cannot prove its state is held does not fire, which is the opposite, and safer, default to "always".
 */
@ApiStatus.Internal
public final class States {

    /** Registry path of 怜's 风之刃: {@code astrum:wind_blade}. */
    public static final String WIND_BLADE_PATH = "wind_blade";

    /** Registry path of 铃莓's 西式小茶壶: {@code astrum:tea_pot}. */
    public static final String TEA_POT_PATH = "tea_pot";

    /**
     * Registry path of 矛依未's unnamed mark: {@code astrum:marker_125}.
     *
     * <h2>Why this one is named after a number</h2>
     * The bwiki gives her clause as 「追加 1 个 ID 125（180 秒，最大 1 层）」 — the game's internal buff id and
     * nothing else. There is no name to borrow, so the id is the name: inventing a poetic one ("天楼之印") would
     * be content this build was not given, and a reader comparing the data to the wiki would have no way to see
     * that it is the same thing.
     */
    public static final String MARKER_125_PATH = "marker_125";

    /** {@code astrum:wind_blade}. */
    public static final ResourceLocation WIND_BLADE = InternalIds.id(WIND_BLADE_PATH);

    /** {@code astrum:tea_pot}. */
    public static final ResourceLocation TEA_POT = InternalIds.id(TEA_POT_PATH);

    /** {@code astrum:marker_125} — 矛依未's 「标记 ID 125」. */
    public static final ResourceLocation MARKER_125 = InternalIds.id(MARKER_125_PATH);

    /** Particle tint: 怜's blade is the pale steel of her sword. */
    public static final int WIND_BLADE_COLOUR = 0xB8D8E8;

    /** Particle tint: 铃莓's tea is warm amber, deliberately unlike the ice-blue of 冰龙之印. */
    public static final int TEA_POT_COLOUR = 0xD9A441;

    /** Particle tint: 矛依未's mark is the deep violet of 天楼. */
    public static final int MARKER_125_COLOUR = 0x7A5CC4;

    private States() {
    }

    /**
     * {@return whether {@code entity} is holding {@code state} right now}
     *
     * <p>The <b>one</b> answer to "is this state on", used by the hook gate in {@code HookEvaluator} and by
     * any future reader. An unknown id, a {@code null} entity or an unbound registry answers {@code false};
     * the unknown-id case also gets one WARN, because a pack that names a state nobody registers is a data
     * defect that would otherwise be indistinguishable from "she is not holding it".
     *
     * @param entity the caster; {@code null} answers {@code false}
     * @param state  the state's registry id; {@code null} answers {@code false}
     */
    public static boolean holds(@Nullable LivingEntity entity, @Nullable ResourceLocation state) {
        if (entity == null || state == null) {
            return false;
        }
        Optional<Holder.Reference<MobEffect>> holder = holderOf(state);
        if (holder.isEmpty()) {
            if (UNKNOWN.first(state.toString())) {
                Astrum.LOGGER.warn("astrum: a hook requires state {} but no such mob effect is registered; "
                        + "the gated branch will never fire until it is registered (reported once)",
                        state);
            }
            return false;
        }
        try {
            return entity.hasEffect(holder.get());
        } catch (RuntimeException e) {
            Astrum.LOGGER.debug("astrum: could not read state {} off {}; treating it as not held",
                    state, entity.getName().getString(), e);
            return false;
        }
    }

    /**
     * {@return the registered effect {@code state} names, or empty when this build does not know it}
     *
     * <p>Public and game-bound because the registry is the only authority on "is that a real effect"; the
     * callers that only need a yes/no use {@link #holds}.
     *
     * @param state the registry id, may be {@code null}
     */
    public static Optional<Holder.Reference<MobEffect>> holderOf(@Nullable ResourceLocation state) {
        if (state == null) {
            return Optional.empty();
        }
        try {
            return BuiltInRegistries.MOB_EFFECT.getHolder(state);
        } catch (RuntimeException e) {
            return Optional.empty();   // a lookup before the registry is bound is "cannot say", not a throw
        }
    }

    /**
     * Builds one state's marker: a harmless, visible effect whose <b>only</b> meaning is "this state is on".
     *
     * <p>Amplifier and duration are a state's own: 「获得一层…持续 18 秒」 is amplifier {@code 0} and the
     * duration the skill declares, so nothing here reads the amplifier as a magnitude. The marker carries no
     * attribute modifier and no tick — {@code AstrumMarkerEffect}'s javadoc explains why that is the honest
     * shape for an effect whose whole substance is being on the entity.
     *
     * @param colour the particle tint, so two states read differently on the bar
     */
    public static MobEffect marker(int colour) {
        return new AstrumMarkerEffect(MobEffectCategory.BENEFICIAL, colour);
    }

    /** Unknown-state notices, once per id rather than once per swing. */
    private static final com.astrum.pcrd.impl.combat.WarnOnce UNKNOWN =
            new com.astrum.pcrd.impl.combat.WarnOnce();
}