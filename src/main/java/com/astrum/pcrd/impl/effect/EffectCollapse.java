package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.EffectTiers;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.TieredParams;
import com.astrum.pcrd.impl.growth.LevelBands;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>「不同技能都相同效果，取最高等级」</b> — the same effect coming from several skills is applied
 * <b>once</b>, at the <b>highest</b> resolved tier.
 *
 * <h2>What the human said, and what it forbids</h2>
 * Two skills that both apply {@code buff_attack_area} do not stack. On a level-35 character (band {@code +1})
 * declaring them at tiers 1 and 2, the answer is
 * {@code max(1 + 1, 2 + 1) = 3}, applied <b>once</b> — not {@code +20%} and {@code +40%} added into
 * {@code +60%} from two separate applications, and not {@code max(1, 2) + 1} if the clamp happened to bite
 * one of them first.
 *
 * <h2>Why this lives here and not in each executor</h2>
 * An executor sees <b>one</b> hook. It cannot know that the skill before it in the list also raised attack.
 * Eleven per-effect arms each collapsing "their own" kind would be eleven copies of the rule, and the copies
 * would disagree the first time one of them forgot the clamp or the band. So the collapse happens
 * <b>once, before dispatch</b>: {@link #collapse} takes the whole hook list of one firing and answers the
 * list to actually run. That is the same "one place, before the split" shape {@code HookPlan} uses for "which
 * skills fire" and {@code AffectionKernel.hooksAt} uses for "which tier's skills run".
 *
 * <h2>The identity of "the same effect"</h2>
 * Keyed by {@link EffectType} plus the <b>receiver shape</b> — the radius and duration — and nothing else.
 * That is deliberately narrow:
 *
 * <ul>
 *   <li><b>Type alone is too coarse.</b> A self-only {@code shield} ({@code radius: 0}) and a party-wide one
 *       are two different applications to two different sets of bodies; collapsing them would silently
 *       extend the self shield to the party or shrink the party one to the caster.</li>
 *   <li><b>The tier is not part of the key</b> — it is the thing being maximised. Two hooks of one type with
 *       the same reach and clock are "the same effect at two rungs", which is exactly the case the human
 *       named.</li>
 *   <li><b>Where the effect lands is not part of the key either</b>, because it cannot differ: {@link EffectType}
 *       already declares {@code reachesParty()} / {@code reachesVictim()}, so a type is always one or the
 *       other.</li>
 * </ul>
 *
 * <h2>What survives the collapse</h2>
 * The <b>first</b> hook of each key, with its tier replaced by the winning one, and its position kept — so
 * the firing order of everything else is unchanged and a log line still points at the hook the author wrote.
 * A non-tiered effect ({@code extra_damage}, {@code lifesteal}, …) is <b>never</b> collapsed: it has no tier
 * to maximise, and "two skills both deal damage" is two hits, which is the intended behaviour rather than a
 * duplicate.
 */
@ApiStatus.Internal
public final class EffectCollapse {

    /**
     * One application after the collapse: the hook to run, and the tier it runs at.
     *
     * <p>No accessor for "this hook's tier block with the winning tier substituted": the tier travels as a plain
     * {@code int} to the executor, which does the substitution itself ({@code EffectExecutors.withResolvedTier}).
     * A convenience accessor here would have been a second place that builds a {@link TieredParams} from a hook,
     * and the two would only ever differ by one of them forgetting the clock or the reach.
     *
     * @param hook     the hook to apply — the survivor of its (type, reach, clock) key
     * @param tier     the tier to use, already band-added and clamped; equal to {@code hook.tieredParams().tier()}
     *                 for a hook that was not collapsed and whose band is zero
     * @param declared the tier the datapack wrote, for the log line
     * @param sources  how many hooks of this key were collapsed into this one; {@code 1} when none were
     */
    @ApiStatus.Internal
    public record Applied(EffectHook hook, int tier, int declared, int sources) {
    }

    private EffectCollapse() {
    }

    /**
     * {@return the applications to run for one firing, with duplicate effects collapsed}
     *
     * <h2>The algorithm, in one pass</h2>
     * Walk the hooks in order. A non-tiered hook is emitted as-is. A tiered hook resolves its band-composed,
     * clamped tier (through {@link TieredAmounts#resolve}, the one place that composition lives) and is keyed;
     * if the key is new it is emitted at that tier, and if the key is already emitted the survivor takes the
     * higher tier and its source count grows. The survivor keeps the <b>position</b> of its first occurrence,
     * so the order every other effect fires in is untouched.
     *
     * @param hooks the hook list of one firing, from {@code AffectionKernel.hooksAt}
     * @param level the character's level, for the band; a level that is unknown is passed as {@code 0}, which
     *              answers the first band and is what {@code LevelBands} documents for hostile input
     */
    public static List<Applied> collapse(List<EffectHook> hooks, int level) {
        if (hooks == null || hooks.isEmpty()) {
            return List.of();
        }
        int band = LevelBands.bonusAt(level);
        List<Applied> order = new ArrayList<>(hooks.size());
        Map<Key, Integer> indexOfKey = new LinkedHashMap<>();
        for (EffectHook hook : hooks) {
            if (hook == null) {
                continue;
            }
            Optional<TieredParams> declared = hook.tieredParams();
            if (declared.isEmpty() || !EffectTiers.isTiered(hook.type())) {
                // Not tiered (or a hook whose type has no ladder): nothing to maximise, so it can neither be
                // collapsed nor collapse anything. Its tier is 0 because it has none.
                order.add(new Applied(hook, 0, 0, 1));
                continue;
            }
            int declaredTier = declared.get().tier();
            int tier = TieredAmounts.resolve(hook.type(), declared.get(), band).tier();
            Key key = Key.of(hook.type(), declared.get());
            Integer at = indexOfKey.get(key);
            if (at == null) {
                indexOfKey.put(key, order.size());
                order.add(new Applied(hook, tier, declaredTier, 1));
                continue;
            }
            Applied survivor = order.get(at);
            // The higher resolved tier wins. On a tie the first hook keeps the slot, so the survivor is
            // stable and a log line always names the hook the author wrote first.
            if (tier > survivor.tier()) {
                order.set(at, new Applied(survivor.hook(), tier, survivor.declared(),
                        survivor.sources() + 1));
            } else {
                order.set(at, new Applied(survivor.hook(), survivor.tier(), survivor.declared(),
                        survivor.sources() + 1));
            }
        }
        return List.copyOf(order);
    }

    /**
     * What makes two tiered hooks "the same effect": the type, the reach and the clock.
     *
     * <p>A record so the identity is written once and the map cannot key on the wrong pair — the failure mode
     * of a hand-rolled {@code String} key, which is how "radius 0.0" and "radius absent" would silently
     * become the same or different depending on how somebody built the text.
     *
     * @param type    the effect type
     * @param radius  the declared reach, if any
     * @param seconds the declared duration, if any
     */
    private record Key(EffectType type, OptionalDouble radius, OptionalDouble seconds) {

        static Key of(EffectType type, TieredParams params) {
            return new Key(type, params.radius(), params.seconds());
        }
    }
}
