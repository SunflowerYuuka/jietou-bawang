package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.AstrumApi;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.impl.attach.Party;
import com.astrum.pcrd.impl.growth.DishFeedHook;
import com.astrum.pcrd.impl.growth.GrowthFacts;
import com.astrum.pcrd.impl.item.BookTheme;
import com.astrum.pcrd.impl.network.PartyEditPayload;
import com.astrum.pcrd.impl.network.RosterSyncPayload;
import com.astrum.pcrd.impl.party.BookText;
import com.astrum.pcrd.impl.party.PartyRules;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The 角色册 screen: roster grid, party editor and growth view in one panel (spec §6.3.1).
 *
 * <h2>Three parts, one screen, one authority</h2>
 * <ul>
 *   <li><b>① 角色册</b> — a grid of owned characters, each with portrait, level, affection-tier pips and
 *       a marker when the character sits in the party.</li>
 *   <li><b>② 编队</b> — the five slots. Select a character in the grid, then click a slot (or drag the
 *       character onto it) to place it; right-click a slot to empty it. A character is never in two
 *       slots, because the request the client sends is a <em>move</em> and the server applies it through
 *       {@code Party.with}, which empties the old slot.</li>
 *   <li><b>③ 养成</b> — the selected character's level, experience bar, affection progress and the
 *       feeding entry point ({@link DishFeedHook} until Task 15 installs a handler).</li>
 * </ul>
 *
 * <h2>The client decides nothing</h2>
 * This class reads three things: the roster push ({@link ClientRosterCache}), the party push
 * ({@link ClientPartyCache}) and the last refusal ({@link ClientBookFeedback}). It writes exactly one
 * thing: a {@link PartyEditPayload} naming a slot and a character. Everything else — whether the
 * character is owned, whether the slot exists, whether the book may be written at all — is the
 * server's answer, produced by the same {@code PartyService} that {@code /astrum party set} calls. So
 * this screen cannot be a second path into player state, which is the whole requirement of §6.3.1.
 *
 * <h2>Geometry is not decided here</h2>
 * Every rectangle comes from {@link BookLayout}, which is pure and unit-tested: this project cannot
 * screenshot, so "the cells do not overlap" and "a click on a cell selects that cell" are asserted in
 * {@code BookLayoutTest} instead of being claimed by looking at a window. This class only puts pixels
 * in the boxes it is given.
 *
 * <h2>Quarantine</h2>
 * When the server reports an unreadable save, the screen draws the 存档读取失败 line <b>instead of</b>
 * the three views and accepts no edit: {@code Roster.entries()} throws on such a book, so drawing an
 * empty grid would be inventing an answer about a player whose progress is sitting on disk.
 *
 * <h2>What the player sees behind and around the panel</h2>
 * The world, dimmed — see {@link #renderBackground}: vanilla's blur and its near-opaque menu tile are
 * both skipped, and the replacement is a 31%→38% black gradient rather than the 55% flat sheet this
 * screen used to paint. That sheet, over a panel narrower than the window, is what the human reported as
 * 「两侧黑乎乎的」.
 *
 * <p>And the panel itself is sized by {@link BookLayout}, which picks one of two metric tiers for the
 * window it is given (comfortable when it fits, compact when it does not) instead of using one fixed
 * 297x227 box on every screen. The tiers, and why the panel is not a scale transform, are documented
 * there.
 *
 * <h2>Why this is not paused, and why it is a Screen rather than an overlay</h2>
 * A book is a deliberate modal action — you stop playing to read it — so unlike the wheel and the cast
 * pad (spec §5.5) it may take the mouse and the keyboard. It does not pause the world
 * ({@link #isPauseScreen()} is {@code false}), so the server keeps ticking and the roster/party pushes
 * keep landing while it is open.
 */
@ApiStatus.Internal
@OnlyIn(Dist.CLIENT)
public final class CharacterBookScreen extends Screen {

    /**
     * The atmospheric dim drawn over the world: a light gradient, not a sheet.
     *
     * <p>{@code 0x50} black at the top row rising to {@code 0x60} at the bottom — 31% to 38% — is
     * deliberately lighter than vanilla's {@code renderTransparentBackground} ({@code 0xC0}→{@code 0xD0}).
     * Vanilla can afford to be that dark because its inventory and pause menu cover the <em>whole</em>
     * screen; this panel is smaller than the screen on purpose, so the world around it is not decoration,
     * it is the background the human asked to keep. 「像打开背包那样能看到背景」 is about seeing the world,
     * and 31% black over it does that while 55% (the old flat {@code 0x90}) over a night-time world is
     * indistinguishable from black bars down the sides — which is exactly what he reported as
     * 「两侧黑乎乎的」.
     */
    private static final int COLOR_DIM_TOP = 0x50000000;

    /** Bottom of the same gradient; one step darker, as vanilla's own two stops are. */
    private static final int COLOR_DIM_BOTTOM = 0x60000000;

    // Section headings and body text are theme-independent: they are drawn *on* the panel, and a
    // cream heading on a dark body reads the same on all three. Everything that is part of the panel's
    // own surface — the wells, the slots, the accent rules — comes from the theme (see the getters at
    // the bottom of this block), so a pink panel cannot end up with a blue cell border.
    private static final int COLOR_EXP_BAR = 0xFF55C8FF;
    private static final int COLOR_AFFECTION_BAR = 0xFFFF9AD5;
    private static final int COLOR_PIP = 0xFFFFC93C;
    private static final int COLOR_IN_PARTY = 0xFF55FF85;
    private static final int COLOR_TITLE = 0xFFF0E6C8;
    private static final int COLOR_TEXT = 0xFFE8E8E8;
    private static final int COLOR_DIM = 0xFF9AA4B8;
    private static final int COLOR_REFUSED = 0xFFFF7070;

    /** {@return the theme's accent, for the thin rules drawn on the panel (hover frames, feed entry)} */
    private int colorAccent() {
        return theme.accentTint();
    }

    /** {@return the theme's cut-in surface: roster cells, party slots and bar backs} */
    private int colorSurface() {
        return theme.surfaceTint();
    }

    /**
     * The current layout; rebuilt on resize ({@link #init()}).
     *
     * <p>Initialised to a 320x240 layout rather than left {@code null}: {@code init()} always runs before
     * the first frame, but a null here would turn any future ordering mistake into a crash in a
     * player's game instead of a slightly wrong panel.
     */
    private BookLayout layout = BookLayout.at(320, 240);

    /** Absolute index of the first roster cell shown, for paging a long roster. */
    private int firstCell;

    /**
     * Character names whose face-resolution branch has already been logged this open.
     *
     * <p>An instance field, cleared in {@link #init()} with the rest of the screen's state: a
     * {@code static} set here would outlive the screen and grow with every character ever seen, which
     * this project's {@code guard} treats as a defect for good reason. Keyed by display name because the
     * thing being logged is "which picture this character resolved to", and the name is what a reader of
     * the log is looking for.
     */
    private final Set<String> faceBranchLogged = new HashSet<>();

    /** The character the growth view is showing, or {@code null} when nothing is selected. */
    private @Nullable ResourceLocation selected;

    /** Absolute roster index a left-drag started on, or {@code -1} when no drag is in progress. */
    private int dragFrom = -1;

    /**
     * The theme this screen was opened with — the theme of the item the player right-clicked.
     *
     * <p>Decided by {@link CharacterBookItem} from the item's registry id
     * ({@link BookTheme#ofItemId}) and passed in at {@link #open}, never read from a static or a config:
     * the player may own all three books, and the one they right-clicked is the one whose screen this is.
     * A field rather than a constructor parameter only because it is read on every frame and must survive
     * {@link #init()} (a window resize re-inits this screen; it must not lose the theme).
     */
    private BookTheme theme = BookTheme.DEFAULT;

    /**
     * Opens the book: the only entry point, called from {@code CharacterBookItem.use} on the client.
     *
     * <p>The theme is a parameter, not a lookup: the screen has no access to the hand that opened it, and
     * the item is the only thing that knows which of the player's books was used.
     *
     * @param theme the theme of the book that was right-clicked; {@code null} means the blue book
     */
    public CharacterBookScreen(@Nullable BookTheme theme) {
        super(Component.translatable(BookText.KEY_TITLE));
        this.theme = BookTheme.resolve(theme);
    }

    /**
     * Shows the book, if there is a world to show it in.
     *
     * <p>The guard is not cosmetic: {@code setScreen} outside a world leaves a screen with no player
     * behind it, and {@code use} is only ever called with a player, so returning silently here would
     * hide a bug rather than prevent one — but a disconnect landing between the click and this call is
     * real, and a screen without a world would draw an empty book for nobody.
     *
     * <p>Re-opening while the same screen is already up is a no-op, which also means right-clicking a
     * <em>different</em> book while one is open does not swap the theme underneath the player's cursor.
     * The screen is modal; the next open takes the theme of the next click.
     *
     * @param theme the theme of the stack that was right-clicked; {@code null} means the blue book
     */
    public static void open(@Nullable BookTheme theme) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        if (minecraft.screen instanceof CharacterBookScreen) {
            return;
        }
        minecraft.setScreen(new CharacterBookScreen(theme));
    }

    @Override
    protected void init() {
        layout = BookLayout.at(width, height);
        firstCell = layout.clampFirstIndex(ClientRosterCache.size(), firstCell);
        // The per-open diagnostic memory: a resize re-inits this screen and the new panel's faces are
        // worth logging again (they are the same characters, but "the branch I took" is what the log is
        // for, and this is the one place a screen's state starts).
        faceBranchLogged.clear();
        if (selected != null && !ClientRosterCache.owns(selected)) {
            // The selected character is gone (a different player's book, or a save swap): clearing keeps
            // the growth view from describing somebody else's character.
            selected = null;
        }
        // What this open resolved to, and the geometry it will draw with. This line is the only evidence
        // available for "the theme came from the item that opened the screen" and "the nine rectangles
        // for the size this window actually has are non-degenerate": the panel size comes from the
        // player's window and GUI scale, so neither can be asserted offline.
        NineSlice.Rects rects = NineSlice.rects(layout.panel(), NineSlice.DEFAULT_SLICE);
        ThemedPanel.isUsable(rects);
        Astrum.LOGGER.debug("astrum: character book opened ({}x{} gui, theme {}, tier {} — panel {}x{} "
                        + "at {},{} of a {}x{} screen; margins l/r/t/b {}/{}/{}/{}; slice {} -> centre "
                        + "{}x{}; {} owned, {} in party{})",
                width, height, theme, layout.tierName(), layout.panel().width(),
                layout.panel().height(), layout.panel().x(), layout.panel().y(), width, height,
                layout.panel().x(), width - layout.panel().right(), layout.panel().y(),
                height - layout.panel().bottom(), NineSlice.DEFAULT_SLICE,
                rects.centre().width(), rects.centre().height(), ClientRosterCache.size(),
                ClientPartyCache.latest().slots().stream().filter(Optional::isPresent).count(),
                ClientRosterCache.quarantined() ? ", quarantined save" : "");
        Astrum.LOGGER.debug("astrum: character book background is the world, dimmed {} -> {} behind a "
                        + "{}x{} panel; the world is visible on all four sides",
                Integer.toHexString(COLOR_DIM_TOP), Integer.toHexString(COLOR_DIM_BOTTOM),
                layout.panel().width(), layout.panel().height());
    }

    /**
     * Overrides vanilla's background so the world behind the book is <b>not blurred</b>, and draws the
     * inventory's own atmospheric dim instead of a black sheet.
     *
     * <p><b>Why it does not call {@code super}.</b> {@code Screen.renderBackground} calls
     * {@code renderBlurredBackground} whenever {@code minecraft.level != null}, which runs a full-screen
     * post-process blur over the already rendered frame, <em>and</em> then {@code renderMenuBackground},
     * which blits vanilla's near-opaque {@code inworld_menu_background} tile over the whole screen. The
     * blur is not a property of our panel — it blurs everything, so grass, hotbar and sky went soft the
     * moment the book opened (a real run on the user's machine showed plain grass with no 16x16 texture,
     * the signature of that blur). A 1.21 {@code Screen} cannot opt out through {@code isPauseScreen()} or
     * any flag, so not calling {@code super} is the way out.
     *
     * <p><b>What replaced the flat fill.</b> The old override painted {@code 0x90000000} — 55% black over
     * the entire screen. With a panel that covers part of a wide window, a uniformly 55%-dark world reads
     * as a black border around the panel, which is what the human described as 「两侧黑乎乎的」.
     *
     * <p>So the dim is now <b>light and graded</b>: {@code 0x50}→{@code 0x60} black, a vertical gradient,
     * which is the shape vanilla uses ({@code renderTransparentBackground} is a gradient too) at a much
     * lower strength — vanilla can afford 75% because its inventory covers the whole screen, while here
     * the world around the panel is the point. The world is visible on every side; see
     * {@link #COLOR_DIM_TOP} for the arithmetic and {@code BookLayout} for the panel that leaves it
     * visible.
     *
     * <p>{@code fillGradient} rather than {@code fill}: a single flat alpha is what a "dim overlay" looks
     * like in a mock-up, while a gradient is what the player's eye has been trained by every vanilla
     * screen to read as "the world is still there behind this".
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, width, height, COLOR_DIM_TOP, COLOR_DIM_BOTTOM);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        // The panel is the theme's nine-slice art, drawn by ThemedPanel with geometry from NineSlice
        // (both pure and unit-tested). Its own opaque body fill replaces the old flat COLOR_PANEL and
        // the four COLOR_FRAME lines: the art carries the frame, so drawing a flat rectangle around it
        // would put a second, square border outside the ornamented one.
        ThemedPanel.draw(graphics, theme, layout.panel(), layout.divider());

        drawCentred(graphics, title, layout.title(), COLOR_TITLE);

        if (ClientRosterCache.quarantined()) {
            drawQuarantined(graphics);
        } else {
            drawRoster(graphics, mouseX, mouseY);
            drawParty(graphics, mouseX, mouseY);
            drawGrowth(graphics);
        }
        drawFooter(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * The unreadable-save state: one sentence, and no grid, no slots, no bars.
     *
     * <p>Deliberately not "an empty book": a fresh player and a player whose save this build cannot
     * parse look identical through the data, and telling the second one that they own nothing would be
     * a lie about progress that is still on disk.
     */
    private void drawQuarantined(GuiGraphics graphics) {
        BookLayout.Box grid = layout.rosterGrid();
        BookLayout.Box wide = new BookLayout.Box(grid.x(), grid.y(), layout.bodyWidth(),
                BookLayout.LINE_HEIGHT);
        drawClipped(graphics, Component.translatable(BookText.KEY_QUARANTINED), wide, COLOR_REFUSED);
    }

    // -----------------------------------------------------------------------------------------
    // ① The roster grid
    // -----------------------------------------------------------------------------------------

    private void drawRoster(GuiGraphics graphics, int mouseX, int mouseY) {
        List<RosterSyncPayload.Entry> entries = ClientRosterCache.entries();
        BookLayout.Box grid = layout.rosterGrid();
        if (entries.isEmpty()) {
            drawClipped(graphics, Component.translatable(BookText.KEY_ROSTER_EMPTY), grid, COLOR_DIM);
            return;
        }
        int first = layout.clampFirstIndex(entries.size(), firstCell);
        int visible = layout.visibleCells(entries.size(), first);
        for (int i = 0; i < visible; i++) {
            BookLayout.Box cell = layout.cell(i);
            if (cell == null) {
                continue;
            }
            RosterSyncPayload.Entry entry = entries.get(first + i);
            boolean isSelected = entry.id().equals(selected);
            boolean hovered = cell.contains(mouseX, mouseY);
            int background = isSelected ? theme.surfaceSelectedTint()
                    : hovered ? theme.surfaceHoverTint() : colorSurface();
            graphics.fill(cell.x(), cell.y(), cell.right(), cell.bottom(), background);

            CharacterDef definition = definitionOf(entry.id());
            WheelIcon icon = WheelIcon.of(faceOf(definition),
                    entry.tier() >= GrowthFacts.MAX_TIER);
            WheelIconDraw.draw(graphics, layout.cellIconCentreX(i), cell.centreY(), icon,
                    Optional.ofNullable(definition), layout.cellIconRadius());

            // Level above, name below, both clipped to the text column the cell's measurement tier
            // defines — truncated rather than allowed to run into the neighbouring cell.
            drawClipped(graphics, Component.literal("Lv " + entry.level()), layout.cellLevelBox(i),
                    COLOR_TEXT);
            drawClipped(graphics, Component.literal(nameOf(definition, entry.id())),
                    layout.cellNameBox(i), COLOR_DIM);

            // Affection tier as 1-3 pips: the grid has no room for the word "tier", and three pips read
            // the same in every language.
            for (int pip = 0; pip < entry.tier(); pip++) {
                int pipX = cell.right() - 4 - pip * 3;
                graphics.fill(pipX, cell.bottom() - 5, pipX + 2, cell.bottom() - 3, COLOR_PIP);
            }
            // In the party: a green corner marker, the same colour the party slots use for "occupied".
            if (partySlotOf(entry.id()).isPresent()) {
                graphics.fill(cell.x() + 2, cell.y() + 2, cell.x() + 5, cell.y() + 5, COLOR_IN_PARTY);
            }
        }
        if (entries.size() > layout.page()) {
            // Paging indicator only when there is something to page through.
            drawClipped(graphics, Component.literal((first + 1) + "-" + (first + visible) + " / "
                            + entries.size()),
                    new BookLayout.Box(grid.x(), grid.bottom() - BookLayout.LINE_HEIGHT,
                            layout.metrics().gridWidth(), BookLayout.LINE_HEIGHT), COLOR_DIM);
        }
    }

    // -----------------------------------------------------------------------------------------
    // ② The party slots
    // -----------------------------------------------------------------------------------------

    private void drawParty(GuiGraphics graphics, int mouseX, int mouseY) {
        drawClipped(graphics, Component.translatable(BookText.KEY_SECTION_PARTY), layout.partyHeading(),
                COLOR_TITLE);
        for (int slot = 0; slot < Party.SLOTS; slot++) {
            BookLayout.Box box = layout.slot(slot);
            if (box == null) {
                continue;
            }
            Optional<ResourceLocation> occupant = ClientPartyCache.slot(slot);
            boolean hovered = box.contains(mouseX, mouseY);
            int background = occupant.isPresent() ? theme.slotFilledTint() : colorSurface();
            graphics.fill(box.x(), box.y(), box.right(), box.bottom(), background);
            if (hovered) {
                graphics.fill(box.x(), box.y(), box.right(), box.y() + 1, colorAccent());
                graphics.fill(box.x(), box.bottom() - 1, box.right(), box.bottom(), colorAccent());
                graphics.fill(box.x(), box.y(), box.x() + 1, box.bottom(), colorAccent());
                graphics.fill(box.right() - 1, box.y(), box.right(), box.bottom(), colorAccent());
            }
            if (occupant.isEmpty()) {
                // The slot number doubles as the wheel position the character would occupy, so an empty
                // slot still says which key would cast it once somebody is in it.
                drawCentred(graphics, Component.literal(String.valueOf(slot + 1)), box, COLOR_DIM);
                continue;
            }
            CharacterDef definition = definitionOf(occupant.get());
            WheelIcon icon = WheelIcon.of(faceOf(definition),
                    ClientPartyCache.enhanced(slot));
            WheelIconDraw.draw(graphics, box.centreX(), box.centreY(), icon,
                    Optional.ofNullable(definition), layout.slotIconRadius());
        }
    }

    // -----------------------------------------------------------------------------------------
    // ③ The growth view
    // -----------------------------------------------------------------------------------------

    private void drawGrowth(GuiGraphics graphics) {
        drawClipped(graphics, Component.translatable(BookText.KEY_SECTION_GROWTH), layout.growthHeading(),
                COLOR_TITLE);

        RosterSyncPayload.Entry entry = selectedEntry();
        if (entry == null) {
            drawClipped(graphics, Component.translatable(BookText.KEY_GROWTH_NONE), layout.levelLine(),
                    COLOR_DIM);
            drawClipped(graphics, Component.translatable(BookText.KEY_FEED_ROUTE), layout.feedEntry(),
                    COLOR_DIM);
            return;
        }
        CharacterDef definition = definitionOf(entry.id());
        String name = nameOf(definition, entry.id());

        // The experience numbers share the level row, right-aligned, instead of taking a line of their
        // own: that line's eleven pixels are what pays for the skills list below. Both halves still say
        // what they said before — the level on the left, "exp x / y" on the right — and the bar between
        // them is unchanged. The level text is clipped to what the numbers leave, so a long name can
        // never draw over them.
        String expText = Component.translatable(BookText.KEY_GROWTH_EXP, entry.exp(),
                entry.expToNext()).getString();
        BookLayout.Box levelBox = layout.levelLine();
        int expWidth = font.width(expText);
        if (expWidth < levelBox.width()) {
            graphics.drawString(font, expText, levelBox.right() - expWidth, levelBox.y(), COLOR_DIM,
                    true);
            drawClipped(graphics, Component.translatable(BookText.KEY_GROWTH_LEVEL, name,
                            entry.level()),
                    new BookLayout.Box(levelBox.x(), levelBox.y(),
                            levelBox.width() - expWidth - 4, levelBox.height()), COLOR_TEXT);
        } else {
            // Numbers wider than the column: the level wins the row and the numbers are dropped rather
            // than drawn over it. Unreachable with the shipped curve (five digits), kept so a pack with
            // an absurd expToNext cannot draw text past the panel.
            graphics.drawString(font, expText, levelBox.x(), levelBox.y(), COLOR_DIM, true);
        }
        drawBar(graphics, layout.expBar(),
                GrowthFacts.expFraction(entry.exp(), entry.expToNext()), COLOR_EXP_BAR);

        drawClipped(graphics, Component.translatable(BookText.KEY_GROWTH_AFFECTION, entry.affection(),
                entry.tier()), layout.affectionLine(), COLOR_TEXT);
        drawBar(graphics, layout.affectionBar(),
                definition == null ? GrowthFacts.NO_PROGRESS
                        : GrowthFacts.affectionFraction(entry.affection(), definition.affection()),
                COLOR_AFFECTION_BAR);

        if (definition == null) {
            drawClipped(graphics, Component.translatable(BookText.KEY_UNKNOWN_CHARACTER, name),
                    layout.nextTierLine(), COLOR_DIM);
        } else {
            OptionalInt next = GrowthFacts.nextTierThreshold(entry.affection(), definition.affection());
            drawClipped(graphics, next.isPresent()
                            ? Component.translatable(BookText.KEY_GROWTH_NEXT, next.getAsInt())
                            : Component.translatable(BookText.KEY_GROWTH_MAX),
                    layout.nextTierLine(), COLOR_DIM);
        }

        // The feeding entry point: a working action once a dish handler is installed, otherwise the route
        // the spec itself gives (§6.2: hold a dish, right-click). Never a dead button.
        boolean feedable = DishFeedHook.available();
        BookLayout.Box feed = layout.feedEntry();
        if (feedable) {
            graphics.fill(feed.x(), feed.y(), feed.right(), feed.bottom(), theme.slotFilledTint());
            graphics.fill(feed.x(), feed.y(), feed.right(), feed.y() + 1, colorAccent());
            drawClipped(graphics, Component.translatable(BookText.KEY_FEED_AVAILABLE), feed, COLOR_TEXT);
        } else {
            drawClipped(graphics, Component.translatable(BookText.KEY_FEED_ROUTE), feed, COLOR_DIM);
        }

        drawSkills(graphics, definition);
    }

    // -----------------------------------------------------------------------------------------
    // ③b The selected character's skills: one official skill icon + "trigger · effect" per hook
    // -----------------------------------------------------------------------------------------

    /**
     * Draws the hook list: the character's effect hooks, each as an official skill icon plus one
     * clipped line.
     *
     * <h2>What a row says</h2>
     * {@code trigger · effect} — e.g. "melee · gain charge". The parameters (amounts, radii) are
     * deliberately not in the row: at 112 pixels wide the line only fits ~11 CJK characters, and a
     * truncated "splash 3.0/" is worse than no number at all. The numbers stay where they are
     * authoritative, in the datapack JSON and on the hook records.
     *
     * <h2>Why the icon can be drawn from a 64x64 file into a 9x9 row</h2>
     * The blit passes {@code uWidth == textureWidth}, which normalises the source to 0..1 and so
     * samples the whole texture whatever its resolution. The alternative, vanilla's 7-argument blit,
     * assumes a 256x256 sheet and would show the top-left ninth of each icon.
     *
     * <p>Hooks beyond this layout's row budget ({@link BookLayout#hookRows()}: three in the compact tier,
     * four in the comfortable one) are summarised by {@code astrum.book.skills.more} rather than dropped:
     * {@code SkillIcons.rows} decides the split and is unit-tested.
     */
    private void drawSkills(GuiGraphics graphics, @Nullable CharacterDef definition) {
        drawClipped(graphics, Component.translatable(BookText.KEY_SECTION_SKILLS),
                layout.skillsHeading(), COLOR_TITLE);
        if (definition == null) {
            return;
        }
        List<EffectHook> hooks = definition.effects();
        SkillIcons.Rows rows = SkillIcons.rows(hooks.size(), layout.hookRows());
        for (int i = 0; i < rows.shownHooks(); i++) {
            drawHookRow(graphics, layout.hookRow(i), hooks.get(i));
        }
        if (rows.overflowRow()) {
            drawClipped(graphics, Component.translatable(BookText.KEY_SKILLS_MORE, rows.overflow()),
                    layout.hookRow(rows.shownHooks()), COLOR_DIM);
        }
    }

    /** One hook: its effect type's icon on the left, the trigger and effect names to its right. */
    private void drawHookRow(GuiGraphics graphics, @Nullable BookLayout.Box row, EffectHook hook) {
        if (row == null) {
            return;
        }
        graphics.blit(SkillIcons.iconFor(hook), row.x(), row.y(), SkillIcons.ROW_ICON,
                SkillIcons.ROW_ICON, 0.0F, 0.0F, SkillIcons.ROW_ICON, SkillIcons.ROW_ICON,
                SkillIcons.ROW_ICON, SkillIcons.ROW_ICON);
        Component text = Component.translatable(BookText.triggerKey(hook.trigger()))
                .append(Component.literal(" · "))
                .append(Component.translatable(BookText.effectKey(hook.type())));
        drawClipped(graphics, text, new BookLayout.Box(row.x() + SkillIcons.ROW_ICON + 2, row.y(),
                row.width() - SkillIcons.ROW_ICON - 2, row.height()), COLOR_TEXT);
    }

    // -----------------------------------------------------------------------------------------
    // Footer: a refusal while it is fresh, the interaction hint otherwise
    // -----------------------------------------------------------------------------------------

    private void drawFooter(GuiGraphics graphics) {
        BookLayout.Box footer = layout.refusalLine();
        Optional<ClientBookFeedback.Refusal> refusal = ClientBookFeedback.active(gameTime());
        if (refusal.isPresent()) {
            drawClipped(graphics, Component.translatable(BookText.KEY_REFUSED,
                    Component.translatable(PartyRules.reasonKey(refusal.get().reason()))), footer,
                    COLOR_REFUSED);
            return;
        }
        if (!ClientRosterCache.quarantined()) {
            drawClipped(graphics, Component.translatable(BookText.KEY_PARTY_HINT), footer, COLOR_DIM);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Input. Every branch ends in at most one payload, and no branch writes state locally.
    // -----------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (ClientRosterCache.quarantined()) {
            // Nothing may be edited on an unreadable save; the server would refuse anyway, and asking
            // would only produce a refusal message about a save we already told the player about.
            return super.mouseClicked(mouseX, mouseY, button);
        }
        OptionalInt cell = layout.rosterIndexAt(mouseX, mouseY, ClientRosterCache.size(), firstCell);
        if (cell.isPresent()) {
            RosterSyncPayload.Entry entry = ClientRosterCache.entries().get(cell.getAsInt());
            if (button == 0) {
                selected = entry.id().equals(selected) ? null : entry.id();
                dragFrom = selected == null ? -1 : cell.getAsInt();
                ClientBookFeedback.clear();
            }
            return true;
        }
        OptionalInt slot = layout.slotAt(mouseX, mouseY);
        if (slot.isPresent()) {
            onSlotClicked(slot.getAsInt(), button);
            return true;
        }
        if (DishFeedHook.available() && selected != null && layout.feedEntry().contains(mouseX, mouseY)) {
            DishFeedHook.sink().ifPresent(sink -> sink.feed(selected));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * A click on a party slot: place the selection, empty the slot, or select what is already there.
     *
     * <p>Left-click places when something is selected and selects the occupant otherwise; right-click
     * empties. Selecting by clicking an occupied slot is what makes the growth view reachable for a
     * character the player cannot see in the grid (a slot from an older save, or a scrolled-off row).
     */
    private void onSlotClicked(int slot, int button) {
        if (button == 1) {
            sendEdit(PartyEditPayload.clear(slot));
            return;
        }
        if (button != 0) {
            return;
        }
        if (selected != null) {
            sendEdit(PartyEditPayload.place(slot, selected));
            return;
        }
        Optional<ResourceLocation> occupant = ClientPartyCache.slot(slot);
        occupant.ifPresent(id -> {
            selected = id;
            ClientBookFeedback.clear();
        });
    }

    /** Drag-to-place: the same single request a click sends, so drag cannot create a state click cannot. */
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragFrom >= 0) {
            int index = dragFrom;
            dragFrom = -1;
            OptionalInt slot = layout.slotAt(mouseX, mouseY);
            if (slot.isPresent() && !ClientRosterCache.quarantined() && index >= 0
                    && index < ClientRosterCache.size()) {
                sendEdit(PartyEditPayload.place(slot.getAsInt(),
                        ClientRosterCache.entries().get(index).id()));
                return true;
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** Scrolling the roster a page at a time, so a long book stays reachable with the same grid. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int count = ClientRosterCache.size();
        if (count > layout.page() && layout.rosterGrid().contains(mouseX, mouseY)) {
            int direction = scrollY > 0 ? -1 : 1;
            int next = layout.clampFirstIndex(count, firstCell + direction * layout.columns());
            if (next != firstCell) {
                firstCell = next;
                return true;
            }
            return false;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** The book does not pause the world: the roster and party pushes keep arriving while it is open. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Sends one party edit and forgets the previous refusal.
     *
     * <p>The client sends an intent and nothing else. The party the player ends up with is whatever the
     * server's answer to it is — validated by {@code PartyService}, which is also what the command
     * calls. A success needs no local bookkeeping because the server's party push arrives within the
     * tick; a refusal arrives as {@link com.astrum.pcrd.impl.network.PartyEditResultPayload} and is drawn
     * in the footer.
     */
    private void sendEdit(PartyEditPayload payload) {
        ClientBookFeedback.clear();
        net.minecraft.client.multiplayer.ClientPacketListener connection =
                Minecraft.getInstance().getConnection();
        if (connection == null) {
            // Disconnected between the click and now: nothing to send to, and no state to fix up.
            return;
        }
        PacketDistributor.sendToServer(payload);
        Astrum.LOGGER.debug("astrum: character book asked for slot {} -> {}", payload.slot(),
                payload.characterId().map(ResourceLocation::toString).orElse("(clear)"));
    }

    // -----------------------------------------------------------------------------------------
    // Small drawing and lookup helpers
    // -----------------------------------------------------------------------------------------

    /** {@return the selected character's progress row, or {@code null} when nothing usable is selected} */
    private @Nullable RosterSyncPayload.Entry selectedEntry() {
        if (selected == null) {
            return null;
        }
        return ClientRosterCache.entry(selected).orElse(null);
    }

    /** {@return the slot a character occupies, for the grid's in-party marker} */
    private OptionalInt partySlotOf(ResourceLocation characterId) {
        for (int slot = 0; slot < Party.SLOTS; slot++) {
            if (ClientPartyCache.slot(slot).filter(characterId::equals).isPresent()) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * {@return the definition of {@code characterId}, or {@code null} when the loaded pack has none}
     *
     * <p>The pack is read through {@code AstrumApi.characters()}, the same facade an addon publishes
     * through: a character from another mod appears in this grid with no change here, which is the
     * §6.3.1 acceptance point for addons.
     */
    private static @Nullable CharacterDef definitionOf(ResourceLocation characterId) {
        return AstrumApi.characters().map(registry -> registry.get(characterId)).orElse(null);
    }

    /**
     * {@return the face to blit for a definition, empty when it has none}
     *
     * <p>Delegates to {@link CharacterDef#face()}, which owns the resolution order
     * ({@code portrait → avatar}) so this screen, the wheel and the cast pad cannot each decide
     * differently. Reading {@code definition.portrait()} here directly is the defect that made the
     * roster draw a glyph for a character whose avatar the party effect was displaying two rectangles
     * to the right.
     *
     * <p>The branch it takes is logged <b>once per character</b>, not per frame: a per-frame
     * {@code LOGGER.debug} would be a formatting cost on every frame at 300 fps for a fact that cannot
     * change while the screen is open. {@link #faceBranchLogged} is the memory, and it is cleared in
     * {@link #init()} — the only place a new screen's state starts.
     */
    private Optional<ResourceLocation> faceOf(@Nullable CharacterDef definition) {
        if (definition == null) {
            return Optional.empty();
        }
        Optional<ResourceLocation> face = definition.face();
        if (faceBranchLogged.add(definition.displayName())) {
            Astrum.LOGGER.debug("astrum: book face for {} -> {} (branch: {})", definition.displayName(),
                    face.map(ResourceLocation::toString).orElse("(none)"),
                    face.isEmpty() ? "glyph disc, no portrait and no avatar"
                            : definition.portrait().isPresent() ? "portrait" : "avatar (portrait absent)");
        }
        return face;
    }

    /** {@return the name to show, falling back to the raw id for a character the pack does not define} */
    private static String nameOf(@Nullable CharacterDef definition, ResourceLocation characterId) {
        return definition == null ? characterId.toString() : definition.displayName();
    }

    /** {@return the client's game time, or {@code 0} outside a level} */
    private long gameTime() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? 0L : minecraft.level.getGameTime();
    }

    /** Draws a filled bar: the frame, then the filled part, clamped to the frame by {@code fraction}. */
    private void drawBar(GuiGraphics graphics, BookLayout.Box box, double fraction, int colour) {
        graphics.fill(box.x(), box.y(), box.right(), box.bottom(), colorSurface());
        int filled = (int) Math.round(box.width() * fraction);
        if (filled > 0) {
            graphics.fill(box.x(), box.y(), box.x() + Math.min(filled, box.width()), box.bottom(),
                    colour);
        }
    }

    /** Draws a component at the left of a box, truncated with an ellipsis rather than overflowing it. */
    private void drawClipped(GuiGraphics graphics, Component text, BookLayout.Box box, int colour) {
        String plain = text.getString();
        if (font.width(plain) <= box.width()) {
            graphics.drawString(font, plain, box.x(), box.y(), colour, true);
            return;
        }
        String truncated = font.plainSubstrByWidth(plain, Math.max(0, box.width() - font.width("…")))
                + "…";
        graphics.drawString(font, truncated, box.x(), box.y(), colour, true);
    }

    /** Draws a component centred horizontally in a box, vertically centred too. */
    private void drawCentred(GuiGraphics graphics, Component text, BookLayout.Box box, int colour) {
        String plain = text.getString();
        int x = box.x() + (box.width() - font.width(plain)) / 2;
        int y = box.y() + (box.height() - font.lineHeight) / 2;
        graphics.drawString(font, plain, x, y, colour, true);
    }
}
