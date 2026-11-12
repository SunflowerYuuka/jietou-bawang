package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectHook;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;

/**
 * Turns one character's hook list into effects for one trigger event (spec §5.1/§5.2/§5.3/§6.4).
 *
 * <h2>Why this is its own class</h2>
 * It is the "what does a hook do" half of the pipeline; {@link CombatEvents} is the "when did something
 * happen" half. Keeping them apart means the event listener stays about event choice, ordering, the
 * recursion fuse and the facts of what just happened, while this class is about the eight effects.
 *
 * <h2>What lives here now, and what moved out</h2>
 * The <b>decision</b> — which of a hook list runs, for these facts, in what order, and whether a
 * {@code stack_full} pass follows — is {@link HookPlan#fire}: a pure step over the hook list, the
 * facts, a chance roll and an effect runner. This class keeps the two things that need the game: the
 * chance roll from the attacker's {@link net.minecraft.util.RandomSource}, and the diagnostics that
 * name a character and a hook list index.
 *
 * <p>That split is not cosmetic. The loop used to be untestable, so the per-trigger dedupe that dropped
 * djeeta's {@code area_charge} on every third hit shipped with a green suite. See {@link HookPlan} for
 * the three properties the extracted step now owes (all effects of one trigger run; {@code stack_full}
 * does not recurse; {@code stack_full} fires once per fill).
 *
 * <h2>The one re-entrant step: {@code stack_full}</h2>
 * A {@code stack} hook can drive a count to its cap, and "the stack is full" is a trigger of its own.
 * The stack arm reports that fact back, and the same character is re-evaluated with
 * {@link TriggerFacts#forStackFull} — same context, same slot multiplier, so a slot-5 character's
 * {@code stack_full} hook is still scaled as a slot-5 character. That pass runs with
 * {@code allowStackFull = false}, so a {@code stack_full} hook that itself fills a stack cannot loop.
 */
@ApiStatus.Internal
public final class HookEvaluator {

    /**
     * Hooks already reported as broken, keyed {@code <character id>#<hook index>}.
     *
     * <p>The key comes from the loaded datapack, not from a hit or a player, so this set is bounded by
     * the pack's content (see {@link WarnOnce}). Without it, a single malformed hook would write a WARN
     * into the server log on every hit.
     */
    private static final WarnOnce REPORTED = new WarnOnce();

    private HookEvaluator() {
    }

    /**
     * Runs {@code def}'s hooks for one event, applying whatever they produce through {@code ctx}.
     *
     * @param def  the character definition owning the hooks
     * @param ctx  the event, carrying this slot's multiplier and character attribution
     * @param facts what just happened, as the eight triggers see it
     */
    public static void evaluate(CharacterDef def, HookContext ctx, TriggerFacts facts) {
        HookPlan.fire(def.effects(), facts,
                chance -> roll(chance, ctx.attacker()),
                (index, hook) -> Optional.ofNullable(EffectExecutors.run(hook, ctx)),
                invalid -> warnInvalid(invalid, ctx));
    }

    /**
     * Reports a hook that cannot work as written, naming the character, the hook index and the
     * parameter — <b>once</b> per (character, hook), because the defect belongs to the loaded datapack
     * and not to the hit that revealed it.
     */
    private static void warnInvalid(HookPlan.Invalid invalid, HookContext ctx) {
        if (!REPORTED.first(ctx.characterId() + "#" + invalid.hookIndex())) {
            return;
        }
        Astrum.LOGGER.warn("astrum: character {} hook #{} ({} on {}) was skipped: {}", ctx.characterId(),
                invalid.hookIndex(), invalid.hook().type(), invalid.hook().trigger(), invalid.problem());
    }

    /** {@return the chance roll for one hook} */
    private static boolean roll(double chance, ServerPlayer attacker) {
        if (chance >= 1.0) {
            return true;
        }
        if (chance <= 0.0) {
            return false;
        }
        return attacker.getRandom().nextFloat() < chance;
    }
}
