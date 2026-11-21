package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * The two halves of panic that are <b>event-shaped</b>: refusing a panicking player's actions, and putting a
 * mob's AI back when the panic ends.
 *
 * <h2>Which events, and why those are the real gates</h2>
 * A server never sees a key. It sees the requests a client sends, and NeoForge fires one cancellable event
 * per request at the point the server is about to act on it:
 *
 * <table border="1">
 *   <caption>Each action, the event that gates it, and where the server fires it</caption>
 *   <tr><th>action</th><th>event</th><th>fired from (verified in 21.1.265)</th></tr>
 *   <tr><td>left-click on an entity</td><td>{@link AttackEntityEvent}</td>
 *       <td>{@code Player.attack} → {@code CommonHooks.onPlayerAttackTarget}</td></tr>
 *   <tr><td>right-click a block</td><td>{@code PlayerInteractEvent.RightClickBlock}</td>
 *       <td>{@code ServerPlayerGameMode.useItemOn} → {@code CommonHooks.onRightClickBlock}</td></tr>
 *   <tr><td>left-click a block (mining)</td><td>{@code PlayerInteractEvent.LeftClickBlock}</td>
 *       <td>{@code ServerPlayerGameMode.startDestroyBlock} → {@code CommonHooks.onLeftClickBlock}</td></tr>
 *   <tr><td>right-click an entity</td><td>{@code PlayerInteractEvent.EntityInteract}</td>
 *       <td>the entity-interact path</td></tr>
 *   <tr><td>use an item in air</td><td>{@code PlayerInteractEvent.RightClickItem}</td>
 *       <td>the item-use path</td></tr>
 * </table>
 *
 * <p>Cancelling is what makes the action <em>not happen</em>: the swing never lands, the block is never
 * used. The alternative — letting it happen and undoing it — cannot undo a placed block, an eaten apple or
 * a dead mob, so it is not an option.
 *
 * <h2>Why one generic handler and not five</h2>
 * {@code PlayerInteractEvent} is the abstract base and does <b>not</b> implement {@link ICancellableEvent} —
 * only its five subclasses do (two of which, {@code RightClickEmpty} and {@code LeftClickEmpty}, are
 * client-only and deliberately not cancellable). So the question "is she panicking?" is asked once, here, and
 * the cancellation goes through {@link ICancellableEvent#setCanceled}, which every cancellable subclass
 * shares. That is what lets a single {@code onInteract(PlayerInteractEvent)} serve all of them without a
 * method that cannot compile against the base type.
 *
 * <h2>Who puts a mob's AI back</h2>
 * {@code MobEffect} in 21.1.265 has <b>no</b> {@code onEffectRemoved} hook — expiry and explicit removal are
 * the NeoForge events {@code MobEffectEvent.Expired} and {@code MobEffectEvent.Remove}. So the restoration of
 * {@code noAi} lives here, in {@link #onEffectEnded} and {@link #onEffectRemoved}, which between them cover
 * the clock running out, {@code /effect clear}, milk, and a totem's cure. The alternative (a
 * {@code PlayerTickEvent} sweep looking for mobs that no longer hold the effect) would be the entity-walking
 * poll this project forbids, and it would miss {@code Mob}s entirely because they have no player tick.
 *
 * <h2>What a modified client can still do</h2>
 * Everything that does not go through one of these five requests: it can <b>walk</b>, because its position
 * arrives in {@code ServerboundMovePlayerPacket} and NeoForge posts no cancellable movement event — see
 * {@code PanicEffect.freezeInput} for why zeroing the movement axes cannot stop a walking player. It can also
 * send packets for things not in this table. The table is the set of <em>player actions this mod can see and
 * refuse</em>, and the report says so plainly rather than claiming the player has no keyboard.
 *
 * <p>{@code PlayerEvent.PlayerLoggedOutEvent} is not here: a panic is 30 ticks and the entity is discarded
 * on logout, so there is nothing to clean up (and the mob's tag dies with the entity it describes).
 */
@ApiStatus.Internal
public final class PanicControl {

    private PanicControl() {
    }

    /**
     * {@return whether {@code entity} is panicking right now}
     *
     * <p>The one predicate every cancellation below uses, so the "is it panicking" question has one answer.
     * Empty registry (a client that never bound the holder, a test) answers {@code false}, which is the
     * correct fail-open for a <em>blocking</em> listener: refusing every player's attacks because the effect
     * registry is not up would be far worse than a panic that did not block once.
     *
     * @param entity the entity an action is about to happen to or by
     */
    public static boolean isPanicking(LivingEntity entity) {
        if (!(entity instanceof Player)) {
            return false;   // panic's player half only; a mob is stopped by its AI, not by events
        }
        // 「控制免疫」's action gate: an immune holder is not treated as panicking even if an instance is
        // still on her — a panic that was running when the immunity arrived stops refusing actions for the
        // immunity's duration. ControlImmunity owns what "control" is; this only asks it.
        if (ControlImmunity.holds(entity)) {
            return false;
        }
        java.util.Optional<net.minecraft.core.Holder<MobEffect>> holder = PartyEffects.panicHolder();
        return holder.isPresent() && entity.hasEffect(holder.get());
    }

    /** A player tried to hit an entity: refuse it while she is panicking. */
    public static void onAttack(AttackEntityEvent event) {
        if (isPanicking(event.getEntity())) {
            event.setCanceled(true);
            Astrum.LOGGER.debug("astrum: panic blocked {}'s attack on {}", event.getEntity().getName().getString(),
                    event.getTarget().getName().getString());
        }
    }

    /**
     * A player tried to interact: refuse it while she is panicking.
     *
     * <p>The shared body of the five typed entry points below. It is <b>private</b> and takes the abstract
     * base type on purpose: {@code NeoForge.EVENT_BUS.addListener} refuses a method whose parameter is an
     * abstract event class ("Cannot register listeners for abstract class PlayerInteractEvent. Register a
     * listener to one of its subclasses instead!" — enforced at mod load, so an untyped registration is a
     * startup crash, not a warning). Each subclass therefore gets its own one-line method, and the rule lives
     * here once. That is the same shape {@code PartyBuffEvents} uses for two player events.
     *
     * @param event any {@code PlayerInteractEvent}, cancellable or not
     */
    private static void refuse(PlayerInteractEvent event) {
        if (!isPanicking(event.getEntity())) {
            return;
        }
        if (event instanceof ICancellableEvent cancellable) {
            cancellable.setCanceled(true);
            Astrum.LOGGER.debug("astrum: panic blocked {}'s {} at {}", event.getEntity().getName().getString(),
                    event.getClass().getSimpleName(), event.getPos());
            return;
        }
        // RightClickEmpty / LeftClickEmpty: client-only notifications of a click on air, with no server-side
        // action to refuse (the interface docs say the server is not even told). Nothing to cancel; say so
        // rather than pretending this path was blocked.
        Astrum.LOGGER.debug("astrum: {} is panicking and sent {}, which carries no server-side action",
                event.getEntity().getName().getString(), event.getClass().getSimpleName());
    }

    /** Right-click on a block: refused while panicking, so no block is placed or used. */
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        refuse(event);
    }

    /** Right-click on an entity: refused while panicking, so nothing is fed, traded or mounted. */
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        refuse(event);
    }

    /** Right-click on a specific point of an entity: the other half of the entity-interact path. */
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        refuse(event);
    }

    /** Right-click holding an item: refused while panicking, so nothing is eaten, thrown or cast. */
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        refuse(event);
    }

    /** Left-click on a block: refused while panicking, so no block is mined. */
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        refuse(event);
    }

    /**
     * The panic's clock ran out, or the instance was replaced: put a mob's {@code noAi} back.
     *
     * <p>{@code MobEffectEvent.Expired} is <b>cancellable</b>, so a listener may keep a panic alive; this one
     * only restores the AI, it never vetoes the expiry.
     */
    public static void onEffectEnded(MobEffectEvent.Expired event) {
        restoreAi(event.getEntity(), event.getEffectInstance().getEffect().value(), "expired");
    }

    /**
     * The effect was removed before its clock ran out — {@code /effect clear}, milk, a totem cure.
     *
     * <p>{@code MobEffectEvent.Remove} is the even earlier hook vanilla posts for every removal path
     * ({@code LivingEntity.removeEffect}, {@code removeEffectsCuredBy}, {@code removeAllEffects}), so this is
     * where the AI comes back for the paths the clock never reaches.
     */
    public static void onEffectRemoved(MobEffectEvent.Remove event) {
        restoreAi(event.getEntity(), event.getEffect().value(), "removed");
    }

    /**
     * Restores the {@code noAi} flag a panic suppressed.
     *
     * <p>Guarded three ways, and each guard is a real case: the entity must be a {@code Mob} (players have no
     * {@code noAi}), the side must be the server (the tag is server state), and the tag must be present — its
     * absence means this panic never suppressed anything (the effect was removed before its first tick, or
     * another panic's tag is still in flight) and waking a mob that was already awake would be a bug.
     *
     * @param entity the entity losing the effect
     * @param effect the effect being removed
     * @param reason the event that asked, for the log line
     */
    private static void restoreAi(LivingEntity entity, MobEffect effect, String reason) {
        if (!(entity instanceof Mob mob) || entity.level().isClientSide) {
            return;
        }
        if (!PartyEffects.isPanic(effect)) {
            return;   // some other effect expired; not ours to touch
        }
        var data = mob.getPersistentData();
        if (!data.contains(PanicEffect.NO_AI_TAG)) {
            return;
        }
        boolean wasNoAi = data.getBoolean(PanicEffect.NO_AI_TAG);
        data.remove(PanicEffect.NO_AI_TAG);
        mob.setNoAi(wasNoAi);
        Astrum.LOGGER.debug("astrum: panic {} on {} -> AI {}", reason, mob.getName().getString(),
                wasNoAi ? "left off (it was already off)" : "restored");
    }
}
