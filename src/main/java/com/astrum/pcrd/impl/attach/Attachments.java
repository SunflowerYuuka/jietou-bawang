package com.astrum.pcrd.impl.attach;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.effect.PartyBuffEvents;
import com.astrum.pcrd.impl.effect.PartyEffects;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jetbrains.annotations.ApiStatus;

/**
 * Registration of the seven attachment types: three persisted player-state ones ({@link #ROSTER},
 * {@link #PARTY}, {@link #UB_STATE}) and four runtime-only ones ({@link #STACK}, {@link #COMBO},
 * {@link #SHIELD_POOL}, {@link #BATTLE_START}).
 *
 * <h2>Why the three player-state types are separate and not one big object</h2>
 * Sync frequency. {@link UbState} changes every tick; {@link Roster} changes a handful of times per
 * session. One combined attachment would re-serialize the whole character book on every charge
 * tick. Spec §4.3 makes the split a requirement, not a preference. {@link #STACK} is a fourth type
 * for a different reason again — it is not player state at all, and it is never written to disk.
 *
 * <h2>Where the state actually lives</h2>
 * On the player entity ({@code ServerPlayer}) — so it is written to {@code playerdata/<uuid>.dat}
 * and is therefore <b>shared across dimensions</b>: a player who walks through a nether portal keeps
 * their roster (M2 acceptance). Nothing here caches data in a static field; the only statics are the
 * registry handles themselves, which hold no per-player state.
 *
 * <h2>The exact builder shape used (1.21.1 / NeoForge 21.1.265)</h2>
 * Read from {@code AttachmentType} in the sources jar rather than guessed:
 *
 * <pre>{@code
 * DeferredRegister<AttachmentType<?>> r = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MOD_ID);
 * DeferredHolder<AttachmentType<?>, AttachmentType<Roster>> h =
 *         r.register("roster", () -> AttachmentType.builder(Roster::empty).serialize(Roster.SERIALIZER).build());
 * }</pre>
 *
 * <p>Note {@code serialize(Roster.SERIALIZER)}, <b>not</b> {@code serialize(Roster.CODEC)}. The
 * codec overload reports a decode failure by throwing, and {@code AttachmentHolder} reacts by
 * dropping that attachment from the rebuilt persistence compound — so for a versioned structure the
 * codec overload <b>deletes</b> a save it cannot parse. Any future persistent attachment here that
 * carries a version field needs the same treatment; see {@link Roster#SERIALIZER}.
 *
 * <ul>
 *   <li>{@code create(ResourceKey<? extends Registry<T>>, String namespace)} is the factory; the key
 *       is {@code NeoForgeRegistries.Keys.ATTACHMENT_TYPES}, since attachments are a NeoForge
 *       registry, not a vanilla one.</li>
 *   <li>{@code AttachmentType.builder(Supplier<T>)} takes the <b>default value supplier</b>, not a
 *       serializer. The serializer is added by {@code Builder.serialize(IAttachmentSerializer<?, T>)}
 *       or its {@code serialize(Codec<T>)} overload — the codec overload wraps itself in an
 *       {@code IAttachmentSerializer} that uses {@code provider.createSerializationContext(NbtOps.INSTANCE)},
 *       i.e. the save data really is NBT.</li>
 *   <li>{@code DeferredRegister.register(String, Supplier<? extends I>)}; the holder type is
 *       {@code DeferredHolder<AttachmentType<?>, AttachmentType<Roster>>}.</li>
 * </ul>
 *
 * <p>No {@code .sync(...)} yet: syncing to clients is a later task's concern and adding it here
 * would ship a stream codec nothing reads.
 */
@ApiStatus.Internal
public final class Attachments {

    /** All three attachment types, registered together on the mod bus. */
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Astrum.MOD_ID);

    /**
     * Owned characters and their progress. Persisted; carries {@code dataVersion}, so a save written
     * by a future build is quarantined rather than misread or destroyed ({@link Roster#SERIALIZER}).
     *
     * <p>Uses a hand-written {@link net.neoforged.neoforge.attachment.IAttachmentSerializer} instead
     * of {@code serialize(Codec)}: the codec overload turns a decode failure into an exception, and
     * {@code AttachmentHolder} responds by dropping the tag and rebuilding the persistence compound
     * without it — i.e. a plain rejecting codec <b>wipes</b> an unreadable save instead of keeping
     * it. The wrapper captures the raw tag so a downgrade round-trips it untouched.
     *
     * <p>{@code copyOnDeath()} is required — see {@link #PARTY} for the mechanism and the
     * measurement.
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Roster>> ROSTER =
            ATTACHMENT_TYPES.register("roster",
                    () -> AttachmentType.builder(Roster::empty).serialize(Roster.SERIALIZER)
                            .copyOnDeath().build());

    /**
     * Five ordered wheel slots. Persisted, and copied on death.
     *
     * <h2>Why {@code copyOnDeath()} is mandatory here and on {@link #ROSTER}</h2>
     * Death does not re-use the player entity. {@code PlayerList.respawn} builds a
     * <b>new</b> {@code ServerPlayer} and calls {@code restoreFrom(original, false)}; that fires
     * {@code PlayerEvent.Clone} with {@code wasDeath = true}, and NeoForge's handler copies only
     * attachments whose type opted in via {@code copyOnDeath()} (see {@code AttachmentInternals
     * .copyEntityAttachments}, filter {@code isDeath ? type -> type.copyOnDeath : type -> true}).
     * The old entity — and with it an uncopied attachment map — is then discarded.
     *
     * <p>The overwrite is persisted on the <b>next</b> save, not by the respawn itself:
     * {@code PlayerList.respawn} never writes playerdata. What actually writes it is the periodic
     * autosave, an explicit {@code /save-all}, or the logout save — all of which serialize the
     * surviving entity's map. So the loss is silent for a while and then becomes permanent, which
     * is worse than an immediate failure.
     *
     * <p>An earlier version of this class argued the opposite — that the party "travels with the
     * playerdata file" and that copying would be a competing source of truth. That premise is
     * wrong: nothing reads the file back mid-session, the surviving entity never consults it, and
     * the file is later rewritten from the in-memory (empty) map. There is only one source of
     * truth, and {@code copyOnDeath()} is what carries it across the entity swap. Measured on a
     * real server run: without it, a roster of two characters and a party in slot 3 came back as
     * {@code entries:{}} and five empty slots, and that is what got saved.
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Party>> PARTY =
            ATTACHMENT_TYPES.register("party",
                    () -> AttachmentType.builder(Party::empty).serialize(Party.CODEC)
                            .copyOnDeath().build());

    /**
     * UB charge, selected slot and cooldown. Persisted, but tiny and written per change — the reason
     * it is not folded into {@link #ROSTER}.
     *
     * <p>Deliberately <b>not</b> {@code copyOnDeath()}: unlike the roster and the party, this is
     * runtime state, and dying resetting the charge bar and the cooldown is the conventional
     * behaviour rather than lost progress. The cost is that {@code selectedSlot} also returns to 0;
     * that is a UI nicety and does not justify copying a full charge bar through a death, which
     * would hand the player an immediate ultimate on respawn.
     */
    /**
     * <b>Which dishes this player has fed on which game day</b> — the state behind 「每游戏日一次」.
     *
     * <p>Persisted, and {@code copyOnDeath()}: the rule is per <b>game day</b>, not per life, so dying must not
     * hand the allowance back. Its size is bounded by the number of dish items, not by elapsed days — see
     * {@link FedDishes}.
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<FedDishes>> FED_DISHES =
            ATTACHMENT_TYPES.register("fed_dishes",
                    () -> AttachmentType.builder(FedDishes::empty).serialize(FedDishes.CODEC)
                            .copyOnDeath().build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<UbState>> UB_STATE =
            ATTACHMENT_TYPES.register("ub_state",
                    () -> AttachmentType.builder(UbState::fresh).serialize(UbState.CODEC).build());

    /**
     * The stack state of one target entity, keyed by (attacker uuid, stack kind) (spec §4.4).
     *
     * <h2>{@code serialize = false}, and it is not a .build() omission</h2>
     * This type calls <b>no</b> {@code .serialize(...)} at all. That is the spec's "serialize = false":
     * {@code AttachmentHolder.serializeAttachments} skips every type whose {@code serializer} is
     * {@code null} (measured in the 21.1.265 sources — the loop is
     * {@code if (type.serializer != null) { ... tag.put(key, write(...)); } }), so this attachment can
     * never reach {@code playerdata} or a region file. The value is a runtime cache of a fight in
     * progress, and a stack that survives a restart would be a stack from a fight nobody remembers.
     *
     * <p>Being unserialized also makes it un-copyable: {@code copyOnDeath()} and {@code copyHandler()}
     * both throw when the type has no serializer, so the "did I forget to copy it?" failure mode cannot
     * be reached. Mobs are not respawned, so nothing needs copying anyway.
     *
     * <p>Consequence worth knowing: an entity that unloads forgets its stacks. That is at least as
     * strict as the lazy expiry in {@code StackRuntime}, and it needs no cleanup path — which is also
     * why no tick listener exists that could sweep it.
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<StackTable>> STACK =
            ATTACHMENT_TYPES.register("stack",
                    () -> AttachmentType.builder(StackTable::new).build());

    /**
     * One player's {@code combo_n} chain (see {@link ComboState}).
     *
     * <p>Also {@code serialize = false}, and for the same reason as {@link #STACK}: a hit chain is a
     * property of the fight in progress. A chain that survived a restart would pay out
     * {@code combo_n} on the first hit of a new session, which is not what "three hits in a row" means.
     *
     * <p>Lives on the <b>player</b> rather than on the target, because a combo is the attacker's rhythm
     * across whatever it is hitting (the convention is stated on {@code TriggerFacts}).
     *
     * <p>Not copied on death, which is the conventional reading: dying ends your combo.
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<ComboState>> COMBO =
            ATTACHMENT_TYPES.register("combo",
                    () -> AttachmentType.builder(() -> ComboState.EMPTY).build());

    /**
     * Our share of one entity's absorption — the pool that lets a {@code SHIELD} effect <b>add to</b>
     * vanilla's golden-apple absorption rather than being cancelled by it.
     *
     * <p>Also {@code serialize = false}, for the reason {@link ShieldState} gives: it only means anything
     * while the effect that granted it is still ticking, so a pool restored from disk would describe a
     * shield the game has already expired.
     *
     * <p>Lives on the entity that <em>carries</em> the shield (a player or a mob), because absorption is
     * per-body. That also makes "did I forget to clean it up?" a non-question: the effect's own expiry
     * clears it, and an entity that unloads takes its pool with it — the same argument {@link #STACK} makes.
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<ShieldState>> SHIELD_POOL =
            ATTACHMENT_TYPES.register("shield_pool",
                    () -> AttachmentType.builder(() -> ShieldState.NONE).build());

    /**
     * One player's 「战斗开始」 boundary: the tick of the last blow she landed (see {@link BattleStartState}).
     *
     * <p>Also {@code serialize = false}, and for the same reason as {@link #COMBO}: whether a fight is in
     * progress is a property of the session, not of the save. A boundary restored from disk would describe a
     * fight that stopped when the server did — and, worse, it would make the <b>first</b> fight of the next
     * session invisible, because the stored tick would still be the last blow of the previous one.
     *
     * <h2>What that costs, stated rather than hidden</h2>
     * The <em>marks</em> a {@code battle_start} hook grants are ordinary effect instances on the player, and
     * vanilla saves those with the player, so a relog keeps the tally while it clears the boundary. A player
     * can therefore log out and back in to have her first blow count as a new fight and top the tally up. That
     * is bounded twice — by the counter's own rung cap ({@code Counters.capForTier}: 12 for 雪菲's tally) and
     * by the grant's duration (180 s) — and the alternative is worse: a persisted tick would let a stale
     * timestamp suppress a real fight's opening, and would outlive the buff it gates.
     *
     * <p>No {@code copyOnDeath()}, and it is not an omission: an unserialized type cannot be copied (NeoForge
     * throws from {@code copyOnDeath()} when there is no serializer), and a death is exactly the moment a new
     * fight should begin. Vanilla clears the effect instances on death anyway, so the respawned player starts
     * from an empty tally and the next blow opening a fight is the honest read — not a farm, because the marks
     * she had are gone.
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<BattleStartState>> BATTLE_START =
            ATTACHMENT_TYPES.register("battle_start",
                    () -> AttachmentType.builder(() -> BattleStartState.EMPTY).build());

    private Attachments() {
    }

    /**
     * Subscribes these registrations to the mod event bus.
     *
     * <p>Must be on the mod bus, not {@code NeoForge.EVENT_BUS}: attachment types are a registry and
     * {@code DeferredRegister} adds its listeners to whatever bus it is given.
     *
     * @param modBus the mod-specific event bus
     */
    public static void register(IEventBus modBus) {
        ATTACHMENT_TYPES.register(modBus);
        // The party's visible half: five per-slot effects that make the equipped characters appear in
        // vanilla's effect HUD, and the two events that restore them after a login or a death. Wired
        // here rather than from Astrum so the entry point keeps one call per subsystem and stays
        // wiring-only; the pairing is genuine — the effects exist to show PARTY and nothing else.
        PartyEffects.register(modBus);
        PartyBuffEvents.register();
    }
}
