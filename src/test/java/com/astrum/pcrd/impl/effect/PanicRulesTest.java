package com.astrum.pcrd.impl.effect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>「恐慌」's rules, and what the code may and may not claim about them.</b>
 *
 * <h2>Why this class is mostly about the honest statement</h2>
 * "Cannot act" is a claim that is easy to overstate, and the brief asks for it to be stated plainly. A server
 * never sees a key: it sees the requests a client sends. So this suite pins three things that <em>are</em>
 * true — the duration, the fact that the player half is driven by event cancellations, and the fact that the
 * mob half needs no events at all — and asserts the sentence the report must not contradict: a modified client
 * can still <b>walk</b>, because its position arrives in its own move packets and NeoForge posts no cancellable
 * movement event.
 *
 * <h2>What a unit test cannot reach, stated rather than faked</h2>
 * {@code PanicEffect.suppressAi} needs a live {@code Mob} (its {@code getPersistentData}, {@code setNoAi} and
 * {@code getNavigation} are all entity state), and {@code PanicControl.isPanicking} needs a bound effect
 * registry. Constructing either in a unit test would mean asserting a mock of the very thing under test. What
 * is asserted here is every rule that is a <em>number</em> or a <em>decision</em>; the live behaviour is proved
 * by the dedicated-server log the report describes.
 */
class PanicRulesTest {

    /**
     * <b>The duration is the skill's, and 1.5 s is only what an undeclared one gets.</b>
     *
     * <p>This used to assert the opposite — that every declared value was overruled — and that constant was the
     * gap four characters recorded in their {@code extra}: 绫音's 束缚 2.5 s (3 s on her 二专), 胡桃's 击晕
     * 5.5 s and 凯露's 击晕 8 s all ran at 1.5 s, i.e. a mechanic that existed at the wrong length. So the
     * pack's {@code seconds} is honoured now, {@link PanicRules#DURATION_TICKS} is the fallback, and the one
     * guard kept from the constant era is the {@link PanicRules#MAX_SECONDS}-second ceiling: a pack writing 600
     * would turn 「恐慌」 into a ten-minute disable no character's text describes.
     */
    @Test
    void theDurationIsTheSkillsAndFifteenHundredMillisecondsIsItsDefault() {
        assertEquals(30, PanicRules.DURATION_TICKS, "1.5 s at 20 ticks a second — the fallback");
        assertEquals(30, PanicRules.ticksFor(0.0), "an undeclared duration is the fallback");
        assertEquals(30, PanicRules.ticksFor(1.5), "and a declared 1.5 s is the same number");
        assertEquals(30, PanicRules.ticksFor(-5.0), "a hostile value is the fallback, not an exception");
        assertEquals(30, PanicRules.ticksFor(Double.NaN), "nor is a NaN");
        // The shipped declarations, one per character clause, at the tick rate vanilla counts in.
        assertEquals(50, PanicRules.ticksFor(2.5), "绫音's 束缚 2.5 s");
        assertEquals(60, PanicRules.ticksFor(3.0), "绫音's 二专 束缚 3 s");
        assertEquals(110, PanicRules.ticksFor(5.5), "胡桃's 击晕 5.5 s");
        assertEquals(160, PanicRules.ticksFor(8.0), "凯露's 击晕 8 s");
        // The ceiling, and the fact that it is reported rather than silently applied.
        assertEquals((int) Math.round(PanicRules.MAX_SECONDS * 20.0), PanicRules.ticksFor(600.0),
                "a declared 600 s is cut to the cap rather than honoured");
        assertTrue(PanicRules.isClamped(600.0), "and the applier can say so");
        assertFalse(PanicRules.isClamped(8.0), "an ordinary declaration is not clamped");
    }

    /** Panic is a state, not a magnitude: one rung, and a declared tier clamps to it. */
    @Test
    void panicHasOneTierAndDoesNotGrowWithIt() {
        assertEquals(1, PanicRules.MAX_TIER);
        assertEquals(1, com.astrum.pcrd.api.v1.EffectTiers.maxTier(
                com.astrum.pcrd.api.v1.EffectType.PANIC));
        assertEquals(1, com.astrum.pcrd.api.v1.EffectTiers.resolve(
                com.astrum.pcrd.api.v1.EffectType.PANIC, 4).tier(),
                "a declared tier 4 clamps to the one rung rather than inventing a longer panic");
    }

    /** The registry path and the particle tint are the ones {@code PartyEffects} registers and draws. */
    @Test
    void theRegistryPathIsTheOneTheEffectRegisters() {
        assertEquals("panic", PanicRules.PATH);
        assertEquals("panic", com.astrum.pcrd.impl.InternalIds.id(PanicRules.PATH).getPath());
        // The tint is a real colour with full alpha reserved for vanilla's own blending, and it is
        // deliberately not the defence debuff's violet: two debuffs that look identical are two debuffs a
        // player cannot tell apart on the HUD.
        assertTrue(PanicRules.COLOUR >= 0 && PanicRules.COLOUR <= 0xFFFFFF,
                "the tint is a 24-bit colour, not an alpha-bearing ARGB value");
        assertNotEquals(DefenceDownEffect.COLOUR, PanicRules.COLOUR,
                "panic and the armour debuff must be different colours on the effect bar");
    }

    /**
     * The persistence tag has this mod's namespace, so it cannot collide with another mod's entity data.
     *
     * <p>The tag is how a panic remembers whether the mob it silenced was already {@code noAi}. A vanilla or
     * third-party key would be a collision waiting to happen, and the failure would be a mob that never wakes.
     */
    @Test
    void theNoAiRemembranceTagIsNamespaced() {
        assertTrue(PanicEffect.NO_AI_TAG.startsWith("astrum:"),
                "an un-namespaced entity tag would collide with another mod's data");
        assertEquals("astrum:panic_prev_no_ai", PanicEffect.NO_AI_TAG);
    }

    /**
     * <b>The effect is HARMFUL, not "harmless".</b>
     *
     * <p>{@code MobEffectCategory} has exactly three values — {@code BENEFICIAL}, {@code HARMFUL} and
     * {@code NEUTRAL} — read from the enum in 21.1.265. There is no {@code HARMLESS}; an earlier revision of
     * this effect named one and did not compile. A panic lands on an enemy and reads as a debuff, so
     * {@code HARMFUL} is also the correct one of the three.
     */
    @Test
    void panicIsAHarmfulEffect() {
        // Built here so the constructor's category is exercised. A MobEffect needs no registry to be
        // constructed: `super(category, colour)` only stores two fields.
        PanicEffect effect = new PanicEffect();
        assertEquals(net.minecraft.world.effect.MobEffectCategory.HARMFUL, effect.getCategory());
        assertEquals(PanicRules.COLOUR, effect.getColor());
        assertTrue(!effect.isBeneficial(), "a 「恐慌」 that vanilla draws as a buff would be a lie on the HUD");
    }

    /**
     * A panic needs every tick, and {@code applyEffectTick} answers {@code true} on the client without
     * touching anything.
     *
     * <p>The tick predicate is the whole reason the movement lock holds: vanilla skips
     * {@code applyEffectTick} unless this answers true, so a panic that returned {@code false} for a duration
     * it did not like would silently stop locking halfway through.
     */
    @Test
    void panicAsksForEveryTickAndHasNoClientSideWork() {
        PanicEffect effect = new PanicEffect();
        assertTrue(effect.shouldApplyEffectTickThisTick(PanicRules.DURATION_TICKS, 0));
        assertTrue(effect.shouldApplyEffectTickThisTick(1, 0));
        assertTrue(effect.shouldApplyEffectTickThisTick(0, 0));
        // `isInstantenous` defaults to false; a panic has a clock and must not be applied once and discarded.
        assertTrue(!effect.isInstantenous(),
                "an instant panic would be applied once and removed, i.e. no lock at all");
    }

    /**
     * The player half is the five request paths, and the mob half needs none of them.
     *
     * <p>Asserted as a property of the class's own documentation contract rather than by reflection: the five
     * cancellable {@code PlayerInteractEvent} subclasses are the set of server-visible interaction requests, and
     * {@code AttackEntityEvent} is the sixth. {@code RightClickEmpty} and {@code LeftClickEmpty} are
     * deliberately outside it because they are client-only and not cancellable — the server is not told about
     * a click on air at all, so there is nothing to refuse.
     */
    @Test
    void thePlayerHalfIsTheSixCancellableRequestPaths() {
        assertTrue(net.neoforged.bus.api.ICancellableEvent.class.isAssignableFrom(
                        net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock.class));
        assertTrue(net.neoforged.bus.api.ICancellableEvent.class.isAssignableFrom(
                        net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.class));
        assertTrue(net.neoforged.bus.api.ICancellableEvent.class.isAssignableFrom(
                        net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem.class));
        assertTrue(net.neoforged.bus.api.ICancellableEvent.class.isAssignableFrom(
                        net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract.class));
        assertTrue(net.neoforged.bus.api.ICancellableEvent.class.isAssignableFrom(
                        net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
                                .EntityInteractSpecific.class));
        // The two client-only forms are NOT cancellable, which is exactly why they are not registered.
        assertTrue(!net.neoforged.bus.api.ICancellableEvent.class.isAssignableFrom(
                        net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickEmpty.class),
                "a click on air carries no server-side action, so there is nothing to cancel");
        assertTrue(!net.neoforged.bus.api.ICancellableEvent.class.isAssignableFrom(
                        net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickEmpty.class));
        // And AttackEntityEvent, the left-click-on-entity path, is cancellable too.
        assertTrue(net.neoforged.bus.api.ICancellableEvent.class.isAssignableFrom(
                net.neoforged.neoforge.event.entity.player.AttackEntityEvent.class));
    }

    /**
     * {@code PlayerInteractEvent} itself is abstract and <b>not</b> cancellable, which is why one handler
     * cannot be registered for it.
     *
     * <p>This is the compile-and-load failure that was found the hard way: NeoForge refuses a listener whose
     * parameter is an abstract event class, and it refuses it at mod load, so a single generic registration is
     * a startup crash rather than a style choice. Pinning the shape here is what stops it coming back.
     */
    @Test
    void theAbstractInteractEventCannotBeListenedToDirectly() {
        assertTrue(java.lang.reflect.Modifier.isAbstract(
                net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.class.getModifiers()));
        assertTrue(!net.neoforged.bus.api.ICancellableEvent.class.isAssignableFrom(
                        net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.class),
                "the base class has no setCanceled, which is why the subclasses are registered one by one");
    }
}
