package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.AstrumApi;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.impl.attach.Party;
import com.astrum.pcrd.impl.growth.DishFeedHook;
import com.astrum.pcrd.impl.growth.GrowthFacts;
import com.astrum.pcrd.impl.network.PartyEditPayload;
import com.astrum.pcrd.impl.network.RosterSyncPayload;
import com.astrum.pcrd.impl.party.BookText;
import com.astrum.pcrd.impl.party.PartyRules;
import com.astrum.pcrd.impl.party.SkillRows;
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
 * The 角色册 screen: five portraits, two doors and her growth on a front page; the roster grid, party editor
 * and growth view behind it (spec §6.3.1).
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
 * <p>The front page shows the same five slots and the same ③ as a <b>summary</b> — one row of portraits, the
 * two doors, and her growth — so a player sees where she stands before opening the editor. It writes nothing:
 * a click there selects, and every party edit still goes through the roster page's gestures.
 *
 * <h2>Three pages, one screen</h2>
 * The book draws one of three pages, chosen by {@link #mode}:
 *
 * <ol>
 *   <li>{@link Mode#BOARD} — the <b>front page</b>, and what the book opens on: five party portraits in one
 *       horizontal row, two buttons under them on the left (「角色编队」 and 「技能详情」), and the selected
 *       character's growth information under them on the right. 「进入现在这个界面之前的界面，一排5个角色横着摆，角色行下面
 *       左侧两个按钮，一个是角色编队，一个是技能详情，角色行下面右侧显示养成选中角色的养成信息。」 It is a new page
 *       <b>in front of</b> the roster, not a replacement: 「点击才进入现在这个界面」.</li>
 *   <li>{@link Mode#ROSTER} — the roster grid, the party column and the growth view, i.e. exactly what this book
 *       was before the front page existed. It is now reached through the front page's 「角色编队」 button, and
 *       nothing on it changed.</li>
 *   <li>{@link Mode#SKILL_DETAIL} — the selected character's skills as a grid of columns, one column per skill
 *       version, each with a row per effect, and a click that opens that column's numbers. Reached from
 *       <b>both</b> pages' 「技能详情」 buttons: the front page got a second door to it, not a second copy.</li>
 * </ol>
 *
 * Each page is a mode rather than a second {@code Screen} because they are the same panel, the same palette and
 * the same nine-slice: a second screen would be a second {@code init}, a second panel draw and a second
 * background, i.e. two things to keep in step. The back chain is one step at a time — {@code SKILL_DETAIL} →
 * {@code ROSTER} → {@code BOARD} → closed — and {@link Mode#back()} is the only place it is written; every input
 * branch reads the mode <b>before</b> any geometry, which is what makes "the detail page's wheel cannot fight the
 * roster's" true by construction rather than by two rectangles happening not to overlap.
 *
 * <p>The detail page's geometry is {@link SkillDetailLayout}'s and its content {@link SkillDetailModel}'s; the
 * front page's geometry is {@link BookLayout}'s (its {@code board*} boxes). This class decides neither.
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

    // Section headings, body text and the two progress bars are fixed colours: they are drawn *on* the
    // panel, and a cream heading on a dark body reads the same whatever is behind it. Everything that is
    // part of the panel's own surface — the wells, the slots, the accent rules — comes from
    // {@link BookPalette}, which derives all of it from one base colour, so a cell border cannot end up in
    // a hue that is not in the family.
    private static final int COLOR_EXP_BAR = 0xFF55C8FF;
    private static final int COLOR_AFFECTION_BAR = 0xFFFF9AD5;
    private static final int COLOR_PIP = 0xFFFFC93C;
    private static final int COLOR_IN_PARTY = 0xFF55FF85;
    private static final int COLOR_TITLE = 0xFFF0E6C8;
    private static final int COLOR_TEXT = 0xFFE8E8E8;
    private static final int COLOR_DIM = 0xFF9AA4B8;
    private static final int COLOR_REFUSED = 0xFFFF7070;

    /**
     * The ink a column whose rung she has <b>not reached</b> is drawn in.
     *
     * <p>Darker and flatter than {@link #COLOR_DIM}: a locked column must be legible enough to be read and not
     * read as an available choice — that is exactly what 「未解锁的更高档 灰色显示（让玩家知道后面还有）」 asks for. The
     * value is one step below the body's mid grey so that, on the panel's dark body, it recedes without
     * disappearing.
     */
    private static final int COLOR_LOCKED = 0xFF6E7686;

    /**
     * The veil drawn over a locked column's skill picture.
     *
     * <p>Drawn <em>over</em> the official art rather than tinting it: the skill icons are the game's own
     * pictures, and multiplying them into a grey would be this mod claiming to redraw them. A flat 55% body
     * colour is the same "present but not yours yet" the locked ink says in words, and it keeps the picture
     * recognisable enough to be something to look forward to — 「未解锁的更高档 灰色显示（让玩家知道后面还有）」.
     */
    private static final int COLOR_LOCK_VEIL = 0x8C14161C;

    /** Opaque body of the numbers popup: it must hide the columns it is describing. */
    private static final int POPUP_BODY = 0xFF14161C;
    /** The one-pixel accent rule around the numbers popup — the book's gold, thin. */
    private static final int POPUP_RULE = 0xFFC8A24A;

    /** {@return the accent, for the thin rules drawn on the panel (hover frames, feed entry)} */
    private int colorAccent() {
        return BookPalette.accent();
    }

    /** {@return the cut-in surface: roster cells, party slots and bar backs} */
    private int colorSurface() {
        return BookPalette.surface();
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

    /**
     * The character the growth view is showing — <b>and</b> whose still fills the panel.
     *
     * <p>One field for both jobs, deliberately. The human asked for two things that are the same question:
     * 「点击头像后，显示完全的角色立绘当背景」 (a click shows that character's still) and the growth column shows
     * her level and affection. Two fields would let the picture behind the panel describe one character while
     * the numbers on it describe another, which is the kind of defect nobody notices until it is on screen.
     *
     * <p>Seeded on open by {@link TierAvatar#defaultSelection()} — party slot 1, then the roster's head, then
     * nobody — which is 「默认使用角色1当背景」.
     */
    private @Nullable ResourceLocation selected;

    /** Absolute roster index a left-drag started on, or {@code -1} when no drag is in progress. */
    private int dragFrom = -1;

    /**
     * Which of this screen's pages is open.
     *
     * <h2>Why a mode and not a second Screen</h2>
     * The skill-detail page is the same panel, the same palette and the same nine-slice, showing different
     * content — a second {@code Screen} class would mean a second {@code init}, a second panel draw, a second
     * background and a second place for the two to drift apart. So it is a field, {@code Esc} goes back to
     * {@link Mode#ROSTER}, and every input branch reads the mode <b>before</b> any geometry: that ordering is
     * what makes "the detail page's wheel cannot fight the roster's" true by construction rather than by two
     * rectangles happening not to overlap.
     *
     * <h2>Three pages now, and which one is the front door</h2>
     * {@link Mode#BOARD} is what the book <b>opens on</b>: five large portraits in one row, the two buttons and
     * the selected character's growth information. It is a new page <em>in front of</em> the roster rather than
     * a rearrangement of it, because the roster page is exactly what the human wanted kept — 「点击才进入现在这个
     * 界面」 — so the roster page, its grid, its party column, its growth panel and its skills entry are
     * untouched and {@code BOARD}'s 「角色编队」 button is simply its door now.
     *
     * <p>The <b>back</b> chain is one step at a time: {@code SKILL_DETAIL} → {@code ROSTER} → {@code BOARD}, and
     * {@code BOARD} → out of the book. That is why {@code Esc} is handled per mode rather than globally: on the
     * front page it closes the book (nothing is behind the front page), and on the other two it goes back one.
     */
    private Mode mode = Mode.BOARD;

    /**
     * The page the skill-detail page was opened <b>from</b>, so {@code Esc} goes back to that door.
     *
     * <h2>Why the detail page needs its own memory and the roster page does not</h2>
     * The same detail page now has two doors — the roster's skills entry and the front page's 「技能详情」 button —
     * and a back gesture that ignored which one it came through would drop a player who opened it from the front
     * page into the roster page she never asked for. The other two pages have exactly one page in front of them,
     * so for them {@link Mode#back()} is enough; this field is the one place the detail page's second door is
     * remembered, and {@code openSkillDetail} is the only thing that writes it.
     */
    private Mode detailReturn = Mode.ROSTER;

    /**
     * The three pages of the character book, in the order a player reaches them.
     *
     * <p>The declaration order is the <b>depth</b> order: {@link #BOARD} is the front, {@link #ROSTER} is one
     * step in, {@link #SKILL_DETAIL} is two. {@link #back()} walks it, so a new page is inserted here and the
     * back chain follows without a second {@code switch} to keep in step.
     */
    private enum Mode {
        /** The front page: five party portraits, two buttons, the selected character's growth. */
        BOARD,
        /** The roster, the party slots and the growth view — everything this book was before the front page. */
        ROSTER,
        /** The selected character's skills, one column per skill version. */
        SKILL_DETAIL;

        /** {@return the page one step towards the front, or {@code null} from the front page itself} */
        private @Nullable Mode back() {
            return switch (this) {
                case SKILL_DETAIL -> ROSTER;
                case ROSTER -> BOARD;
                case BOARD -> null;
            };
        }
    }

    /**
     * The detail page's content: one entry per skill version, each with its effects and their numbers.
     *
     * <p>Built when the page opens and rebuilt when the thing it describes changes (another selection, another
     * affection tier, another level) — see {@link #refreshSkillDetail}. It is <b>not</b> rebuilt per frame: it
     * formats every number of every effect, and this screen renders at the player's frame rate.
     */
    private SkillDetailModel detailModel = SkillDetailModel.EMPTY;

    /** The page's column geometry at scroll {@code 0}; shifted by {@link #detailScroll} where it is used. */
    private SkillDetailLayout.Grid detailGrid =
            SkillDetailLayout.grid(new BookLayout.Box(0, 0, 0, 0), SkillDetailLayout.Metrics.COMPACT,
                    List.of());

    /** How far the detail page is scrolled, in pixels; {@link SkillDetailLayout#clampScroll} bounds it. */
    private int detailScroll;

    /** Which column's numbers panel is open, or {@code -1} for none. */
    private int detailSelected = -1;

    /**
     * Which page of the open skill's numbers is shown, 0-based.
     *
     * <p>Paged rather than scrolled because the human asked for 翻页 (「数值界面做个翻页，佩可莉姆的效果太长了」):
     * a page boundary is a stable thing to point at, and 「3/7」 tells her how much is left in a way a pixel
     * offset cannot. Only the numbers page uses it; the grid keeps its own {@link #detailScroll}.
     */
    private int numbersPage;

    /** What {@link #detailModel} was built from, so a re-sync or a level-up rebuilds it. */
    private @Nullable ResourceLocation detailKeyCharacter;

    /** The affection tier {@link #detailModel} was built at; {@code -1} until it is built once. */
    private int detailKeyTier = -1;

    /** The level {@link #detailModel}'s level bands and EX formulas were evaluated at. */
    private int detailKeyLevel = -1;

    /**
     * The character <b>and texture</b> the panel drew last, so the swap is logged once per change rather than
     * per frame.
     *
     * <p>A {@code String} key rather than a single id, because the still changes inside one character: an
     * affection tier crossing swaps the picture while the selection stays put (「背景立绘要随着好感的升阶更换背景
     * 立绘」), and keying on the id alone would swallow exactly the line that proves it.
     *
     * <p>Instance state, cleared in {@link #init()} with the rest of the screen's: a static here would outlive
     * the screen, and the thing being logged is "this open drew this picture".
     */
    private @Nullable String loggedStillKey;

    /**
     * Opens the book: the only entry point, called from {@code CharacterBookItem.use} on the client.
     *
     * <p>No arguments. The screen used to take a {@code BookTheme} because there were three books; there is
     * one book now, so there is nothing left for the caller to decide and nothing that could disagree with
     * the item.
     */
    public CharacterBookScreen() {
        super(Component.translatable(BookText.KEY_TITLE));
    }

    /**
     * Shows the book, if there is a world to show it in.
     *
     * <p>The guard is not cosmetic: {@code setScreen} outside a world leaves a screen with no player
     * behind it, and {@code use} is only ever called with a player, so returning silently here would
     * hide a bug rather than prevent one — but a disconnect landing between the click and this call is
     * real, and a screen without a world would draw an empty book for nobody.
     *
     * <p>Re-opening while the same screen is already up is a no-op: the screen is modal, and the next open
     * starts from the default selection again.
     */
    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        if (minecraft.screen instanceof CharacterBookScreen) {
            return;
        }
        minecraft.setScreen(new CharacterBookScreen());
    }

    /**
     * <b>Pick mode was deleted with the 选择券.</b>
     *
     * <p>There used to be an {@code openForPick()} here and a {@code picking} flag on the screen: the voucher
     * opened this book, a click meant "claim this character", and the click sent a
     * {@code VoucherClaimPayload} to the server. The human removed the whole flow —
     * 「角色选择券打开后，弹出的是角色册，但是角色册没有角色给我选。直接随机获取一个没有的角色。」 — and he was
     * right about why it could not work: this panel draws the characters the player <b>owns</b>, so a
     * voucher-opened picker was always going to be empty for the player who needed it. A hundred 记忆结晶 now
     * roll a character directly, preferring one the player does not have
     * ({@code MemoryTokenItem} + {@code CardRoll.Mode#GUARANTEED_PREFER_UNOWNED}), and no screen is involved.
     *
     * <p>Nothing else used the flag, so the field, the entry point, the payload, its handler and the whole
     * {@code VoucherService} are deleted rather than left dormant — a second click meaning is exactly the
     * kind of state that rots into a bug.
     */

    @Override
    protected void init() {
        layout = BookLayout.at(width, height);
        firstCell = layout.clampFirstIndex(ClientRosterCache.size(), firstCell);
        // The per-open diagnostic memory: a resize re-inits this screen and the new panel's faces are
        // worth logging again (they are the same characters, but "the branch I took" is what the log is
        // for, and this is the one place a screen's state starts).
        faceBranchLogged.clear();
        loggedStillKey = null;
        if (selected != null && !ClientRosterCache.owns(selected)) {
            // The selected character is gone (a different player's book, or a save swap): clearing keeps
            // the growth view from describing somebody else's character.
            selected = null;
        }
        if (selected == null) {
            // 「默认使用角色1当背景」: party slot 1, then the roster's head, then nobody. Resolved through
            // TierAvatar so the rule has one implementation and one test, not a second copy here.
            selected = TierAvatar.defaultSelection().orElse(null);
        }
        if (mode == Mode.SKILL_DETAIL) {
            // A resize re-runs init: the panel changed size, so the page's columns have to be measured again.
            // The scroll position and the open numbers panel survive, clamped to the new grid.
            refreshSkillDetail();
        }
        // What this open resolved to, and the geometry it will draw with. This line is the only evidence
        // available for "the nine rectangles for the size this window actually has are non-degenerate",
        // for "the still is the default selection the human asked for", and for which character's art the
        // panel will blit: the panel size comes from the player's window and GUI scale, so none of it can
        // be asserted offline.
        NineSlice.Rects rects = NineSlice.rects(layout.panel(), layout.slice());
        OfficialPanel.isUsable(rects);
        Astrum.LOGGER.debug("astrum: character book opened ({}x{} gui, tier {} — panel {}x{} at {},{} of "
                        + "a {}x{} screen; margins l/r/t/b {}/{}/{}/{}; slice {} -> centre {}x{}; {} owned, "
                        + "{} in party; selected {} -> still {}{})",
                width, height, layout.tierName(), layout.panel().width(),
                layout.panel().height(), layout.panel().x(), layout.panel().y(), width, height,
                layout.panel().x(), width - layout.panel().right(), layout.panel().y(),
                height - layout.panel().bottom(), layout.slice(),
                rects.centre().width(), rects.centre().height(), ClientRosterCache.size(),
                ClientPartyCache.latest().slots().stream().filter(Optional::isPresent).count(),
                selected == null ? "(none)" : selected,
                stillOf(selected).map(ResourceLocation::toString).orElse("(no still art, plain dark body)"),
                ClientRosterCache.quarantined() ? ", quarantined save" : "");
        Astrum.LOGGER.debug("astrum: character book background is the world, dimmed {} -> {} behind a "
                        + "{}x{} panel; the world is visible on all four sides",
                Integer.toHexString(COLOR_DIM_TOP), Integer.toHexString(COLOR_DIM_BOTTOM),
                layout.panel().width(), layout.panel().height());
        // The front page's geometry, in the terms the human's sketch uses: five portraits of one edge in one
        // row, two buttons of one width, one growth panel — and whether each fits the body it was measured
        // against. `BookLayoutTest` asserts the same relations offline; this line reports the numbers for the
        // window the player actually has, which no unit test can produce.
        Astrum.LOGGER.debug("astrum: character book front page on {} — {}x{} portraits in a {}x{} row (body "
                        + "{}px wide), buttons {}x{} + {}x{} with {} between them, growth {}x{} at {},{}, page {}",
                mode, layout.boardSlotSize(), layout.boardSlotSize(), layout.boardRow().width(),
                layout.boardRow().height(), layout.bodyWidth(), layout.boardPartyButton().width(),
                layout.boardPartyButton().height(), layout.boardSkillsButton().width(),
                layout.boardSkillsButton().height(), layout.boardButtonGap(),
                layout.boardGrowthPanel().width(), layout.boardGrowthPanel().height(),
                layout.boardGrowthPanel().x(), layout.boardGrowthPanel().y(), mode);
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
        // The panel is official art, nine-sliced: the frame from `gui/book_panel.png` (cut from the game's own
        // rounded frame by tools/gen_book_panel_texture.py), and the body is the selected character's official
        // still. Geometry is NineSlice's (pure, tested) and the still's crop is StillCover's (pure, tested),
        // so "the panel we drew has no seam" and "the picture is not stretched" are assertions rather than
        // claims about a screenshot.
        ResourceLocation stillCharacter = stillCharacter();
        // The still is official art, so a player without astrum-assets.zip gets the 「请安装资源包」
        // placeholder in the panel instead of a checkerboard. `null` stays `null`: a character whose
        // definition ships no still keeps the panel's plain dark body, which is the documented degradation
        // and a different fact from "this build cannot find the art".
        ResourceLocation still = stillOf(stillCharacter).map(ArtFallback::resolve).orElse(null);
        logStillOnce(stillCharacter, still);
        OfficialPanel.draw(graphics, layout.panel(), StillCover.bodyOf(layout.panel()), still,
                layout.divider(), layout.slice());

        // The heading says which page is open. The book's own title would be a lie on the detail page:
        // 「角色册」 is what you press Esc to get back to. The front page keeps the book's title — it is the
        // book, opened — so only the detail page overrides it.
        drawCentred(graphics, Component.translatable(mode == Mode.SKILL_DETAIL
                ? BookText.KEY_SKILL_DETAIL_TITLE
                : BookText.KEY_TITLE), layout.title(), COLOR_TITLE);

        if (ClientRosterCache.quarantined()) {
            // The unreadable-save state wins over the mode: an unreadable book has no skills to describe, and
            // drawing a detail page from a half-read roster is exactly the invention this screen refuses.
            drawQuarantined(graphics);
        } else if (mode == Mode.SKILL_DETAIL) {
            drawSkillDetail(graphics, mouseX, mouseY);
        } else if (mode == Mode.BOARD) {
            drawBoard(graphics, mouseX, mouseY);
        } else {
            drawRoster(graphics, mouseX, mouseY);
            drawParty(graphics, mouseX, mouseY);
            drawGrowth(graphics, mouseX, mouseY);
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

    /**
     * Paints every roster cell on the current page that has no character in it.
     *
     * <h2>Why the empty cells are drawn at all</h2>
     * The human's screenshot showed a single roster cell sitting above a large empty band and read it
     * as the panel being broken (「上下像是被压缩了」). The band was not a layout hole: it was the part of
     * the grid the screen never painted, so the frame's body colour showed through and the grid read as
     * "one cell, then nothing". Painting the empty cells turns that band into what it is — slots waiting
     * for characters — which is both honest and the cheapest fix: no metric moves, so the small-window
     * behaviour and every {@code BookLayoutTest} assertion stay exactly as they were.
     *
     * <p>A very slightly darker fill, not the selected/hover tints: an empty slot must not look
     * selectable, and a hovered empty cell must not look different from an idle one. Hover is
     * deliberately not a parameter — an empty cell has no hover state at all.
     *
     * @param graphics the GUI buffer
     * @param count    the roster size
     * @param first    the absolute index the page starts at
     */
    private void paintEmptyCells(GuiGraphics graphics, int count, int first) {
        int owned = layout.visibleCells(count, first);
        for (int i = owned; i < layout.page(); i++) {
            BookLayout.Box cell = layout.cell(i);
            if (cell == null) {
                continue;
            }
            graphics.fill(cell.x(), cell.y(), cell.right(), cell.bottom(), colorEmptySlot());
        }
    }

    /** {@return the absolute index the current page starts at, clamped to the roster} */
    private int pageFirst(int count) {
        return layout.clampFirstIndex(count, firstCell);
    }

    /** {@return the fill of a roster cell with no character in it: the plain surface, no tint} */
    private int colorEmptySlot() {
        // The same surface an owned cell sits on when it is neither hovered nor selected, so a page of
        // empties is quiet rather than a second colour. Drawing them is the whole point: an unpainted
        // grid area read as a hole in the panel.
        return colorSurface();
    }
    private void drawRoster(GuiGraphics graphics, int mouseX, int mouseY) {
        List<RosterSyncPayload.Entry> entries = ClientRosterCache.entries();
        BookLayout.Box grid = layout.rosterGrid();
        // Every cell on the page is painted, empty or not. The human's screenshot showed one character
        // and a large unpainted band below it — the grid looked like a frame with a hole, because only
        // the owned cells were filled and the rest of the grid's area stayed the panel's body colour.
        // An empty cell now draws as what it is: a slot with nothing in it. The band is gone without
        // moving a single number in BookLayout.
        paintEmptyCells(graphics, entries.size(), pageFirst(entries.size()));
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
            int background = isSelected ? BookPalette.surfaceSelected()
                    : hovered ? BookPalette.surfaceHover() : colorSurface();
            graphics.fill(cell.x(), cell.y(), cell.right(), cell.bottom(), background);

            CharacterDef definition = definitionOf(entry.id());
            // The avatar follows the affection tier (1★ / 3★ / 6★): TierAvatar joins the row this cell is
            // already drawing with the definition's per-tier art, and CharacterDef.face(int) owns the
            // degradation rule. The tier comes from the server's row, never re-derived here.
            WheelIcon icon = WheelIcon.of(
                    TierAvatar.forRosterEntry(definition, entry),
                    entry.tier() >= GrowthFacts.MAX_TIER);
            logTierOnce(definition, entry);
            // Square, not circular: the picture is a square blit, so a ring at half its edge cut through her
            // face and left the corners outside it — 「角色册里面头像的那个圆圈」. The square frame hugs the slot
            // instead. See WheelIconDraw's class javadoc.
            WheelIconDraw.drawSquare(graphics, layout.cellIconLeft(i), layout.cellIconTop(i),
                    layout.cellIconDiameter(), icon, Optional.ofNullable(definition));

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
            int background = occupant.isPresent() ? BookPalette.slotFilled() : colorSurface();
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
            // Same rule as the roster cell, so a character cannot wear one face in the grid and another in
            // the slot beside it. The slot has no progress row of its own, so the tier comes from the roster
            // through TierAvatar.forPartySlot. The frame is square here too: the slot blits a square portrait
            // into a 16-pixel square, and the old ring cut its corners just as it did in the grid
            // (「编队槽也有这个问题」).
            WheelIcon icon = WheelIcon.of(
                    TierAvatar.forPartySlot(definition, occupant.get()),
                    ClientPartyCache.enhanced(slot));
            WheelIconDraw.drawSquare(graphics, box.centreX() - layout.slotIconDiameter() / 2,
                    box.centreY() - layout.slotIconDiameter() / 2, layout.slotIconDiameter(), icon,
                    Optional.ofNullable(definition));
        }
    }

    // -----------------------------------------------------------------------------------------
    // ③ The growth view
    // -----------------------------------------------------------------------------------------

    private void drawGrowth(GuiGraphics graphics, int mouseX, int mouseY) {
        drawGrowthInto(graphics, layout.growthColumn(), Component.translatable(BookText.KEY_SECTION_GROWTH),
                mouseX, mouseY, true);
    }

    /**
     * Draws one growth view — heading, level, experience bar, affection line, bar, next tier, feed entry.
     *
     * <h2>Why the column is a parameter</h2>
     * The front page shows the selected character's growth information too (「角色行下面右侧显示养成选中角色的养成
     * 信息」), in a different rectangle ({@link BookLayout#boardGrowthColumn()}). The task's constraint is explicit:
     * reuse this rendering, do not re-implement it. So the seven boxes come in as a {@link BookLayout.GrowthColumn}
     * and the method does not know which page is drawing it — a second copy of "level, experience, affection,
     * next tier" would be the copy that keeps saying 3★ after the first one learned about 6★.
     *
     * <p>The one thing that <b>does</b> differ is the skills entry: the roster page puts 「技能详情」 in the strip
     * under the feeding entry, while the front page has its own 「技能详情」 button in the band's left half. Passing
     * {@code withSkillsEntry} keeps that difference a parameter rather than a fork of the whole method.
     *
     * @param graphics        the GUI buffer
     * @param column          which seven boxes to draw into
     * @param heading         the section heading's text (the two pages word it in their own key)
     * @param mouseX          pointer x, for the skills entry's hover frame
     * @param mouseY          pointer y, for the skills entry's hover frame
     * @param withSkillsEntry whether to draw the roster page's skills entry under the feeding entry
     */
    private void drawGrowthInto(GuiGraphics graphics, BookLayout.GrowthColumn column, Component heading,
            int mouseX, int mouseY, boolean withSkillsEntry) {
        drawClipped(graphics, heading, column.heading(), COLOR_TITLE);

        RosterSyncPayload.Entry entry = selectedEntry();
        if (entry == null) {
            drawClipped(graphics, Component.translatable(BookText.KEY_GROWTH_NONE), column.levelLine(),
                    COLOR_DIM);
            drawClipped(graphics, Component.translatable(BookText.KEY_FEED_ROUTE), column.feedEntry(),
                    COLOR_DIM);
            if (withSkillsEntry) {
                drawSkillsEntry(graphics, null, mouseX, mouseY);
            }
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
        BookLayout.Box levelBox = column.levelLine();
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
        drawBar(graphics, column.expBar(),
                GrowthFacts.expFraction(entry.exp(), entry.expToNext()), COLOR_EXP_BAR);

        drawClipped(graphics, Component.translatable(BookText.KEY_GROWTH_AFFECTION, entry.affection(),
                entry.tier()), column.affectionLine(), COLOR_TEXT);
        drawBar(graphics, column.affectionBar(),
                definition == null ? GrowthFacts.NO_PROGRESS
                        : GrowthFacts.affectionFraction(entry.affection(), definition.affection()),
                COLOR_AFFECTION_BAR);

        if (definition == null) {
            drawClipped(graphics, Component.translatable(BookText.KEY_UNKNOWN_CHARACTER, name),
                    column.nextTierLine(), COLOR_DIM);
        } else {
            OptionalInt next = GrowthFacts.nextTierThreshold(entry.affection(), definition.affection());
            drawClipped(graphics, next.isPresent()
                            ? Component.translatable(BookText.KEY_GROWTH_NEXT, next.getAsInt())
                            : Component.translatable(BookText.KEY_GROWTH_MAX),
                    column.nextTierLine(), COLOR_DIM);
        }

        // The feeding entry point: a working action once a dish handler is installed, otherwise the route
        // the spec itself gives (§6.2: hold a dish, right-click). Never a dead button.
        boolean feedable = DishFeedHook.available();
        BookLayout.Box feed = column.feedEntry();
        if (feedable) {
            graphics.fill(feed.x(), feed.y(), feed.right(), feed.bottom(), BookPalette.slotFilled());
            graphics.fill(feed.x(), feed.y(), feed.right(), feed.y() + 1, colorAccent());
            drawClipped(graphics, Component.translatable(BookText.KEY_FEED_AVAILABLE), feed, COLOR_TEXT);
        } else {
            drawClipped(graphics, Component.translatable(BookText.KEY_FEED_ROUTE), feed, COLOR_DIM);
        }

        if (withSkillsEntry) {
            drawSkillsEntry(graphics, definition, mouseX, mouseY);
        }
    }

    // -----------------------------------------------------------------------------------------
    // ⓪ The front page: five portraits, two buttons, her growth information
    // -----------------------------------------------------------------------------------------

    /**
     * Draws the page the book opens on — the one 「点击才进入现在这个界面」 is about.
     *
     * <h2>The picture, in one block</h2>
     * <pre>
     *   [角色1] [角色2] [角色3] [角色4] [角色5]     <- BookLayout.boardRow(), five equal squares
     *   [角色编队] [技能详情]        养成信息…      <- the band under it: buttons left, growth right
     * </pre>
     * Every rectangle comes from {@link BookLayout} ({@code boardRow}, {@code boardSlot}, {@code boardPartyButton},
     * {@code boardSkillsButton}, {@code boardGrowthColumn}), so "the five slots are equal and inside the body" and
     * "the buttons do not overlap the growth panel" are assertions in {@code BookLayoutTest} rather than claims
     * about a window this project cannot screenshot.
     *
     * <h2>What it reuses instead of rebuilding</h2>
     * <ul>
     *   <li>the portraits are {@link TierAvatar#forPartySlot} through {@link WheelIconDraw#drawSquare} — the same
     *       join and the same drawing the party slots use, so a character cannot wear one face on the front page
     *       and another one click in, and the affection tier is read from the roster row, never re-derived;</li>
     *   <li>the party itself is {@link ClientPartyCache#slot(int)} — the one answer to "who is in slot N";</li>
     *   <li>the growth information is {@link #drawGrowthInto} with {@link BookLayout#boardGrowthColumn()}, i.e. the
     *       very method the roster page draws, in a different rectangle;</li>
     *   <li>the two buttons are the same surface/hover/frame vocabulary as the skills entry, so the page's
     *       controls are not a second design.</li>
     * </ul>
     *
     * <h2>An empty slot is drawn as an empty slot</h2>
     * Five boxes are always painted, occupied or not, with the slot's number in an empty one — the roster page
     * learned this the hard way (an unpainted area read as a hole in the panel). Nothing here is ever blank.
     *
     * @param graphics the GUI buffer
     * @param mouseX   pointer x, for hover frames
     * @param mouseY   pointer y, for hover frames
     */
    private void drawBoard(GuiGraphics graphics, int mouseX, int mouseY) {
        for (int slot = 0; slot < Party.SLOTS; slot++) {
            BookLayout.Box box = layout.boardSlot(slot);
            if (box == null) {
                continue;
            }
            Optional<ResourceLocation> occupant = ClientPartyCache.slot(slot);
            boolean isSelected = occupant.isPresent() && occupant.get().equals(selected);
            boolean hovered = box.contains(mouseX, mouseY);
            int background = occupant.isPresent() ? BookPalette.slotFilled() : colorSurface();
            graphics.fill(box.x(), box.y(), box.right(), box.bottom(), background);
            if (isSelected || hovered) {
                // The selected portrait wears the accent frame: it is the one the growth panel is describing,
                // and on a page of five faces that is the only thing telling the player which one it is.
                frame(graphics, box, colorAccent());
            }
            if (occupant.isEmpty()) {
                // The slot number, as the party row does: it doubles as the wheel position the character would
                // occupy, so an empty slot still says which key would cast her.
                drawCentred(graphics, Component.literal(String.valueOf(slot + 1)), box, COLOR_DIM);
                continue;
            }
            ResourceLocation id = occupant.get();
            CharacterDef definition = definitionOf(id);
            // The same join as the party slot, at a bigger edge — the front page scales the party up, it does
            // not frame faces differently. The tier comes from the roster row through TierAvatar.
            WheelIcon icon = WheelIcon.of(TierAvatar.forPartySlot(definition, id),
                    ClientPartyCache.enhanced(slot));
            logTierOnce(definition, ClientRosterCache.entry(id).orElse(null));
            int edge = layout.boardSlotIconDiameter();
            WheelIconDraw.drawSquare(graphics, box.centreX() - edge / 2, box.centreY() - edge / 2, edge, icon,
                    Optional.ofNullable(definition));
        }

        // The two buttons. 「角色编队」 opens the page this book used to open on; 「技能详情」 opens the detail page
        // that already exists — a second door to it, not a second copy.
        boolean skillsUsable = selected != null && definitionOf(selected) != null;
        drawBoardButton(graphics, layout.boardPartyButton(), Component.translatable(BookText.KEY_BOARD_PARTY),
                true, mouseX, mouseY);
        drawBoardButton(graphics, layout.boardSkillsButton(), Component.translatable(BookText.KEY_BOARD_SKILLS),
                skillsUsable, mouseX, mouseY);

        // The growth information, drawn by the roster page's own method into this page's column.
        drawGrowthInto(graphics, layout.boardGrowthColumn(),
                Component.translatable(BookText.KEY_BOARD_GROWTH), mouseX, mouseY, false);
    }

    /**
     * Draws one front-page button: a filled surface, a hover frame, and a centred label.
     *
     * <p>The same vocabulary as {@link #drawSkillsEntry} — surface, hover, accent frame — so the front page's
     * controls and the roster page's controls are recognisably the same kind of thing. A disabled button (no
     * character selected, so nothing to describe) is drawn in the dim ink and takes no hover, exactly as the
     * skills entry does when it has no character.
     *
     * @param graphics the GUI buffer
     * @param box      the button
     * @param label    its text
     * @param enabled  whether it acts at all
     * @param mouseX   pointer x
     * @param mouseY   pointer y
     */
    private void drawBoardButton(GuiGraphics graphics, BookLayout.Box box, Component label, boolean enabled,
            int mouseX, int mouseY) {
        boolean hovered = enabled && box.contains(mouseX, mouseY);
        graphics.fill(box.x(), box.y(), box.right(), box.bottom(),
                hovered ? BookPalette.surfaceHover() : colorSurface());
        if (hovered) {
            frame(graphics, box, colorAccent());
        }
        // Centred rather than left-aligned: these two are a pair of equal width, and a centred label is what
        // makes a pair read as one control group. drawClipped still truncates, so a longer translation cannot
        // escape the button.
        BookLayout.Box labelBox = layout.boardButtonLabel(box);
        String plain = label.getString();
        int x = labelBox.x() + Math.max(0, (labelBox.width() - font.width(plain)) / 2);
        drawClipped(graphics, label, new BookLayout.Box(x, labelBox.y(),
                Math.max(0, labelBox.right() - x), labelBox.height()),
                !enabled ? COLOR_DIM : hovered ? COLOR_TITLE : COLOR_TEXT);
    }

    // -----------------------------------------------------------------------------------------
    // ③b 技能详情: one button in the growth column, one page of columns behind it
    // -----------------------------------------------------------------------------------------

    /**
     * Draws the 「技能详情」 button, in exactly the strip the skill list used to occupy.
     *
     * <h2>Why the list became a button</h2>
     * The strip is one column of the growth view: on the compact tier its whole skill area is a heading plus
     * two rows, so ペコリーヌ's skills arrived as <b>one</b> row and a 「+N」 (「角色册界面的4技能，满了不显示」). Paging
     * the strip was the previous answer and it treated the symptom: two 9-pixel rows cannot show six skills,
     * cannot show a skill's effects, and cannot show any numbers even one per page. So the strip is now a
     * control that opens {@link Mode#SKILL_DETAIL}, where the whole panel body is available.
     *
     * <h2>What it shows: a themed button, and <b>no picture</b></h2>
     * 「技能详情按钮不要显示技能图标，使用角色册同风格的按钮配色就行。」 The button is a control, and a control is
     * drawn in the panel's own palette — a filled surface, a hover frame, the label and a chevron. It is not a
     * place where a skill is identified, so it wears no skill picture: the skill pictures belong to the detail
     * page's columns, which <b>are</b> a place where a skill is identified, and they keep theirs.
     *
     * <p>That reverses the previous version of this method, which put the selected character's first skill's
     * picture inside the button. {@code BookLayout.skillsEntryIcon()} and the reserved slot beside the label are
     * gone with it, so the label starts at the button's own left inset and there is no empty square left over
     * from a removed picture.
     *
     * @param graphics   the GUI buffer
     * @param definition the selected character's definition, or {@code null}
     * @param mouseX     pointer x, for the hover frame
     * @param mouseY     pointer y, for the hover frame
     */
    private void drawSkillsEntry(GuiGraphics graphics, @Nullable CharacterDef definition, int mouseX,
            int mouseY) {
        BookLayout.Box box = layout.skillsEntry();
        boolean usable = definition != null && selected != null;
        boolean hovered = usable && box.contains(mouseX, mouseY);
        graphics.fill(box.x(), box.y(), box.right(), box.bottom(),
                hovered ? BookPalette.surfaceHover() : colorSurface());
        if (hovered) {
            frame(graphics, box, colorAccent());
        }
        // A chevron, because the button opens something: a glyph, not a sentence, so it needs no translation.
        int chevronWidth = font.width(CHEVRON);
        int textTop = box.centreY() - font.lineHeight / 2;
        graphics.drawString(font, CHEVRON, box.right() - 5 - chevronWidth, textTop,
                usable ? COLOR_TITLE : COLOR_DIM, true);
        // The label's box comes from the layout and stops before the chevron: a long 「技能详情」 in another
        // language is truncated by drawClipped instead of running under the glyph.
        drawClipped(graphics, Component.translatable(BookText.KEY_SKILLS_OPEN), layout.skillsEntryLabel(),
                !usable ? COLOR_DIM : hovered ? COLOR_TITLE : COLOR_TEXT);
    }

    /** The chevron an entry button wears: it opens something rather than acting in place. */
    private static final String CHEVRON = ">";

    /**
     * Opens the detail page for the current selection and writes the line that proves it happened.
     *
     * <p>The log line is INFO and carries the facts only the running client knows: which character, which
     * affection tier, <b>how many columns including her ultimate</b>, how wide a column is and how much content
     * there is against how much body. That is the evidence for "six skills on the compact tier fit a sane grid"
     * on a real window, which no unit test can produce — and the column names say the ultimate is among them.
     */
    private void openSkillDetail() {
        CharacterDef definition = selected == null ? null : definitionOf(selected);
        if (definition == null || selected == null) {
            return;
        }
        // Remember which door this page came through, before the mode changes: the front page's 「技能详情」 and
        // the roster's skills entry both open it, and Esc has to go back to the one the player used.
        detailReturn = mode;
        mode = Mode.SKILL_DETAIL;
        detailScroll = 0;
        detailSelected = -1;
        detailKeyCharacter = null;   // force a build
        refreshSkillDetail();
        int ultimates = (int) detailModel.entries().stream().filter(SkillDetailModel.Entry::ultimate)
                .count();
        Astrum.LOGGER.info("astrum: character book skill detail opened for {} (tier {}, level {}): {} "
                        + "columns ({} of them the ultimate) of {}px in a {}x{} body, {}px of content, {} "
                        + "row(s) in all — {}",
                selected, TierAvatar.tierOf(selected), levelOf(selected), detailModel.size(), ultimates,
                detailGrid.columnWidth(), detailGrid.area().width(), detailGrid.area().height(),
                detailGrid.contentHeight(),
                detailModel.entries().stream().mapToInt(entry -> entry.rows().size()).sum(),
                detailGrid.titles());
        // The pictures. Two facts only this line can carry, because they depend on what the loaded packs
        // actually hold: which column is drawn with the skill's OWN art and which fell back to her portrait,
        // and whether the art is present on disk at all (a player without astrum-assets.zip gets the
        // 「请安装资源包」 placeholder from ArtFallback, and this names each one). The human has to confirm the
        // result looks right; this says what was asked for, per column.
        StringBuilder pictures = new StringBuilder();
        for (int index = 0; index < detailModel.size(); index++) {
            SkillDetailModel.Entry entry = detailModel.entry(index);
            Optional<ResourceLocation> own = entry == null ? Optional.empty() : entry.icon();
            Optional<ResourceLocation> shown = SkillArt.pictureFor(entry, portraitOf(selected));
            boolean installed = shown.isPresent() && ArtFallback.isInstalled(shown.get());
            if (pictures.length() > 0) {
                pictures.append(", ");
            }
            pictures.append(entry == null ? "(none)" : entry.name())
                    .append('=').append(shown.map(ResourceLocation::getPath).orElse("(placeholder)"));
            if (!own.isPresent()) {
                pictures.append("[portrait]");
            }
            if (!installed) {
                pictures.append("[not installed]");
            }
        }
        Astrum.LOGGER.info("astrum: character book skill detail pictures at {}x{} each — {}",
                detailGrid.metrics().nameIcon(), detailGrid.metrics().nameIcon(), pictures);
    }

    /**
     * Leaves the skill-detail page for the page that opened it.
     *
     * <p>The name is the old one — the detail page used to have exactly one door — but the destination is now
     * {@link #detailReturn}, so the front page's 「技能详情」 button and the roster's skills entry each get their own
     * page back. Both pages are one step from the detail page, so the walk is still one step at a time; what the
     * field adds is <em>which</em> of the two steps.
     */
    private void returnToRoster() {
        goBackTo(detailReturn);
    }

    /**
     * Moves to {@code target}, one step towards the front, and writes the line that proves it.
     *
     * <p>The single place page state is cleared for a move, so the three pages cannot each remember a different
     * subset: the detail page's scroll, its open numbers panel and its numbers page are dropped whenever the
     * detail page is left, because they describe a grid that is no longer on screen.
     *
     * @param target the page to open; the caller has already checked it is one step back
     */
    private void goBackTo(Mode target) {
        Mode from = mode;
        if (target == Mode.SKILL_DETAIL) {
            // Nothing goes *in* through here: the detail page is only ever entered through its buttons, which
            // build the model for the character it will describe.
            throw new IllegalArgumentException("the detail page is entered through its buttons, not Esc");
        }
        mode = target;
        detailSelected = -1;
        detailScroll = 0;
        numbersPage = 0;
        Astrum.LOGGER.info("astrum: character book {} -> {} ({})", from, target,
                selected == null ? "(nothing selected)" : selected);
    }

    /**
     * Rebuilds the page's model and geometry for the current selection, affection tier and level.
     *
     * <p>Called when the page opens, when the window is resized ({@link #init()}) and when the numbers it was
     * built from change — see {@link #drawSkillDetail}. Everything expensive (level bands, EX formulas, the
     * formatting of every parameter) happens here, once, rather than per frame.
     */
    private void refreshSkillDetail() {
        CharacterDef definition = selected == null ? null : definitionOf(selected);
        if (definition == null || selected == null) {
            // Her definition or her row went away under the page: drop one step back rather than leave a
            // detail page describing a character the client can no longer name. Through Mode.back() and not
            // a literal, so "where a page goes when it cannot be drawn" is the same chain Esc walks.
            mode = Mode.SKILL_DETAIL.back();
            return;
        }
        int tier = TierAvatar.tierOf(selected);
        int level = levelOf(selected);
        detailModel = SkillDetailModel.of(definition, tier, level);
        detailGrid = SkillDetailLayout.grid(layout.skillDetailBody(),
                SkillDetailLayout.metricsFor(layout), detailModel.columns());
        detailScroll = detailGrid.clamp(detailScroll);
        if (detailSelected >= detailModel.size()) {
            detailSelected = -1;
        }
        detailKeyCharacter = selected;
        detailKeyTier = tier;
        detailKeyLevel = level;
    }

    /**
     * {@return the character's level, or {@code 1} when the roster holds no row for her}
     *
     * <p>{@code 1} rather than {@code 0}: the level feeds {@code LevelBands} (which reads anything below the
     * first band as the first band) and the EX point formulas, and a character the roster cannot describe
     * should be drawn as a level-1 one rather than as one with no numbers at all.
     *
     * @param characterId the character
     */
    private int levelOf(ResourceLocation characterId) {
        return ClientRosterCache.entry(characterId).map(RosterSyncPayload.Entry::level).orElse(1);
    }

    /** Draws the whole detail page: the grid of columns, then the open numbers panel over it. */
    private void drawSkillDetail(GuiGraphics graphics, int mouseX, int mouseY) {
        // The page describes the selection's current tier and level, and both move under a re-sync: rebuild
        // when either changed, which is the only way this page can stay truthful while it is open.
        int tier = selected == null ? -1 : TierAvatar.tierOf(selected);
        int level = selected == null ? -1 : levelOf(selected);
        if (!java.util.Objects.equals(detailKeyCharacter, selected) || detailKeyTier != tier
                || detailKeyLevel != level) {
            refreshSkillDetail();
            if (mode != Mode.SKILL_DETAIL) {
                // The selection or her definition went away under the page (a re-sync, a pack swap): the
                // refresh dropped us back to the roster, and drawing a page nobody can select from would be
                // the second half of the same lie.
                return;
            }
        }
        BookLayout.Box area = layout.skillDetailBody();
        if (detailModel.isEmpty()) {
            drawClipped(graphics, Component.translatable(BookText.KEY_SKILLS_EMPTY), area, COLOR_DIM);
            return;
        }
        SkillDetailLayout.Metrics metrics = SkillDetailLayout.metricsFor(layout);
        SkillDetailLayout.Grid view = detailGrid.scrolled(detailScroll);
        // A chosen skill REPLACES the grid; it is not drawn over it.
        //
        // Two attempts at an overlay both failed, and the human put his finger on why: 「是文字的层级问题」
        // — the columns' text kept appearing inside the numbers box whatever the box's fill was, because an
        // overlay in a GUI is only ever as opaque as the draw order allows. Removing the under-layer removes
        // the whole class of bug: while a skill is chosen there are no columns on screen to bleed through.
        if (detailSelected >= 0) {
            drawSkillNumbers(graphics, metrics, view);
            return;
        }
        // Scissored to the body: the columns scrolled past the top would otherwise be drawn over the title
        // and the ornament, which are painted before this method runs.
        graphics.enableScissor(area.x(), area.y(), area.right(), area.bottom());
        for (int index = 0; index < view.size(); index++) {
            BookLayout.Box cell = view.cell(index);
            if (cell == null || cell.bottom() <= area.y() || cell.y() >= area.bottom()) {
                continue;   // entirely outside the body: nothing of it could be seen
            }
            drawSkillColumn(graphics, view, metrics, index, mouseX, mouseY);
        }
        graphics.disableScissor();
    }

    /**
     * Draws one column: the skill version's name, then one row per effect.
     *
     * <h2>Locked columns</h2>
     * A rung above the character's is drawn in {@link #COLOR_LOCKED} and wears 「未解锁」, and the rung she is on
     * wears 「当前」. Both the fact and its word come from the model ({@link SkillDetailModel.Entry#locked()} and
     * {@link SkillDetailModel.Entry#markerKey()}), so this method decides pixels and nothing else — which is what
     * lets "a locked rung renders in the locked style" be a test about the model rather than a claim about a
     * window.
     *
     * @param graphics the GUI buffer
     * @param view     the grid at the current scroll
     * @param metrics  the tier's measurements
     * @param index    which entry
     * @param mouseX   pointer x, for the hover frame
     * @param mouseY   pointer y, for the hover frame
     */
    private void drawSkillColumn(GuiGraphics graphics, SkillDetailLayout.Grid view,
            SkillDetailLayout.Metrics metrics, int index, int mouseX, int mouseY) {
        BookLayout.Box cell = view.cell(index);
        SkillDetailModel.Entry entry = detailModel.entry(index);
        if (cell == null || entry == null) {
            return;
        }
        boolean chosen = index == detailSelected;
        boolean hovered = !chosen && view.area().contains(mouseX, mouseY) && cell.contains(mouseX, mouseY);
        graphics.fill(cell.x(), cell.y(), cell.right(), cell.bottom(),
                chosen ? BookPalette.surfaceSelected()
                        : hovered ? BookPalette.surfaceHover() : colorSurface());
        if (chosen || hovered) {
            frame(graphics, cell, colorAccent());
        }
        int ink = entry.locked() ? COLOR_LOCKED
                : entry.ultimate() ? COLOR_PIP : entry.current() ? COLOR_TITLE : COLOR_TEXT;
        // One picture per COLUMN, because a column is one skill version (「一个技能可以引用多个通用效果的代码，但是
        // 图标只使用一个」). It is this skill's own art, else her portrait, else the placeholder — never nothing, and
        // never a per-effect picture: the rows under it stay text-only.
        BookLayout.Box iconBox = view.nameIcon(index);
        if (iconBox.width() > 0) {
            drawSquarePicture(graphics, iconBox, SkillArt.drawable(SkillArt.pictureFor(entry,
                    portraitOf(selected))));
            if (entry.locked()) {
                // A rung above hers: the picture is drawn through a dark veil rather than in another colour,
                // because the art is official and recolouring it is a bigger claim than dimming it. This is the
                // same "greyed but visible" rule the locked ink keeps for the words.
                graphics.fill(iconBox.x(), iconBox.y(), iconBox.right(), iconBox.bottom(), COLOR_LOCK_VEIL);
            }
        }
        BookLayout.Box nameBox = view.nameBox(index);
        String markerKey = entry.markerKey();
        String marker = markerKey.isEmpty() ? "" : Component.translatable(markerKey).getString();
        int markerWidth = marker.isEmpty() ? 0 : font.width(marker);
        boolean markerFits = markerWidth > 0 && markerWidth + 2 < nameBox.width();
        drawClipped(graphics, Component.literal(entry.name()),
                markerFits ? new BookLayout.Box(nameBox.x(), nameBox.y(),
                        nameBox.width() - markerWidth - 2, nameBox.height()) : nameBox, ink);
        if (markerFits) {
            graphics.drawString(font, marker, nameBox.right() - markerWidth, nameBox.y() + 1,
                    entry.locked() ? COLOR_LOCKED : COLOR_PIP, true);
        }
        for (int row = 0; row < entry.rows().size(); row++) {
            SkillDetailModel.Row content = entry.rows().get(row);
            BookLayout.Box label = view.effectLabel(index, row);
            BookLayout.Box tier = view.effectTier(index, row);
            if (label == null || tier == null) {
                break;   // past the column's own rows: nothing left to draw for this entry
            }
            // Text only: 「角色册里的数值显示……纯文字」. The icon box is reclaimed for the label by
            // SkillDetailLayout, so nothing is drawn into it here — an EFFECT has no picture, which is a
            // different rule from "a SKILL has one", and the column's picture is on its name row.
            drawClipped(graphics, Component.translatable(content.labelKey()), label,
                    entry.locked() ? COLOR_LOCKED : COLOR_TEXT);
            String tierText = content.tierText();
            if (!tierText.isEmpty()) {
                int width = font.width(tierText);
                if (width < tier.width()) {
                    graphics.drawString(font, tierText, tier.right() - width, tier.y(),
                            entry.locked() ? COLOR_LOCKED : COLOR_DIM, true);
                }
            }
        }
    }

    /**
     * Draws the numbers panel for the chosen column.
     *
     * <p>The panel is the book's own frame at the book's own slice ({@link OfficialPanel#draw}), not a second
     * panel implementation: 「不要另写一套九宫格」. Its box comes from
     * {@link SkillDetailLayout#popup} — below the column when it fits, above it otherwise, always inside the
     * panel — and its content from {@link SkillDetailModel.Entry#numberPanel()}, one line per parameter.
     *
     * @param graphics the GUI buffer
     * @param metrics  the tier's measurements
     * @param view     the grid at the current scroll, so the panel anchors to where the column really is
     */
    /** {@return the language key of the one line of guidance under the panel, for the page on screen} */
    private String footerHintKey() {
        if (mode == Mode.BOARD) {
            // The front page's own line: it has no grid to scroll and no slot to click, so the roster's party
            // hint would describe two gestures that do not exist here.
            return BookText.KEY_BOARD_HINT;
        }
        if (mode != Mode.SKILL_DETAIL) {
            return BookText.KEY_PARTY_HINT;
        }
        // The numbers page has its own way out — a click goes back, not deeper — so it must not advertise
        // 「点击技能看数值」, which is the page it is already on.
        return detailSelected >= 0
                ? BookText.KEY_SKILL_DETAIL_NUMBERS_HINT
                : BookText.KEY_SKILL_DETAIL_HINT;
    }

    private void drawSkillNumbers(GuiGraphics graphics, SkillDetailLayout.Metrics metrics,
            SkillDetailLayout.Grid view) {
        SkillDetailModel.Entry entry = detailModel.entry(detailSelected);
        BookLayout.Box popup = numbersBox(metrics, view);
        if (entry == null || popup.width() <= 0 || popup.height() <= 0) {
            detailSelected = -1;
            return;
        }
        // A thin frame, not the book's nine-slice.
        //
        // The nine-slice was tried twice and failed twice. Its frame is 18 px (compact) / 30 px
        // (comfortable) wide, while the numbers' own padding was 4-5 px, so every line of text started
        // *inside* the gold border — 「文字会出现在UI边框上」. And because the nine-slice silhouette is
        // rounded, its corners are cut away, so the skill columns underneath showed through them —
        // 「技能详情的文字会浮现在数值界面」. A tooltip-sized box simply has no room for a frame that
        // thick, so it gets an opaque body, a one-pixel accent rule, and a scissor: nothing under it can
        // show through, and nothing inside it can escape onto the border.
        graphics.fill(popup.x(), popup.y(), popup.right(), popup.bottom(), POPUP_BODY);
        graphics.renderOutline(popup.x(), popup.y(), popup.width(), popup.height(), POPUP_RULE);
        graphics.enableScissor(popup.x() + 1, popup.y() + 1, popup.right() - 1, popup.bottom() - 1);
        // Wrapped, not truncated. The human's correction: 「文字行不够显示的情况下，现在是用...做省略，改成换行显示完全。」
        // So the list this draws is the WIDE-AWARE one, and the page total comes from the same call — which is
        // what makes a long line add a page instead of losing its tail to an ellipsis.
        int textWidth = numbersTextWidth(popup, metrics);
        List<SkillDetailModel.Line> lines = entry.wrappedPanel(textWidth, font::width, this::translated);
        // Paged, not truncated. 佩可莉姆's ultimate has fifteen lines of parameters and the body holds
        // sixteen — one more parameter anywhere and the tail would simply vanish. A page holds everything
        // that fits except the last row, which is reserved for the 「3/7」 counter so the counter can never
        // cover a line of real content.
        int capacity = Math.max(1, SkillDetailLayout.visibleLines(popup, metrics));
        int rowsPerPage = Math.max(1, capacity - 1);
        int pages = Math.max(1, (lines.size() + rowsPerPage - 1) / rowsPerPage);
        boolean paged = pages > 1;
        int rows = paged ? rowsPerPage : capacity;
        numbersPage = Math.max(0, Math.min(numbersPage, pages - 1));
        int from = numbersPage * rows;
        for (int line = 0; line < rows && from + line < lines.size(); line++) {
            SkillDetailModel.Line content = lines.get(from + line);
            BookLayout.Box box = SkillDetailLayout.popupLine(popup, metrics, line);
            // drawClipped is still the right call: a WRAPPED line is guaranteed to fit, and the clip is what
            // guarantees it cannot spill if a future wrap were ever wrong. The ellipsis it falls back to can no
            // longer trigger for a wrapped line, so the 「…」 the human complained about is gone from this page
            // without removing the guard that keeps text inside the frame.
            drawClipped(graphics, Component.literal(content.displayText(this::translated)), box, switch (content.kind()) {
                case NAME -> COLOR_TITLE;
                case EFFECT -> COLOR_PIP;
                case NUMBER -> COLOR_TEXT;
            });
        }
        if (paged) {
            drawClipped(graphics, Component.literal((numbersPage + 1) + "/" + pages),
                    SkillDetailLayout.popupLine(popup, metrics, rows), COLOR_DIM);
        }
        graphics.disableScissor();
    }

    /**
     * {@return the box the numbers panel would occupy for the chosen column at the current scroll}
     *
     * <p>One function, asked by both the drawing code and the click handler, so the panel a player clicks into
     * is the panel that was drawn. Nothing about it depends on the frame — only on geometry and the entry's line
     * count — which is why this can be recomputed at input time instead of being remembered from the last
     * {@code render}.
     *
     * @param metrics the tier's measurements
     * @param view    the grid at the current scroll
     */
    private BookLayout.Box numbersBox(SkillDetailLayout.Metrics metrics, SkillDetailLayout.Grid view) {
        SkillDetailModel.Entry entry = detailModel.entry(detailSelected);
        if (entry == null) {
            return new BookLayout.Box(0, 0, 0, 0);
        }
        // The whole body, because the numbers are a page now, not a popup anchored to a column. It stays a
        // single function so the click handler and the renderer still cannot disagree about the rectangle.
        return layout.skillDetailBody();
    }

    /**
     * {@return the numbers panel's text width for the body box — the same number the drawing code uses}
     *
     * <p>One function for the renderer and the pager. Before the wrap change both counted <em>logical</em> lines,
     * so the two agreed by construction; now the count depends on the width, and two call sites computing it
     * separately is exactly how a page counter ends up disagreeing with the text beside it.
     */
    private static int numbersTextWidth(BookLayout.Box popup, SkillDetailLayout.Metrics metrics) {
        return Math.max(0, popup.width() - 2 * metrics.popupPadding());
    }

    /** {@return {@code key} as the player reads it, or {@code key} itself outside a client} */
    private String translated(String key) {
        return key == null || key.isEmpty() ? "" : Component.translatable(key).getString();
    }

    /** {@return one numbers-panel line as the text the font draws} */
    private String lineText(SkillDetailModel.Line line) {
        return line.displayText(this::translated);
    }

    /**
     * Draws a one-pixel frame around a box — the same four fills the party slots use for their hover state.
     *
     * @param graphics the GUI buffer
     * @param box      the box to frame
     * @param colour   the frame's ink
     */
    private void frame(GuiGraphics graphics, BookLayout.Box box, int colour) {
        graphics.fill(box.x(), box.y(), box.right(), box.y() + 1, colour);
        graphics.fill(box.x(), box.bottom() - 1, box.right(), box.bottom(), colour);
        graphics.fill(box.x(), box.y(), box.x() + 1, box.bottom(), colour);
        graphics.fill(box.right() - 1, box.y(), box.right(), box.bottom(), colour);
    }

    /**
     * Scrolls the detail page by {@code direction} rows, and answers whether it moved.
     *
     * <p>Answers {@code false} at either end, which is what lets the wheel fall through instead of swallowing
     * an event that changed nothing — the same contract the roster grid's paging has.
     *
     * @param direction negative is up
     */
    private boolean scrollSkillDetail(int direction) {
        int step = SkillDetailLayout.metricsFor(layout).effectRowHeight() * 3;
        int next = detailGrid.clamp(detailScroll + direction * step);
        if (next == detailScroll) {
            return false;
        }
        detailScroll = next;
        return true;
    }

    /**
     * Turns one page of the open skill's numbers.
     *
     * <p>Answers {@code false} at either end so the wheel falls through rather than swallowing an event that
     * changed nothing — the same contract {@link #scrollSkillDetail} keeps. The page size matches the one
     * {@link #drawSkillNumbers} draws with, and both read it from the same layout, so the counter and the
     * content cannot disagree.
     *
     * @param direction negative is previous
     */
    private boolean pageNumbers(int direction) {
        SkillDetailModel.Entry entry = detailModel.entry(detailSelected);
        if (entry == null) {
            return false;
        }
        SkillDetailLayout.Metrics metrics = SkillDetailLayout.metricsFor(layout);
        BookLayout.Box body = layout.skillDetailBody();
        int capacity = Math.max(1, SkillDetailLayout.visibleLines(body, metrics));
        int rowsPerPage = Math.max(1, capacity - 1);
        // The total is the WRAPPED count, through the same width and the same resolver the renderer uses —
        // otherwise a page could show a line that the counter had not counted, or the wheel would refuse to turn
        // to a page that exists.
        int total = entry.wrappedLineCount(numbersTextWidth(body, metrics), font::width, this::translated);
        int pages = Math.max(1, (total + rowsPerPage - 1) / rowsPerPage);
        int next = Math.max(0, Math.min(numbersPage + direction, pages - 1));
        if (next == numbersPage) {
            return false;
        }
        numbersPage = next;
        return true;
    }

    /**
     * Chooses the column whose numbers are open, or closes the panel when it is already that column.
     *
     * @param index the entry index, from {@link SkillDetailLayout.Grid#entryAt}
     */
    private void selectSkillColumn(int index) {
        if (index == detailSelected) {
            detailSelected = -1;
            return;
        }
        SkillDetailModel.Entry entry = detailModel.entry(index);
        if (entry == null) {
            detailSelected = -1;
            return;
        }
        detailSelected = index;
        numbersPage = 0;
        Astrum.LOGGER.info("astrum: character book skill detail clicked {} (rung {}{}, {} row(s), {} "
                        + "line(s) of numbers)",
                entry.name(), entry.rung(), entry.locked() ? ", locked" : "", entry.rows().size(),
                entry.numberLineCount());
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
        if (ClientRosterCache.quarantined()) {
            return;
        }
        // One line, and which line depends on the page: the roster's own hint names the party gestures, and
        // while the detail page is open those gestures do not exist, so it is the page's own hint that says
        // 「Esc 返回」, how the wheel behaves and that a column opens its numbers.
        drawClipped(graphics, Component.translatable(footerHintKey()), footer, COLOR_DIM);
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
        if (mode == Mode.SKILL_DETAIL) {
            return onSkillDetailClicked(mouseX, mouseY, button);
        }
        if (mode == Mode.BOARD) {
            return onBoardClicked(mouseX, mouseY, button);
        }
        OptionalInt cell = layout.rosterIndexAt(mouseX, mouseY, ClientRosterCache.size(), firstCell);
        if (cell.isPresent()) {
            RosterSyncPayload.Entry entry = ClientRosterCache.entries().get(cell.getAsInt());
            if (button == 0) {
                // The toggle rule lives in BookSelection, where it is unit-tested: a click on another cell
                // changes the still behind the panel (「点击头像后，显示完全的角色立绘当背景」).
                selected = BookSelection.select(selected, entry.id());
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
        if (button == 0 && selected != null && layout.skillsEntry().contains(mouseX, mouseY)) {
            // The skill strip is one control now: it opens the page rather than listing two rows of hooks.
            openSkillDetail();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * A click on the front page: a portrait selects, and the two buttons go where they say.
     *
     * <h2>Why a portrait click only selects</h2>
     * The front page's five boxes are the <b>party</b>, and the party is written by the roster page's
     * click-and-place gestures. Letting a click here also place a character would give the party a second write
     * path whose meaning depends on which page the player is on — the exact kind of state this screen has kept
     * out of itself from the beginning. So a click on an occupied portrait selects her (which swaps the growth
     * panel and the panel's background), and placing stays one page in, behind 「角色编队」.
     *
     * <p>An <b>empty</b> slot is inert for the same reason, and a click on it falls through to the screen's
     * default handling rather than sending an edit that could only be a mistake.
     *
     * @param mouseX pointer x
     * @param mouseY pointer y
     * @param button the mouse button; only the left one does anything on this page
     */
    private boolean onBoardClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        // The buttons first: they are drawn on top of the band and a long label must never be under a slot.
        if (layout.boardPartyButton().contains(mouseX, mouseY)) {
            openRosterPage();
            return true;
        }
        if (layout.boardSkillsButton().contains(mouseX, mouseY)) {
            if (selected != null && definitionOf(selected) != null) {
                openSkillDetail();
            }
            return true;
        }
        OptionalInt slot = layout.boardSlotAt(mouseX, mouseY);
        if (slot.isPresent()) {
            Optional<ResourceLocation> occupant = ClientPartyCache.slot(slot.getAsInt());
            if (occupant.isPresent()) {
                // The same toggle the roster grid uses (BookSelection), so clicking the selected portrait
                // again deselects her here exactly as it does one page in.
                selected = BookSelection.select(selected, occupant.get());
                ClientBookFeedback.clear();
                Astrum.LOGGER.info("astrum: character book front page selected {} from slot {}", selected,
                        slot.getAsInt());
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * Opens the roster page — the page this book opened on before the front page existed.
     *
     * <p>Reached from the front page's 「角色编队」 button. The page itself is unchanged; see {@link Mode}. The log
     * line is the only evidence a real client can give that the door was the door.
     */
    private void openRosterPage() {
        mode = Mode.ROSTER;
        // The grid's paging window is absolute; an entry point in from the front page starts on the first page,
        // where the character the front page was describing is most likely to be.
        firstCell = 0;
        Astrum.LOGGER.info("astrum: character book front page -> roster page ({} owned, {} in party, selected "
                        + "{})", ClientRosterCache.size(),
                ClientPartyCache.latest().slots().stream().filter(Optional::isPresent).count(),
                selected == null ? "(none)" : selected);
    }

    /**
     * A click while the detail page is open: the open numbers panel first, then a column, then a dismissal.
     *
     * <h2>Why the panel is tested before the columns</h2>
     * The numbers panel is <b>drawn over</b> the grid, so it is what the pointer is visually on when it sits
     * on the panel. Without this branch, clicking a number would select the column underneath and close the
     * panel the player was reading. The panel's rectangle comes from the same pure function the drawing code
     * used ({@link #numbersBox}), so "what I clicked" and "what I saw" cannot disagree.
     *
     * @param mouseX pointer x
     * @param mouseY pointer y
     * @param button the mouse button
     */
    private boolean onSkillDetailClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return true;   // the detail page has no right-click gesture; consume rather than fall through
        }
        SkillDetailLayout.Metrics metrics = SkillDetailLayout.metricsFor(layout);
        SkillDetailLayout.Grid view = detailGrid.scrolled(detailScroll);
        if (detailSelected >= 0) {
            // The numbers page owns the whole body: any left click goes back to the grid. Clicking the
            // numbers themselves must not re-open the same skill, which is what the old "consumed but kept
            // open" branch did.
            detailSelected = -1;
            return true;
        }
        OptionalInt entry = view.entryAt(mouseX, mouseY);
        if (entry.isPresent()) {
            selectSkillColumn(entry.getAsInt());
            return true;
        }
        if (detailSelected >= 0) {
            detailSelected = -1;   // a click on the page's empty space closes the panel
            return true;
        }
        return true;
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
        // Only the page that draws the slots can complete a drag onto one. A drag started on the roster and
        // finished after the page changed must not land on a party row that is no longer on screen.
        if (button == 0 && dragFrom >= 0 && mode == Mode.ROSTER) {
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

    /**
     * The mouse wheel: <b>one consumer per page</b>, decided by the mode before any geometry is consulted.
     *
     * <h2>The conflict the task asked to resolve explicitly</h2>
     * Before this page existed the wheel had two claimants <em>inside this screen</em>: the roster grid, which
     * pages the grid over its own rectangle, and the skill strip, which paged the hook list over its strip
     * (「角色册界面的4技能，满了不显示……是滚轮上下翻页还是怎么做？」). The detail page is a third list with the same
     * gesture. Three claims and one wheel is how a wheel ends up paging two lists at once.
     *
     * <p>The resolution, in two steps, both structural rather than a priority order:
     * <ol>
     *   <li><b>The strip's claim is gone.</b> The strip is a button now, not a list, so there is nothing there
     *       to page and its branch — and {@code SkillIcons.Pages} with it — is deleted rather than left
     *       dormant.</li>
     *   <li><b>The mode decides first.</b> A wheel event while {@link Mode#SKILL_DETAIL} is open is answered by
     *       the detail page or by nobody: the roster branches are below an early return, so a wheel over the
     *       detail page can never also page the grid behind it. In {@link Mode#ROSTER} the detail branch is
     *       unreachable for the mirror-image reason. There is no overlap to arbitrate, so there is no order to
     *       get wrong.</li>
     * </ol>
     * Both branches answer {@code true} only when something actually moved, so the end of a list lets the
     * event fall through to vanilla's own handling instead of swallowing it.
     *
     * <p>The preset wheel (the {@code B} key's overlay) is not a claimant at all: it is a HUD layer, and
     * {@code setScreen} routes input to this screen while it is open.
     *
     * <h2>Direction</h2>
     * Vanilla's {@code scrollY} is positive when the wheel is rolled <em>up</em> (away from the player), and
     * that means "go back" here — up the page, up a row — matching every list the player has ever scrolled.
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int direction = scrollY > 0 ? -1 : 1;
        if (mode == Mode.SKILL_DETAIL) {
            if (detailSelected >= 0 && layout.skillDetailBody().contains(mouseX, mouseY)) {
                // The numbers page owns the wheel while it is open: its own pager, not the grid's scroll,
                // because the grid is not on screen.
                return pageNumbers(direction);
            }
            if (layout.skillDetailBody().contains(mouseX, mouseY) && scrollSkillDetail(direction)) {
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        int count = ClientRosterCache.size();
        // Gated on the mode, not just on the rectangle: the grid's rectangle still exists on the front page
        // (every page is the same panel), so a rectangle test alone would let the wheel page a grid nobody can
        // see — and the player would find the roster page scrolled by an invisible gesture.
        if (mode == Mode.ROSTER && count > layout.page() && layout.rosterGrid().contains(mouseX, mouseY)) {
            int next = layout.clampFirstIndex(count, firstCell + direction * layout.columns());
            if (next != firstCell) {
                firstCell = next;
                return true;
            }
            return false;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /**
     * The arrow keys, on both pages.
     *
     * <p>Present because a wheel is not always the thing under the player's hand. On the roster they move the
     * grid by one row — the same clamp the wheel uses, so the two inputs cannot land on different pages. On the
     * detail page they scroll it. {@code Esc} is the third key: it is the way <b>back</b> to the roster, and it
     * is consumed here rather than reaching vanilla, which would close the whole book instead of the page.
     * {@code Left}/{@code Right} are left alone, so a future binding there is not pre-empted.
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean up = keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_UP;
        boolean down = keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
        if (mode == Mode.SKILL_DETAIL) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                returnToRoster();
                return true;
            }
            if ((up || down) && scrollSkillDetail(up ? -1 : 1)) {
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            // One step towards the front, from whichever page is open — and from the front page itself,
            // {@link Mode#back()} is null, so Esc falls through to vanilla and closes the book. That is the
            // whole back chain: 技能详情 -> 角色册 -> 角色一览 -> closed.
            Mode back = mode.back();
            if (back != null) {
                goBackTo(back);
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        // Same gate as the wheel: the grid's paging keys belong to the page that draws the grid.
        if (mode == Mode.ROSTER && (up || down)) {
            int count = ClientRosterCache.size();
            int next = layout.clampFirstIndex(count, firstCell + (up ? -1 : 1) * layout.columns());
            if (next != firstCell) {
                firstCell = next;
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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
     * {@return the character whose still fills the panel}
     *
     * <p>The selection, or — when the player has deselected everything by clicking the same cell twice — the
     * default again. That is deliberate and it is the human's rule, not a convenience: he asked for
     * 「默认使用角色1当背景」, so the panel has <em>some</em> character behind it whenever the book holds one.
     * A deselected book showing a flat black panel would be the state he complained about (「深色底替」) coming
     * back through a different door. The growth column still goes blank on a deselect, because "no character
     * selected" is a real answer for numbers and never a real answer for a background.
     */
    private @Nullable ResourceLocation stillCharacter() {
        return BookSelection.background(selected).orElse(null);
    }

    /**
     * {@return the still to draw behind the panel for {@code characterId}, empty when there is none}
     *
     * <p>Delegates to {@link TierAvatar#stillOf(CharacterDef, ResourceLocation)}, which is where the "which
     * picture fills the panel <em>at this character's affection tier</em>" rule is written. Reading
     * {@code definition.still()} here directly would work today and would be the second copy the moment
     * anything else needs the same answer — and it would also pin the background to one picture forever,
     * which is the defect 「背景立绘要随着好感的升阶更换背景立绘」 describes.
     *
     * @param characterId the character, or {@code null} for "nothing selected"
     */
    private Optional<ResourceLocation> stillOf(@Nullable ResourceLocation characterId) {
        return characterId == null ? Optional.empty()
                : TierAvatar.stillOf(definitionOf(characterId), characterId);
    }

    /**
     * {@return the portrait to fall back to for {@code characterId}, at her <b>current</b> affection tier}
     *
     * <p>The middle link of {@link SkillArt}'s chain: a skill the pack ships no picture for is drawn with her
     * face — the same picture the roster cell and the party slot draw, through the same
     * {@link TierAvatar#forPartySlot} join, so the book cannot show two different faces for one character.
     *
     * @param characterId the character, or {@code null}
     */
    private Optional<ResourceLocation> portraitOf(@Nullable ResourceLocation characterId) {
        if (characterId == null) {
            return Optional.empty();
        }
        return TierAvatar.forPartySlot(definitionOf(characterId), characterId);
    }

    /**
     * Blits one square picture into {@code box}: the skill icon, a portrait, or the placeholder.
     *
     * <p>One call site for every square skill picture this screen draws, so the button's picture and the detail
     * page's columns cannot end up with different sampling. The texture is always the whole image scaled into
     * the box ({@code uWidth == textureWidth}), which is the form the 64x64 skill icons and the 128x128
     * placeholder both need; {@code ArtFallback} has already answered which file to read.
     *
     * @param graphics  the GUI buffer
     * @param box       the square to fill
     * @param texture   the texture to blit, already resolved by {@link SkillArt#drawable}
     */
    private void drawSquarePicture(GuiGraphics graphics, BookLayout.Box box, ResourceLocation texture) {
        if (box.width() <= 0 || box.height() <= 0) {
            return;
        }
        graphics.blit(texture, box.x(), box.y(), box.width(), box.height(), 0.0F, 0.0F, box.width(),
                box.height(), box.width(), box.height());
    }

    /**
     * Logs, once per change, which character's still the panel is drawing — <b>and</b> which tier picked it.
     *
     * <p>This is the evidence for three things the human asked for and no test can see: that the book opens
     * with slot 1's character as the background (「默认使用角色1当背景」), that clicking another cell swaps it
     * (「点击头像后，显示完全的角色立绘当背景」), and that the picture follows affection (「背景立绘要随着好感的升阶
     * 更换背景立绘」). The third one is why the log key is the character <b>plus the still</b> and not the
     * character alone: a tier crossing changes the picture while the selection stays put, and that is exactly
     * the swap worth proving.
     *
     * <p>A per-frame line would be a formatting cost at 300 fps for a fact that moves when the player clicks
     * or gains affection, so it fires on a change and names the tier and the texture.
     *
     * @param characterId the selected character, or {@code null}
     * @param still       the texture that will be blitted, or {@code null}
     */
    private void logStillOnce(@Nullable ResourceLocation characterId, @Nullable ResourceLocation still) {
        // Keyed on both halves: within one character the still can change (a tier crossing), and that swap
        // is the whole point of the field being tiered.
        String key = (characterId == null ? "(none)" : characterId.toString()) + "|"
                + (still == null ? "(none)" : still.toString());
        if (key.equals(loggedStillKey)) {
            return;
        }
        String before = loggedStillKey;
        loggedStillKey = key;
        if (characterId == null) {
            Astrum.LOGGER.info("astrum: book still background -> (none): nothing is selected, so the panel "
                    + "keeps its plain dark body (was {})", before);
            return;
        }
        int tier = TierAvatar.tierOf(characterId);
        Astrum.LOGGER.info("astrum: book still background {} -> {} draws {} at tier {} (affection {})",
                before, characterId,
                still == null ? "(no still art, plain dark body)" : still.toString(),
                tier, ClientRosterCache.entry(characterId)
                        .map(RosterSyncPayload.Entry::affection).orElse(-1));
    }

    /**
     * Logs, once per character per open, which avatar the affection tier resolved to.
     *
     * <p>The human's request is 「角色头像要跟随好感度进行变化，符合前面的1* 3* 6*的要求」, and the sketch the
     * appearance of that rule is the one thing no test can see. So the chosen texture and the tier that chose
     * it are written down, once per character per open — {@link #faceBranchLogged} is the memory and
     * {@link #init()} is where it starts, so a resize relogs and a 300 fps render does not.
     *
     * @param definition the character, or {@code null} when the pack no longer defines her
     * @param entry      her progress row, or {@code null}
     */
    private void logTierOnce(@Nullable CharacterDef definition, @Nullable RosterSyncPayload.Entry entry) {
        if (definition == null || !faceBranchLogged.add(definition.displayName())) {
            return;
        }
        int tier = entry == null || entry.tier() <= 0 ? TierAvatar.UNSYNCED_TIER : entry.tier();
        Astrum.LOGGER.debug("astrum: book avatar for {} at tier {} (affection {}) -> {} [1★={} 3★={} 6★={}]",
                definition.displayName(), tier, entry == null ? "(no row)" : entry.affection(),
                definition.face(tier).map(ResourceLocation::toString).orElse("(none, glyph disc)"),
                definition.icon1st().map(ResourceLocation::getPath).orElse("(none)"),
                definition.icon3rd().map(ResourceLocation::getPath).orElse("(none)"),
                definition.icon6th().map(ResourceLocation::getPath).orElse("(none, falls back to 3★)"));
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
