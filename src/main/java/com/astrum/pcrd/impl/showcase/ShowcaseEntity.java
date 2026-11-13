package com.astrum.pcrd.impl.showcase;

import com.astrum.pcrd.Astrum;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * The 演出 (showcase) entity: a short-lived, non-interactive figure that exists only to play the
 * animation of one ultimate (spec §5.6).
 *
 * <h2>What it is allowed to do</h2>
 * Nothing. Spec §5.6 lists the three things that must be right, and all three are "it must be
 * absent" from the player's point of view:
 *
 * <ol>
 *   <li><b>Not interactive.</b> No collision ({@code noPhysics}), no gravity
 *       ({@link #isNoGravity()}), not pushable ({@link #isPushable()}), not pickable
 *       ({@link #isPickable()}), not attackable ({@link #isAttackable()}), and {@link #hurt} answers
 *       {@code false} without starting a damage sequence. A figure that blocked a doorway or ate a
 *       hit would be a gameplay bug wearing a cutscene's clothes.</li>
 *   <li><b>Bounded lifetime.</b> {@link #tick()} does exactly one thing: ask
 *       {@link ShowcaseLifetime#shouldDespawn} and discard when it says so. There is no
 *       {@code aiStep}, no navigator, no goals — it is an {@link Entity}, not a {@code Mob} — so it
 *       costs one branch per tick, and it cannot be "just about to despawn" forever because the
 *       timeout half of that predicate does not depend on the animation.</li>
 *   <li><b>Server-spawned, client-rendered.</b> The entity is created and added on the logical
 *       server ({@code ShowcaseManager}); the client only ever receives it through the ordinary
 *       entity-tracking path and draws it with {@code impl.client.ShowcaseRenderer}. Nothing here
 *       calls into client code, which is also what keeps this file legal outside
 *       {@code impl/client} (guard rule).</li>
 * </ol>
 *
 * <h2>Why the animation name is synched data and not a field</h2>
 * The server decides <em>which</em> animation plays (the tier's {@code animation}, so the enhanced
 * ultimate can play a different one) and the client has to know it. A plain Java field would be
 * {@code null} on the client — entities are constructed client-side from a spawn packet and only
 * synched data crosses that boundary. So the name is a {@link SynchedEntityData} string, and the
 * client's {@link #registerControllers} reads it to pick the animation. That is also why the name
 * has to be non-null on the client: {@link #ANIMATION_FALLBACK} is the value an unknown or empty
 * name becomes, so a bad datapack cannot make the renderer throw.
 *
 * <h2>What is deliberately missing</h2>
 * No {@code readAdditionalSaveData} payload: the entity is {@code noSave()} at the entity type, so
 * it must never be in a save in the first place (spec §5.6: 不参与世界 tick 逻辑 — and a saved
 * cutscene would be exactly that, resurrected on the next world load). {@link #addAdditionalSaveData}
 * is still written and still correct, because a corrupted world could contain one; it just writes
 * nothing worth restoring.
 */
@ApiStatus.Internal
public class ShowcaseEntity extends Entity implements GeoEntity {

    /**
     * The animation one cast plays: the clip the tier named, or {@link #ANIMATION_CAST} when the tier
     * named one the shipped file does not declare.
     *
     * <p>Spec §5.6 lets a tier name its own animation — {@code tempest_blade} for the base ultimate,
     * {@code seiga_itten} for the enhanced one — and the placeholder art ships both, plus
     * {@code ub_cast} as the generic fallback and {@code idle} as the loop that follows. A datapack may
     * still name something else, and that name cannot be played: GeckoLib would look it up in the file
     * and find nothing, on a client that has no way to ask the datapack what it meant. So the name is
     * checked against {@link #SHIPPED_CLIPS} here, on the server, and an unknown one becomes
     * {@link #ANIMATION_CAST} with the declared name kept in {@link #declaredAnimation()} for the log.
     * A data author therefore sees the substitution in {@code latest.log} instead of a figure that
     * silently does not move.
     */
    public static final java.util.Set<String> SHIPPED_CLIPS = java.util.Set.of(
            "ub_cast",
            "idle",
            "tempest_blade",
            "seiga_itten");

    /** The generic cast clip: what {@link #SHIPPED_CLIPS} does not recognise falls back to. */
    public static final String ANIMATION_CAST = "ub_cast";

    /** The clip a showcase plays when nothing else applies; the same clip as the generic cast one. */
    public static final String ANIMATION_FALLBACK = ANIMATION_CAST;

    /** The idle loop, played after the cast clip so a long-lived showcase does not freeze mid-swing. */
    public static final String ANIMATION_IDLE = "idle";

    /**
     * Which animation this showcase plays, synched so the client can read it.
     *
     * <p>Always one of the clips the shipped file declares (currently only {@link #ANIMATION_CAST}):
     * a name that is not in the file cannot be played, and the client has no way to ask the datapack
     * what it meant. Empty resolves to {@link #ANIMATION_FALLBACK}, so an entity spawned by a code
     * path that forgot to configure still renders.
     */
    private static final EntityDataAccessor<String> DATA_ANIMATION =
            SynchedEntityData.defineId(ShowcaseEntity.class, EntityDataSerializers.STRING);

    /**
     * The animation name the tier asked for, kept for the log and for a future per-tier clip.
     *
     * <p>Server-side only and not synched: it is a diagnostic, not something the client acts on. The
     * whole point of keeping it is that the substitution above is <b>visible</b> — a data author who
     * wrote {@code tempest_blade} sees in the log that {@code ub_cast} was played instead, rather
     * than wondering why their animation never appeared.
     */
    private String declaredAnimation = ANIMATION_FALLBACK;

    /** GeckoLib's per-instance animation state. */
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    /**
     * How many ticks the animation this entity was spawned for runs.
     *
     * <p>Server-side only, and {@code transient}-by-omission: it participates in
     * {@link #tick()}'s despawn decision, and the client never makes that decision (the server
     * removes the entity and the client obeys). Not synched for the same reason — one fewer value on
     * the wire for something the client cannot act on.
     */
    private int animationTicks;

    /**
     * What settles this cast's damage, run from the entity's own {@link #tick()} at the strike frame
     * and again — never twice — when the entity leaves the level.
     *
     * <p>Carried on the entity rather than in a side table for the same reason {@link #owner} is: the
     * fact is about one cast, and it must die with the entity. A side table would need pruning per
     * cast, which is the leak Task 13's probe already caught once.
     *
     * <p>{@code null} means "this showcase settles nothing", which is the honest default for an
     * entity constructed by the entity type (a corrupted save, a probe, a future caller that only wants
     * the visual). It is <b>not</b> synched: the client never decides damage.
     */
    private Runnable onStrike;

    /**
     * The one-shot latch shared by the two moments that may settle ({@code null} before
     * {@link #settleDamageOnStrike}).
     *
     * <p>The strike frame is the ordinary moment and the removal is the fallback for a clip that was
     * cut short. Both ask the same latch, so an interrupted ultimate deals its damage exactly once and
     * a normal one is not settled a second time when its entity despawns 4 ticks later.
     */
    private com.astrum.pcrd.impl.ub.SettleOnce settlement;

    /**
     * The player whose ultimate this showcase plays, or {@code null} for none.
     *
     * <p>Held on the entity rather than in a side table keyed by entity id, so it cannot outlive the
     * entity: when the showcase despawns the fact goes with it, and the "owner left" cleanup can
     * simply walk the level's showcases. A side table would need pruning, and a missed prune is a
     * record that grows once per cast for the whole session — measured once, then designed out.
     *
     * <p>Server-side only and not synched: the client has no use for it (it never decides anything),
     * and the spawn packet stays one string.
     */
    private java.util.UUID owner;

    /**
     * Creates the entity. Called by {@code EntityType} (both sides) and by
     * {@link ShowcaseManager}; the constructor only sets the two flags that make it harmless, so an
     * entity can never exist for even one tick in a pushable state.
     *
     * @param type  the registered type
     * @param level the level it belongs to
     */
    public ShowcaseEntity(EntityType<? extends ShowcaseEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
        this.setInvulnerable(true);
    }

    /**
     * Records whose ultimate this is, so the logout cleanup can find it.
     *
     * @param owner the caster's UUID
     */
    public void setOwner(java.util.UUID owner) {
        this.owner = owner;
    }

    /** {@return the caster whose ultimate this plays, or {@code null} when unset} */
    public @Nullable java.util.UUID owner() {
        return this.owner;
    }

    /**
     * {@return the clip a declared animation name resolves to}
     *
     * <p>The one place that decision is made, so {@link #configure} (which stores the answer) and any
     * caller that wants to know what a name would play cannot disagree. Pure and public so the
     * derivation is testable and loggable without an entity.
     *
     * @param declared the animation name a tier declared, possibly {@code null}
     */
    public static String declaredClipOrDefault(@Nullable String declared) {
        if (declared == null || declared.isBlank()) {
            return ANIMATION_CAST;
        }
        return SHIPPED_CLIPS.contains(declared) ? declared : ANIMATION_CAST;
    }

    /**
     * Tells this showcase what to play and for how long.
     *
     * <p>Called by {@link ShowcaseManager} on the server right after construction, before the entity
     * is added — so the synched value is already correct in the spawn packet and the client never
     * renders a frame of the wrong animation.
     *
     * @param declared the animation name the tier asked for; recorded, and played when
     *                 {@link #SHIPPED_CLIPS} contains it, otherwise {@link #ANIMATION_CAST} is played
     * @param ticks    how long that animation runs, in ticks; {@code 0} means "unknown", which leaves
     *                 the hard lifetime cap as the only clock
     */
    public void configure(String declared, int ticks) {
        this.declaredAnimation = declared == null || declared.isBlank() ? ANIMATION_CAST : declared;
        this.entityData.set(DATA_ANIMATION, declaredClipOrDefault(declared));
        this.animationTicks = Math.max(0, ticks);
    }

    /** {@return the animation this showcase plays, never {@code null}} */
    public String animation() {
        String name = this.entityData.get(DATA_ANIMATION);
        return name == null || name.isBlank() ? ANIMATION_FALLBACK : name;
    }

    /** {@return the animation name the tier declared, for the log and for a future per-tier clip} */
    public String declaredAnimation() {
        return this.declaredAnimation;
    }

    /** {@return the animation length this showcase was spawned with, in ticks} */
    public int animationTicks() {
        return this.animationTicks;
    }

    /** {@return the tick at which this showcase's clip reads as having landed} */
    public int strikeTick() {
        return com.astrum.pcrd.impl.ub.UbDamage.strikeTick(this.animationTicks);
    }

    /**
     * Arms this showcase to settle one ultimate's damage at its strike frame.
     *
     * <p>Called by {@code ShowcaseManager} right after {@link #configure}, before the entity is added,
     * so the settlement is armed before the entity can tick even once — an entity that ticked first
     * would miss a two-tick clip's strike frame.
     *
     * @param settlement what to run, exactly once; {@code null} disarms it
     */
    public void settleDamageOnStrike(Runnable settlement) {
        if (settlement == null) {
            this.onStrike = null;
            this.settlement = null;
            return;
        }
        this.onStrike = settlement;
        this.settlement = new com.astrum.pcrd.impl.ub.SettleOnce();
    }

    /** {@return whether this showcase still owes its damage; for the log and for probes} */
    public boolean damagePending() {
        return this.settlement != null && this.settlement.isPending();
    }

    /**
     * Runs the armed settlement if this tick is the strike frame, and only once per cast.
     *
     * <p>This is the whole reason the settlement lives on the entity: the moment "the animation has
     * landed" is the entity's own age against its own clip, and it needs no second clock. The guard is
     * {@link com.astrum.pcrd.impl.ub.SettleOnce}, shared with the removal fallback below.
     */
    private void settleAtStrikeFrame() {
        if (this.onStrike == null || this.settlement == null || !this.settlement.isPending()) {
            return;
        }
        if (this.tickCount < strikeTick()) {
            return;
        }
        settleNow("strike frame at tick " + this.tickCount);
    }

    /**
     * Settles the cast's damage now, if it is still owed.
     *
     * <p>Split from {@link #tick()} so the two moments (strike frame, removal) and the no-entity path in
     * {@code ShowcaseManager} all go through one guarded call. The latch is taken <b>before</b> the work
     * runs, so a damage listener that reached back into this cast could not re-enter it; a throwing run
     * still counts as spent, because whatever landed before the failure is on the victim's health
     * already.
     *
     * @param moment why it is being settled now, for the log
     */
    private void settleNow(String moment) {
        if (this.onStrike == null || this.settlement == null || !this.settlement.claim()) {
            return;
        }
        try {
            Astrum.LOGGER.debug("astrum: showcase {} settling its UB damage at the {}",
                    this.getId(), moment);
            this.onStrike.run();
        } finally {
            this.settlement.done();
        }
    }

    // ------------------------------------------------------------------ lifetime

    /**
     * One tick: decide whether to go away, and nothing else.
     *
     * <p>Overridden instead of using {@code baseTick}/{@code super.tick()}: the parent's tick
     * handles water, fire, portals, passengers and freezing, none of which may apply to a figure
     * that must not fall, drown or be moved. Skipping it also keeps the entity off the world's
     * physics entirely, which is spec §5.6's 不参与世界 tick 逻辑.
     *
     * <p><b>{@code tickCount} is not incremented here.</b> {@code ServerLevel.tickNonPassenger}
     * already does it — before calling {@code tick()} — so an increment here made the showcase age
     * twice as fast as the world. Measured: a 28-tick animation reported despawn "at tick 32" after
     * only 16 server ticks. {@link #getTick} and {@link ShowcaseLifetime} both read
     * {@code tickCount}, so the entity's age is the world's age of that entity, which is what the
     * despawn numbers in the report were derived from.
     */
    @Override
    public void tick() {
        if (this.level().isClientSide) {
            return;
        }
        settleAtStrikeFrame();
        if (ShowcaseLifetime.shouldDespawn(
                this.tickCount, this.animationTicks, ShowcaseLifetime.MAX_LIFETIME_TICKS)) {
            Astrum.LOGGER.debug("astrum: showcase {} despawning at tick {} (animation {}t, cap {}t)",
                    this.getId(), this.tickCount, this.animationTicks,
                    ShowcaseLifetime.MAX_LIFETIME_TICKS);
            this.discard();
        }
    }

    /**
     * The fallback moment: a showcase that leaves the level before its strike frame still settles.
     *
     * <p>Three real ways to get here without a strike: the owner logged out, the server stopped, the
     * chunk unloaded, or the hard deadline fired on an unknown clip length. Without this the ultimate
     * would spend the bar, play half a clip, and deal <b>nothing</b> — spec §5.6's 动画被打断就丢伤害.
     *
     * <p>Deliberately on the entity's own lifecycle rather than on a level tick: {@code setRemoved} →
     * {@code levelCallback.onRemove} → {@link #onRemovedFromLevel()} happens once, synchronously, for
     * every removal path, and costs one virtual call. The latch makes "once" true across both moments.
     */
    @Override
    public void onRemovedFromLevel() {
        if (!this.level().isClientSide) {
            settleNow("interrupted clip (entity removed)");
        }
    }

    /**
     * Never moves, and says so every tick.
     *
     * <p>Belt and braces next to {@code noPhysics} + {@code isNoGravity()}: a caller (a piston, a
     * fluid, a future mod's knockback) that reaches the ordinary movement path gets a zero delta
     * rather than a drifting cutscene.
     */
    @Override
    public void move(MoverType type, Vec3 movement) {
        // intentionally empty: a showcase is placed once and stays exactly where it was placed
    }

    // ------------------------------------------------------------------ not interactive

    /** A showcase is not a physical obstacle: players and mobs walk through it. */
    @Override
    public boolean isPushable() {
        return false;
    }

    /** Not selectable by the crosshair, so it can never show a name tag or take an interaction. */
    @Override
    public boolean isPickable() {
        return false;
    }

    /** Not attackable (spec §5.6: 不可被攻击). */
    @Override
    public boolean isAttackable() {
        return false;
    }

    /** Immune to every damage source, including {@code /kill}-style bypasses that ignore invulnerability. */
    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return true;
    }

    /** Takes no damage and starts no damage sequence, whatever asks. */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    /** Cannot be pushed by another entity. */
    @Override
    public void push(Entity other) {
        // intentionally empty: a showcase must stay where the cast put it
    }

    /** Fire does not spread, nothing is damaged by it, and it does not burn away. */
    @Override
    public boolean fireImmune() {
        return true;
    }

    /** Never collides with blocks, so it cannot be shoved out of a wall or into a void. */
    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    /** No bounding box in the world's collision pass; the render box comes from {@code sized()} instead. */
    @Override
    public boolean isColliding(net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.state.BlockState state) {
        return false;
    }

    // ------------------------------------------------------------------ GeckoLib

    /**
     * One controller: the cast clip once, then {@link #ANIMATION_IDLE} looping while the entity is
     * still alive.
     *
     * <p>The follow-up loop matters. The cast clips are {@code hold_on_last_frame} and last ~1.4 s
     * while the entity may live up to 6 s on the timeout path, so without it a long-lived showcase
     * would freeze mid-swing — which reads as a broken renderer rather than as a deliberate pose.
     *
     * <p>The switch is driven by the entity's own {@code tickCount} against the animation length it was
     * configured with, not by {@code AnimationState.getAnimationTick()}: the state's tick is in
     * animation time (seconds of the clip, scaled by the controller speed) while the lifetime is in
     * server ticks, and mixing the two is how a showcase would either loop too early or never. When no
     * length is known ({@code 0}), the cast clip simply holds and the timeout ends the entity.
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "showcase", 0, state -> {
            if (this.animationTicks > 0 && this.tickCount >= this.animationTicks) {
                return state.setAndContinue(RawAnimation.begin().thenLoop(ANIMATION_IDLE));
            }
            return state.setAndContinue(RawAnimation.begin().thenPlay(animation()));
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    /**
     * {@return the tick GeckoLib animates against}
     *
     * <p>{@code tickCount} rather than {@code level().getGameTime()}: the animation must start at
     * the entity's own creation (that is what makes the cast land in sync with the spawn), and it
     * must stop advancing when this entity is not ticking.
     */
    @Override
    public double getTick(Object object) {
        return this.tickCount;
    }

    // ------------------------------------------------------------------ data

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_ANIMATION, ANIMATION_FALLBACK);
    }

    /**
     * Writes nothing: the entity type is {@code noSave()}, so a saved showcase is already a bug.
     *
     * <p>Implemented rather than left to throw because a world corrupted by an older build, or by a
     * future one that changes its mind about saving, must not crash on load — it just gets an
     * ordinary showcase that despawns on its cap.
     */
    @Override
    protected void readAdditionalSaveData(CompoundTag data) {
        // intentionally empty: see the javadoc — this entity is never meant to be loaded from a save
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag data) {
        // intentionally empty: no state worth restoring, and noSave() means nobody should ask
    }

    /**
     * {@return where a showcase for {@code caster} should be placed}
     *
     * <p>In front of the caster, at their feet height, one fixed short hop out — close enough to read
     * as "the player's ultimate", far enough not to sit inside the player's own body. The offset is
     * computed from the caster's look direction with flat trigonometry rather than by asking for a
     * ray-cast hit, so it never lands inside a wall the caster is standing against: a showcase gives
     * no collision, so overlapping a block would only look wrong, and a fixed short hop is
     * predictable in a way a hit position is not.
     *
     * <p>Pure and package-visible so {@code ShowcaseLifetimeTest} can pin the placement maths without
     * a level.
     *
     * @param casterX caster's x
     * @param casterY caster's y (feet)
     * @param casterZ caster's z
     * @param yawDeg  caster's yaw, in degrees, as {@code Entity.getYRot()} reports it
     */
    static Vec3 placement(double casterX, double casterY, double casterZ, float yawDeg) {
        double radians = Math.toRadians(yawDeg);
        double dx = -Math.sin(radians) * PLACEMENT_DISTANCE;
        double dz = Math.cos(radians) * PLACEMENT_DISTANCE;
        return new Vec3(casterX + dx, casterY + PLACEMENT_HEIGHT, casterZ + dz);
    }

    /** How far in front of the caster a showcase stands, in blocks. */
    public static final double PLACEMENT_DISTANCE = 1.25;

    /** How high above the caster's feet a showcase is anchored, in blocks (roughly knee/hip height). */
    public static final double PLACEMENT_HEIGHT = 0.0;

    /**
     * Places this showcase in front of the caster.
     *
     * @param caster the player whose ultimate fired
     */
    public void placeInFrontOf(net.minecraft.server.level.ServerPlayer caster) {
        Vec3 spot = placement(caster.getX(), caster.getY(), caster.getZ(), caster.getYRot());
        this.moveTo(spot.x, spot.y, spot.z, caster.getYRot(), 0.0F);
    }

    /**
     * {@return whether this entity is still alive in a server level}
     *
     * <p>{@code !isRemoved() && isAlive()}, in one place: the two flags disagree in exactly the
     * window a despawn is happening, and counting a half-removed entity towards
     * {@code maxActiveShowcaseEntities} would let one stale showcase block a new cast.
     */
    public boolean isActive() {
        return !this.isRemoved() && this.isAlive() && this.level() instanceof ServerLevel;
    }
}
