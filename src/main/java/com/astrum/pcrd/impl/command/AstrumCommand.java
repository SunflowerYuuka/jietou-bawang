package com.astrum.pcrd.impl.command;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.AstrumApi;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.CharacterProgress;
import com.astrum.pcrd.impl.attach.Attachments;
import com.astrum.pcrd.impl.attach.Party;
import com.astrum.pcrd.impl.attach.Roster;
import com.astrum.pcrd.impl.attach.UbState;
import com.astrum.pcrd.impl.combat.AreaChargeTuning;
import com.astrum.pcrd.impl.growth.AffectionKernel;
import com.astrum.pcrd.impl.party.PartyRules;
import com.astrum.pcrd.impl.party.PartyService;
import com.astrum.pcrd.impl.ub.CastGate;
import com.astrum.pcrd.impl.ub.ChargeKernel;
import com.astrum.pcrd.impl.ub.UbService;
import com.astrum.pcrd.impl.ub.WheelSlots;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /astrum}: the operator panel for everything this slice ships, so nothing has to be tested by
 * hand-editing a save file.
 *
 * <h2>The slot convention, stated once</h2>
 * <b>Commands number the five slots 1-5; the party, the wheel and the wire number them 0-4.</b> Every
 * command argument that names a slot is 1-based, every internal index is 0-based, and the conversion
 * happens in exactly one place — {@link #slotIndex(int)}, which delegates to {@link WheelSlots} (the
 * class the wheel itself uses). This is not pedantry: the same off-by-one already shipped once in this
 * project, when the wheel sent its {@code 1-5} digit straight through as a {@code 0-4} slot and
 * {@code 1} cast the second character. {@code AstrumCommandTest} pins the mapping digit by digit, and
 * the help text says the convention out loud.
 *
 * <h2>Why permission level 2 and not "cheats only"</h2>
 * The spec has to survive a LAN host with cheats off (spec §7.4), so every value set here is also
 * reachable by a documented non-command route — a card for the roster, the config file for the guards.
 * The command is a convenience for a world the operator already controls, not the only path.
 *
 * <h2>Every branch speaks</h2>
 * Spec §5.5: no silent failure. Each subcommand answers with a translated line on success and a
 * translated reason on failure — including "that slot was already empty" and "you do not own that
 * character", the two outcomes an operator would otherwise mistake for a broken command. The keys are
 * listed in {@link #LANG_KEYS}, which {@code LangParityTest} checks against both language files.
 */
@ApiStatus.Internal
public final class AstrumCommand {

    /** The root literal. */
    public static final String ROOT = "astrum";

    /** Vanilla's "operator" level: enough to change a world, not enough to be automatic. */
    public static final int PERMISSION_LEVEL = 2;

    /** First slot number a command accepts. */
    public static final int FIRST_SLOT = 1;

    /** Last slot number a command accepts: one per party slot. */
    public static final int LAST_SLOT = Party.SLOTS;

    /** Lowest charge {@code /astrum charge set} accepts, as an integer command argument. */
    public static final int MIN_CHARGE_ARG = (int) ChargeKernel.MIN_CHARGE;

    /** Highest charge {@code /astrum charge set} accepts, as an integer command argument. */
    public static final int MAX_CHARGE_ARG = (int) ChargeKernel.MAX_CHARGE;

    // -----------------------------------------------------------------------------------------
    // Feedback colours as RGB, deliberately not {@code ChatFormatting}.
    //
    // {@code net.minecraft.ChatFormatting} cannot be imported by this build: the class file in the
    // MDG artifact carries a RuntimeVisibleParameterAnnotations attribute whose parameter count does
    // not match its enum constructor's descriptor, and javac 21.0.8 rejects the whole class with
    // "bad class file ... class file is invalid" (javap and the JVM read it happily, which is why the
    // failure looks inexplicable). No pre-existing source in this mod used it, so nothing hit this
    // until now. {@code withColor(int)} reaches the same styling without touching that class.
    // -----------------------------------------------------------------------------------------

    /** Vanilla aqua: headings. */
    private static final int COLOR_HEADING = 0x55FFFF;

    /** Vanilla grey: help lines and empty slots. */
    private static final int COLOR_DIM = 0xAAAAAA;

    // -----------------------------------------------------------------------------------------
    // Translation keys. Held as constants (and collected in LANG_KEYS) so the parity test can prove
    // both language files carry them, instead of a player reading a raw key.
    // -----------------------------------------------------------------------------------------

    public static final String KEY_HELP_HEADER = "astrum.command.help.header";
    public static final String KEY_HELP_ROSTER = "astrum.command.help.roster";
    public static final String KEY_HELP_PARTY = "astrum.command.help.party";
    public static final String KEY_HELP_CHARGE = "astrum.command.help.charge";
    public static final String KEY_HELP_CAST = "astrum.command.help.cast";
    public static final String KEY_HELP_AFFECTION = "astrum.command.help.affection";
    public static final String KEY_HELP_TUNE = "astrum.command.help.tune";

    public static final String KEY_NO_PLAYER = "astrum.command.no_player";
    public static final String KEY_NO_REGISTRY = "astrum.command.no_registry";
    public static final String KEY_UNKNOWN_CHARACTER = "astrum.command.unknown_character";
    public static final String KEY_BAD_SLOT = "astrum.command.bad_slot";
    public static final String KEY_BOOK_QUARANTINED = "astrum.command.book_quarantined";

    public static final String KEY_ROSTER_HEADER = "astrum.command.roster.header";
    public static final String KEY_ROSTER_LINE = "astrum.command.roster.line";
    public static final String KEY_ROSTER_EMPTY = "astrum.command.roster.empty";

    public static final String KEY_PARTY_LINE = "astrum.command.party.line";
    public static final String KEY_PARTY_EMPTY = "astrum.command.party.empty";
    public static final String KEY_PARTY_SET = "astrum.command.party.set";
    public static final String KEY_PARTY_MOVED = "astrum.command.party.moved";
    public static final String KEY_PARTY_CLEARED = "astrum.command.party.cleared";
    public static final String KEY_PARTY_ALREADY_EMPTY = "astrum.command.party.already_empty";
    public static final String KEY_PARTY_NOT_OWNED = "astrum.command.party.not_owned";

    public static final String KEY_CHARGE_SET = "astrum.command.charge.set";
    public static final String KEY_CHARGE_ADDED = "astrum.command.charge.added";
    public static final String KEY_CHARGE_RANGE = "astrum.command.charge.range";

    public static final String KEY_CAST_OK = "astrum.command.cast.ok";
    public static final String KEY_CAST_REFUSED = "astrum.command.cast.refused";

    public static final String KEY_AFFECTION_ADDED = "astrum.command.affection.added";
    public static final String KEY_AFFECTION_NOT_OWNED = "astrum.command.affection.not_owned";
    public static final String KEY_AFFECTION_NO_DEFAULT = "astrum.command.affection.no_default";

    public static final String KEY_TUNE_REPORT = "astrum.command.tune.report";
    public static final String KEY_TUNE_REJECTED = "astrum.command.tune.rejected";
    public static final String KEY_TUNE_RESET = "astrum.command.tune.reset";

    /**
     * Every key this class asks for, for {@code LangParityTest}.
     *
     * <p>The refusal keys are not written out by hand: they are built through {@link #reasonKey}, so a
     * new {@link CastGate.Reason} cannot arrive without a translation and a rename cannot leave a stale
     * key behind.
     */
    public static final Set<String> LANG_KEYS = Set.of(
            KEY_HELP_HEADER, KEY_HELP_ROSTER, KEY_HELP_PARTY, KEY_HELP_CHARGE, KEY_HELP_CAST,
            KEY_HELP_AFFECTION, KEY_HELP_TUNE,
            KEY_NO_PLAYER, KEY_NO_REGISTRY, KEY_UNKNOWN_CHARACTER, KEY_BAD_SLOT, KEY_BOOK_QUARANTINED,
            KEY_ROSTER_HEADER, KEY_ROSTER_LINE, KEY_ROSTER_EMPTY,
            KEY_PARTY_LINE, KEY_PARTY_EMPTY, KEY_PARTY_SET, KEY_PARTY_MOVED, KEY_PARTY_CLEARED,
            KEY_PARTY_ALREADY_EMPTY, KEY_PARTY_NOT_OWNED,
            KEY_CHARGE_SET, KEY_CHARGE_ADDED, KEY_CHARGE_RANGE,
            KEY_CAST_OK, KEY_CAST_REFUSED,
            KEY_AFFECTION_ADDED, KEY_AFFECTION_NOT_OWNED, KEY_AFFECTION_NO_DEFAULT,
            KEY_TUNE_REPORT, KEY_TUNE_REJECTED, KEY_TUNE_RESET,
            reasonKey(CastGate.Reason.SLOT_OUT_OF_RANGE), reasonKey(CastGate.Reason.SLOT_EMPTY),
            reasonKey(CastGate.Reason.CHARACTER_UNKNOWN), reasonKey(CastGate.Reason.NOT_CHARGED),
            reasonKey(CastGate.Reason.COOLDOWN));

    private AstrumCommand() {
    }

    /**
     * Subscribes the command to the game bus.
     *
     * <p>{@link RegisterCommandsEvent} fires when {@code Commands} is constructed — at world load and
     * again on every {@code /reload} — so registering here, once, from common code, is what makes the
     * command exist in single-player and on a dedicated server alike. No dist check: a command is a
     * server concept, and a client sends it as chat.
     */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, AstrumCommand::onRegisterCommands);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
        Astrum.LOGGER.info("astrum: /{} registered at permission level {} (slots are 1-5 on the "
                + "command line, 0-4 internally)", ROOT, PERMISSION_LEVEL);
    }

    // -----------------------------------------------------------------------------------------
    // Pure helpers. Kept free of Minecraft state so the slot convention, the affection cap and the
    // id parsing are unit-tested values rather than lines inside a handler that needs a live server.
    // -----------------------------------------------------------------------------------------

    /**
     * {@return the 0-based slot index for a 1-based command argument, or empty if it is not 1-5}
     *
     * <p>Delegates to {@link WheelSlots#slotForDigit} rather than repeating {@code slot - 1}: the
     * command and the wheel must never disagree about what "slot 3" means, and one conversion is the
     * only way to guarantee that.
     *
     * @param commandSlot the slot as typed on the command line, {@code 1}-{@code 5}
     */
    public static OptionalInt slotIndex(int commandSlot) {
        return WheelSlots.slotForDigit(commandSlot);
    }

    /**
     * {@return the 1-based number to show a player for a 0-based slot index}
     *
     * @param slotIndex a party index, {@code 0}-{@code 4}
     */
    public static int commandSlot(int slotIndex) {
        return slotIndex + FIRST_SLOT;
    }

    /**
     * {@return {@code current + delta} affection, floored at 0 and capped at the tier-3 threshold}
     *
     * <p>Capped because affection above tier 3 buys nothing (spec §6.2 — tier 3 is the last tier), so a
     * debug value beyond it would be a number the game can never produce. A threshold of {@code 0}
     * means the pack defines no tier 3, and then there is nothing to cap to.
     *
     * <p>Widened to {@code long} before adding: two large {@code int}s would otherwise overflow into a
     * negative affection, which is the one value a roster should never hold.
     *
     * @param current        affection the player has now
     * @param delta          change requested by the command; may be negative
     * @param tier3Threshold the definition's tier-3 threshold, or {@code 0} for none
     */
    public static int clampAffection(int current, int delta, int tier3Threshold) {
        // Delegates to the one implementation. This used to be a second copy that had lost the
        // Integer.MAX_VALUE guard: /astrum affection add 2147483647 on a character whose
        // tier3Threshold is <= 0 would overflow the long back into a negative int and write a
        // negative affection. Nobody could trigger it with today's data (djeeta's threshold is 8),
        // but a character without a tier-3 would have made it reachable. One rule, one copy.
        return AffectionKernel.clampAffection(current, delta, tier3Threshold);
    }

    /**
     * {@return the id a character argument names, or {@code null} if it is not a valid id}
     *
     * <p>A bare name is taken to be this mod's namespace ({@code djeeta} → {@code astrum:djeeta}),
     * because that is what an operator typing at a console means, and requiring the namespace would
     * make the common case the one that fails.
     *
     * @param raw the argument as typed
     */
    public static @Nullable ResourceLocation parseCharacterId(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.indexOf(':') >= 0
                ? ResourceLocation.tryParse(trimmed)
                : ResourceLocation.tryBuild(Astrum.MOD_ID, trimmed);
    }

    /**
     * {@return the translation key for a cast refusal}
     *
     * @param reason the refusal the cast path returned
     */
    public static String reasonKey(CastGate.Reason reason) {
        return "astrum.cast.reason." + reason.name();
    }

    // -----------------------------------------------------------------------------------------
    // The tree
    // -----------------------------------------------------------------------------------------

    /**
     * Builds the command tree.
     *
     * <p>Separate from {@link #register()} so the tree can be built against a bare dispatcher — which
     * is how a test could assert its shape without a running server.
     *
     * @param dispatcher the server's command dispatcher
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(ROOT)
                .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                .executes(context -> help(context.getSource()))
                .then(Commands.literal("roster").executes(context -> roster(context.getSource())))
                .then(party())
                .then(charge())
                .then(Commands.literal("cast")
                        .then(Commands.argument("slot", IntegerArgumentType.integer())
                                .executes(context -> cast(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "slot")))))
                .then(affection())
                .then(tune()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> party() {
        // greedyString, not string(): Brigadier's unquoted-string reader stops at ':' (it only takes
        // word characters), so `party set 1 astrum:djeeta` would die with "trailing data" at the colon
        // — measured, not guessed, on the first probe run. The id is therefore taken as the rest of the
        // line and parsed here, which also lets a bare `djeeta` mean `astrum:djeeta`.
        return Commands.literal("party")
                .then(Commands.literal("set")
                        .then(Commands.argument("slot", IntegerArgumentType.integer())
                                .then(Commands.argument("characterId", StringArgumentType.greedyString())
                                        .executes(context -> partySet(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "slot"),
                                                StringArgumentType.getString(context, "characterId"))))))
                .then(Commands.literal("clear")
                        .then(Commands.argument("slot", IntegerArgumentType.integer())
                                .executes(context -> partyClear(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "slot")))))
                .then(Commands.literal("list").executes(context -> partyList(context.getSource())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> charge() {
        return Commands.literal("charge")
                .then(Commands.literal("set")
                        .then(Commands.argument("value", IntegerArgumentType.integer())
                                .executes(context -> chargeSet(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "value")))))
                .then(Commands.literal("add")
                        .then(Commands.argument("delta", IntegerArgumentType.integer())
                                .executes(context -> chargeAdd(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "delta")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> affection() {
        // Same greedyString reason as party(); see the comment there.
        return Commands.literal("affection")
                .then(Commands.literal("add")
                        .then(Commands.argument("delta", IntegerArgumentType.integer())
                                .executes(context -> affectionAdd(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "delta"), null))
                                .then(Commands.argument("characterId", StringArgumentType.greedyString())
                                        .executes(context -> affectionAdd(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "delta"),
                                                StringArgumentType.getString(context, "characterId"))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> tune() {
        return Commands.literal("tune")
                .then(Commands.literal("areaCharge")
                        .executes(context -> tuneReport(context.getSource()))
                        .then(Commands.literal("pairCooldownTicks")
                                .then(Commands.argument("ticks", IntegerArgumentType.integer())
                                        .executes(context -> tunePairCooldown(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "ticks")))))
                        .then(Commands.literal("receiveCapPerSecond")
                                .then(Commands.argument("cap", DoubleArgumentType.doubleArg())
                                        .executes(context -> tuneReceiveCap(context.getSource(),
                                                DoubleArgumentType.getDouble(context, "cap"))))))
                .then(Commands.literal("reset").executes(context -> tuneReset(context.getSource())));
    }

    // -----------------------------------------------------------------------------------------
    // Handlers
    // -----------------------------------------------------------------------------------------

    private static int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable(KEY_HELP_HEADER).withColor(COLOR_HEADING),
                false);
        for (String key : new String[] {KEY_HELP_ROSTER, KEY_HELP_PARTY, KEY_HELP_CHARGE, KEY_HELP_CAST,
                KEY_HELP_AFFECTION, KEY_HELP_TUNE}) {
            source.sendSuccess(() -> Component.translatable(key).withColor(COLOR_DIM), false);
        }
        return 1;
    }

    private static int roster(CommandSourceStack source) {
        return withPlayer(source, (src, player) -> {
            Roster book = readableRoster(src, player);
            if (book == null) {
                return 0;
            }
            // Sorted by id so two runs print the same order: output that shuffles between calls cannot
            // be diffed against a previous run.
            Map<ResourceLocation, CharacterProgress> entries = new TreeMap<>(book.entries());
            src.sendSuccess(() -> Component.translatable(KEY_ROSTER_HEADER, entries.size())
                    .withColor(COLOR_HEADING), false);
            if (entries.isEmpty()) {
                src.sendSuccess(() -> Component.translatable(KEY_ROSTER_EMPTY), false);
                return 1;
            }
            entries.forEach((id, progress) -> src.sendSuccess(
                    () -> Component.translatable(KEY_ROSTER_LINE, id.toString(), progress.level(),
                            progress.exp(), progress.affection()),
                    false));
            return 1;
        });
    }

    private static int partySet(CommandSourceStack source, int slotArgument, String rawCharacterId) {
        return withPlayer(source, (src, player) -> {
            OptionalInt index = slotIndex(slotArgument);
            if (index.isEmpty()) {
                return badSlot(src, slotArgument);
            }
            ResourceLocation characterId = parseCharacterId(rawCharacterId);
            if (characterId == null) {
                src.sendFailure(Component.translatable(KEY_UNKNOWN_CHARACTER,
                        String.valueOf(rawCharacterId)));
                return 0;
            }
            // One call, and no rule of its own: PartyService is the same code the 角色册 screen's
            // astrum:party_edit payload runs, so "slot legal / character defined / character owned"
            // cannot answer differently depending on which way the player asked (spec §6.3.1).
            // The ownership question is answered there, not here; the command used to ask it itself.
            PartyService.Result result = PartyService.edit(player, index.getAsInt(), characterId);
            if (!result.accepted()) {
                return refusePartyEdit(src, slotArgument, characterId, result);
            }

            PartyRules.Edit edit = result.edit();
            String name = displayNameOf(result.definition(), characterId);
            if (edit.moved()) {
                int from = commandSlot(edit.previousSlot().getAsInt());
                src.sendSuccess(() -> Component.translatable(KEY_PARTY_MOVED, slotArgument, name,
                        from), true);
            } else {
                src.sendSuccess(() -> Component.translatable(KEY_PARTY_SET, slotArgument, name), true);
            }
            return 1;
        });
    }

    private static int partyClear(CommandSourceStack source, int slotArgument) {
        return withPlayer(source, (src, player) -> {
            OptionalInt index = slotIndex(slotArgument);
            if (index.isEmpty()) {
                return badSlot(src, slotArgument);
            }
            // A clear travels the same validated path as a placement, with no character named. It used
            // to be a direct attachment write, which meant the screen and the command could disagree
            // about whether a quarantined book may be written.
            PartyService.Result result = PartyService.edit(player, index.getAsInt(), null);
            if (!result.accepted()) {
                return refusePartyEdit(src, slotArgument, null, result);
            }
            if (!result.edit().changed()) {
                src.sendSuccess(() -> Component.translatable(KEY_PARTY_ALREADY_EMPTY, slotArgument),
                        false);
                return 1;
            }
            src.sendSuccess(() -> Component.translatable(KEY_PARTY_CLEARED, slotArgument), true);
            return 1;
        });
    }

    private static int partyList(CommandSourceStack source) {
        return withPlayer(source, (src, player) -> {
            Party party = player.getData(Attachments.PARTY);
            for (int i = 0; i < Party.SLOTS; i++) {
                int shown = commandSlot(i);
                Component contents = party.slots().get(i)
                        .<Component>map(id -> Component.literal(id.toString()))
                        .orElseGet(() -> Component.translatable(KEY_PARTY_EMPTY)
                                .withColor(COLOR_DIM));
                src.sendSuccess(() -> Component.translatable(KEY_PARTY_LINE, shown, contents), false);
            }
            return 1;
        });
    }

    private static int chargeSet(CommandSourceStack source, int value) {
        return withPlayer(source, (src, player) -> {
            if (value < MIN_CHARGE_ARG || value > MAX_CHARGE_ARG) {
                src.sendFailure(Component.translatable(KEY_CHARGE_RANGE, value, MIN_CHARGE_ARG,
                        MAX_CHARGE_ARG));
                return 0;
            }
            UbState state = player.getData(Attachments.UB_STATE);
            // withCharge, not a new record: the per-character cooldowns are not this command's business
            // and a full construction here would need a map argument the command has no opinion about.
            player.setData(Attachments.UB_STATE, state.withCharge((float) value, state.selectedSlot()));
            src.sendSuccess(() -> Component.translatable(KEY_CHARGE_SET, value), true);
            return 1;
        });
    }

    private static int chargeAdd(CommandSourceStack source, int delta) {
        return withPlayer(source, (src, player) -> {
            UbState state = player.getData(Attachments.UB_STATE);
            double before = state.charge();
            // ChargeKernel.add clamps, so "add 500" lands on 100 rather than making a gauge that can
            // never be full again.
            //
            // NEUTRAL_RATE, deliberately: `CHARGE_RATE_UP` multiplies what a *fight* pays, and an
            // operator typing `/astrum charge add 10` is setting up a test, not earning charge. Scaling
            // this by the player's current buff would make the command's own number unpredictable
            // ("add 10" landing at 22.5), which is the opposite of what a debug command is for.
            double after = ChargeKernel.add(before, delta, ChargeKernel.NEUTRAL_RATE);
            player.setData(Attachments.UB_STATE, state.withCharge((float) after, state.selectedSlot()));
            src.sendSuccess(() -> Component.translatable(KEY_CHARGE_ADDED, oneDecimal(before),
                    oneDecimal(after)), true);
            return 1;
        });
    }

    private static int cast(CommandSourceStack source, int slotArgument) {
        return withPlayer(source, (src, player) -> {
            OptionalInt index = slotIndex(slotArgument);
            if (index.isEmpty()) {
                return badSlot(src, slotArgument);
            }
            // Through UbService, never around it: the command obeys exactly the validation the wheel
            // key does (slot occupied, character known, gauge full, cooldown over), or the panel could
            // produce a cast the game's own path refuses.
            Optional<CastGate.Reason> refusal = UbService.tryCast(player, index.getAsInt());
            if (refusal.isPresent()) {
                Component reason = Component.translatable(reasonKey(refusal.get()));
                src.sendFailure(Component.translatable(KEY_CAST_REFUSED, slotArgument, reason));
                return 0;
            }
            src.sendSuccess(() -> Component.translatable(KEY_CAST_OK, slotArgument), true);
            return 1;
        });
    }

    private static int affectionAdd(CommandSourceStack source, int delta, @Nullable String rawCharacterId) {
        return withPlayer(source, (src, player) -> {
            Roster book = readableRoster(src, player);
            if (book == null) {
                return 0;
            }
            ResourceLocation target;
            if (rawCharacterId == null) {
                target = selectedCharacter(player);
                if (target == null) {
                    src.sendFailure(Component.translatable(KEY_AFFECTION_NO_DEFAULT));
                    return 0;
                }
            } else {
                target = parseCharacterId(rawCharacterId);
                if (target == null) {
                    src.sendFailure(Component.translatable(KEY_UNKNOWN_CHARACTER,
                            rawCharacterId));
                    return 0;
                }
            }
            CharacterDef def = definitionOrFail(src, target);
            if (def == null) {
                return 0;
            }
            CharacterProgress progress = book.progressOf(target);
            if (progress == null) {
                src.sendFailure(Component.translatable(KEY_AFFECTION_NOT_OWNED, def.displayName()));
                return 0;
            }

            int updated = clampAffection(progress.affection(), delta, def.affection().tier3Threshold());
            Map<ResourceLocation, CharacterProgress> entries = new LinkedHashMap<>(book.entries());
            entries.put(target, new CharacterProgress(progress.level(), progress.exp(), updated));
            player.setData(Attachments.ROSTER, new Roster(entries, Roster.CURRENT_DATA_VERSION));

            src.sendSuccess(() -> Component.translatable(KEY_AFFECTION_ADDED, def.displayName(), updated,
                    def.affection().tier3Threshold()), true);
            return 1;
        });
    }

    private static int tuneReport(CommandSourceStack source) {
        source.sendSuccess(AstrumCommand::tuneLine, false);
        return 1;
    }

    private static int tunePairCooldown(CommandSourceStack source, int ticks) {
        try {
            // The override rejects a value that would switch a brake off by accident; the message it
            // throws is the feedback, so nothing is reformatted here.
            AreaChargeTuning.override(ticks, null);
        } catch (IllegalArgumentException rejected) {
            source.sendFailure(Component.translatable(KEY_TUNE_REJECTED, rejected.getMessage()));
            return 0;
        }
        source.sendSuccess(AstrumCommand::tuneLine, true);
        return 1;
    }

    private static int tuneReceiveCap(CommandSourceStack source, double cap) {
        try {
            AreaChargeTuning.override(null, cap);
        } catch (IllegalArgumentException rejected) {
            source.sendFailure(Component.translatable(KEY_TUNE_REJECTED, rejected.getMessage()));
            return 0;
        }
        source.sendSuccess(AstrumCommand::tuneLine, true);
        return 1;
    }

    private static int tuneReset(CommandSourceStack source) {
        AreaChargeTuning.clearOverride();
        source.sendSuccess(() -> Component.translatable(KEY_TUNE_RESET), true);
        source.sendSuccess(AstrumCommand::tuneLine, false);
        return 1;
    }

    /** The current effective pair of guard values, as one line. */
    private static Component tuneLine() {
        return Component.translatable(KEY_TUNE_REPORT, AreaChargeTuning.pairCooldownTicks(),
                AreaChargeTuning.receiveCapPerSecond());
    }

    // -----------------------------------------------------------------------------------------
    // Shared plumbing
    // -----------------------------------------------------------------------------------------

    /** A handler that needs a player. */
    @FunctionalInterface
    private interface PlayerHandler {
        int run(CommandSourceStack source, ServerPlayer player);
    }

    /**
     * Runs {@code handler} with the player behind the command, or says why not.
     *
     * <p>Every subcommand here reads or writes player data, so a console invocation is a refusal with a
     * reason, and the refusal is written once rather than eight times.
     */
    private static int withPlayer(CommandSourceStack source, PlayerHandler handler) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable(KEY_NO_PLAYER));
            return 0;
        }
        return handler.run(source, player);
    }

    /**
     * {@return the player's book, or {@code null} after telling them it cannot be read}
     *
     * <p>A quarantined book is refused rather than read: every accessor on it throws, and "0 characters"
     * would be an invented answer about a save this build cannot parse.
     */
    private static @Nullable Roster readableRoster(CommandSourceStack source, ServerPlayer player) {
        Roster roster = player.getData(Attachments.ROSTER);
        if (roster.isQuarantined()) {
            source.sendFailure(Component.translatable(KEY_BOOK_QUARANTINED));
            return null;
        }
        return roster;
    }

    /**
     * {@return the definition of {@code characterId}, or {@code null} after saying why it is missing}
     *
     * <p>Validated against the loaded pack rather than accepted blindly: a party slot naming a character
     * the pack does not define would be a slot that refuses every cast with {@code CHARACTER_UNKNOWN}.
     */
    private static @Nullable CharacterDef definitionOrFail(CommandSourceStack source,
            ResourceLocation characterId) {
        Optional<Registry<CharacterDef>> registry = AstrumApi.characters();
        if (registry.isEmpty()) {
            source.sendFailure(Component.translatable(KEY_NO_REGISTRY));
            return null;
        }
        CharacterDef def = registry.get().get(characterId);
        if (def == null) {
            source.sendFailure(Component.translatable(KEY_UNKNOWN_CHARACTER, characterId.toString()));
            return null;
        }
        return def;
    }

    /** Says why a slot argument was refused, naming the convention the caller has to follow. */
    private static int badSlot(CommandSourceStack source, int slotArgument) {
        source.sendFailure(Component.translatable(KEY_BAD_SLOT, slotArgument, LAST_SLOT));
        return 0;
    }

    /**
     * Turns a shared party-edit refusal into the line this player reads.
     *
     * <p>The mapping lives here rather than in {@link PartyService} because it is a <em>command</em>
     * concern: the service returns a typed {@link PartyRules.Reason}, the command picks the sentence
     * (its wording names the 1-5 convention), and the screen picks its own short one from the same
     * enum through {@link PartyRules#reasonKey}. The verdict itself is shared; only the prose differs.
     *
     * @param source      the command source to answer
     * @param slotArgument the slot as typed, for the range message
     * @param characterId  the character asked for, or {@code null} for a clear
     * @param result       the refused result; its reason is non-null by contract
     */
    private static int refusePartyEdit(CommandSourceStack source, int slotArgument,
            @Nullable ResourceLocation characterId, PartyService.Result result) {
        PartyRules.Reason reason = result.reason() == null
                ? PartyRules.Reason.CHARACTER_UNKNOWN // unreachable: callers check accepted() first
                : result.reason();
        Component line = switch (reason) {
            case BOOK_UNREADABLE -> Component.translatable(KEY_BOOK_QUARANTINED);
            case SLOT_OUT_OF_RANGE -> Component.translatable(KEY_BAD_SLOT, slotArgument, LAST_SLOT);
            case NO_REGISTRY -> Component.translatable(KEY_NO_REGISTRY);
            case CHARACTER_UNKNOWN ->
                    Component.translatable(KEY_UNKNOWN_CHARACTER, String.valueOf(characterId));
            case NOT_OWNED -> Component.translatable(KEY_PARTY_NOT_OWNED,
                    displayNameOf(result.definition(), characterId));
        };
        source.sendFailure(line);
        return 0;
    }

    /** {@return the name to print for a character, falling back to its id when the pack does not define it} */
    private static String displayNameOf(@Nullable CharacterDef definition,
            @Nullable ResourceLocation characterId) {
        if (definition != null) {
            return definition.displayName();
        }
        return characterId == null ? "" : characterId.toString();
    }

    /**
     * {@return the character in the player's selected wheel slot, or {@code null}}
     *
     * <p>The default target of {@code /astrum affection add <n>}: the selected slot is the character the
     * player is looking at in the HUD, so it is the one they most likely mean.
     */
    private static @Nullable ResourceLocation selectedCharacter(ServerPlayer player) {
        UbState state = player.getData(Attachments.UB_STATE);
        Party party = player.getData(Attachments.PARTY);
        int slot = state.selectedSlot();
        if (slot < 0 || slot >= Party.SLOTS) {
            return null;
        }
        return party.slots().get(slot).orElse(null);
    }

    /** Formats a charge with one decimal, in a locale-independent way so logs and chat agree. */
    private static String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
