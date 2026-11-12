package com.astrum.pcrd.impl.attach;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.combat.StackTable;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jetbrains.annotations.ApiStatus;

/**
 * Registration of the four attachment types: three persistent player-state ones and one runtime-only
 * stack cache.
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
    }
}
