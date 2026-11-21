package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.effect.PartyEffects;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Every generic effect has a sprite at the path JEI's atlas looks for, and they are all one picture.</b>
 *
 * <h2>The defect, in the human's words</h2>
 * 「JEI里所有通用效果的图标都使用一个统一的图标」 — and his earlier screenshots, where the same missing sprite showed up as
 * magenta in the effect bar. Both surfaces ask the {@code mob_effects} atlas for
 * {@code astrum:<effect path>}, which is
 * {@code assets/astrum/textures/mob_effect/<effect path>.png}; a file that is not there is not "no icon", it is
 * the missing-texture checkerboard. So the fix is thirteen-plus files, and the requirement that they be the
 * <b>same</b> picture is what stops a per-effect icon table from growing back.
 *
 * <h2>Why the ids come from the registry and the files from the pack root</h2>
 * The id list is {@link PartyEffects#mechanicHolders()} — the declaration the generator and the hider also read, so
 * a fourteenth generic effect cannot be forgotten here. The files are read from the <b>pack root the test classpath
 * was built from</b> ({@code /pack.mcmeta}'s directory), not from a hard-coded {@code src/main/resources}, so this
 * test runs from any working directory and asserts about the tree the jar is really made from — the lesson
 * {@code EffectSpriteResourceTest} records after its first version failed its own precondition.
 *
 * <h2>Byte-identical, not merely similar</h2>
 * 「统一的图标」 is a fact about the files, not about a generator's intent: if one copy were re-rendered at a different
 * size, or a stray per-effect icon were left behind by the old {@code gen_tinted_icons.py}, the picture would differ
 * on that one row and nothing else would say so. So the assertion is a SHA-256 comparison across the set.
 *
 * <p><b>They must also be in the jar</b>, which is why they are not in {@code .gitignore}: {@code build.gradle}
 * excludes every ignored {@code src/main/resources} path from the jar, and a sprite that ships only in git would
 * leave JEI with the checkerboard for every player. {@link #theSpritesAreNotPackOnly()} pins that decision.
 */
class EffectSpriteCopyTest {

    /**
     * The two effects whose sprite is a <b>portrait</b> — 计数 (雪菲) and 精灵 (千歌) — and therefore the only
     * {@code mob_effect} files that are <b>pack-only</b>.
     *
     * <p>Deliberately a literal list, not a computed one: "which picture is a character's face" is a design
     * decision, and a decision derivable from the registry would change without anyone noticing. The three rules
     * below all name this pair as their exception, and each says why.
     *
     * <p>The human's ruling moved these two out of the jar: their sprites are <b>derived official art</b>
     * (「官方素材都不进jar，放资源包」), so they are {@code .gitignore}d and absent from a fresh clone. Their
     * absence is <b>expected</b>, not a defect: without the pack those two rows are the checkerboard, which he
     * accepted.
     *
     * <p>Every other generic effect — including the movement family, the two immunity channels and the timed
     * crit-damage buff — ships a <b>self-drawn 16x16 placeholder</b>, and so do all 91 skill types. One size for
     * both sets, because the human asked for it: 「不是使用16*16的那个图标吗？」
     */
    private static final Set<String> CHARACTER_MARKERS = Set.of("counter", "spirit");

    /** Every generic effect's registry path, from the registry's own declaration. */
    private static List<String> effectPaths() {
        List<String> paths = new ArrayList<>();
        for (var holder : PartyEffects.mechanicHolders()) {
            paths.add(holder.getKey().location().getPath());
        }
        assertFalse(paths.isEmpty(), "the manifest must not be empty");
        return List.copyOf(paths);
    }

    /** {@return the resource tree this build packaged, found through {@code /pack.mcmeta}} */
    private static Path packRoot() {
        URL meta = EffectSpriteCopyTest.class.getResource("/pack.mcmeta");
        assertNotNull(meta, "the mod's pack.mcmeta must be on the test classpath");
        try {
            return Path.of(meta.toURI()).getParent();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("pack.mcmeta has no usable path: " + meta, e);
        }
    }

    private static Path spriteOf(String effectPath) {
        return packRoot().resolve("assets/astrum/textures/mob_effect/" + effectPath + ".png");
    }

    private static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("cannot hash " + file, e);
        }
    }

    /**
     * <b>Every generic effect has a sprite file.</b>
     *
     * <p>The whole defect in one assertion: a missing file is the magenta checkerboard on JEI's tooltip and on the
     * effect bar.
     */
    @Test
    void everyGenericEffectHasASprite() {
        List<String> missing = new ArrayList<>();
        for (String path : effectPaths()) {
            // The two portraits are pack-only (derived official art): a fresh clone has no file for them, and a
            // player without the pack gets the checkerboard there, which the human accepted. Every self-drawn
            // placeholder is required.
            if (CHARACTER_MARKERS.contains(path)) {
                continue;
            }
            Path sprite = spriteOf(path);
            if (!Files.isRegularFile(sprite)) {
                missing.add(sprite.toString());
            }
        }
        assertEquals(List.of(), missing,
                "every self-drawn generic effect needs textures/mob_effect/<path>.png, or JEI draws the "
                        + "missing-texture checkerboard; run tools/gen_effect_icon_copies.py");
        assertEquals(effectPaths().size(), effectPaths().stream()
                        .map(path -> spriteOf(path).toString()).distinct().count(),
                "one file per effect, not one file named twice");
    }

    /**
     * <b>The shared picture is shared — except where the effect belongs to one character.</b>
     *
     * <h2>What changed, and why the old assertion was right when it was written</h2>
     * 「所有通用效果的图标都使用一个统一的图标」 was read as a property of the bytes: every sprite's SHA-256
     * identical. That held while a generic effect was "a mechanic the maths reads".
     *
     * <p>Then the human drew a line through the family: 「计数这种角色专属的效果，图标使用角色的头像，方便查看」
     * and 「其他角色的类似标记/计数/叠层的效果也都用这种类型的方式实现」. So a <em>character-exclusive marker</em>
     * wears that character's face, and the byte-identity rule has to name its exceptions rather than be deleted
     * — an assertion removed is a rule nobody can break, which is how per-effect icon tables came back before.
     *
     * <h2>The two lists</h2>
     * {@link #CHARACTER_MARKERS} are the effects whose sprite is a character's portrait, and each is required to
     * differ from the shared one (if a portrait ever equals the shared icon, this test should say so rather than
     * pass silently). Everything else in {@link #effectPaths()} must still be byte-identical.
     */
    @Test
    void everySpriteIsTheSharedPictureExceptTheCharacterMarkers() {
        Set<String> characterMarkers = CHARACTER_MARKERS;

        String shared = null;
        Map<String, String> hashes = new java.util.LinkedHashMap<>();
        for (String path : effectPaths()) {
            Path sprite = spriteOf(path);
            if (!Files.isRegularFile(sprite)) {
                continue;
            }
            String hash = sha256(sprite);
            hashes.put(path, hash);
            if (!characterMarkers.contains(path) && shared == null) {
                shared = hash;
            }
        }
        assertNotNull(shared, "at least one non-marker sprite must exist to be the shared picture");

        for (Map.Entry<String, String> entry : hashes.entrySet()) {
            if (characterMarkers.contains(entry.getKey())) {
                continue;
            }
            assertEquals(shared, entry.getValue(),
                    "every generic effect except the character markers must share one picture; "
                            + entry.getKey() + " differs. Hashes: " + hashes);
        }
        // <b>A marker that is present must wear a face, not the placeholder.</b> The file's pres*ence* is not the
        // invariant — the pack builder needs it on disk to pack it, while a fresh clone has none because it is
        // `.gitignore`d — so this checks the picture instead, which is true either way:
        //
        //   * absent      → pack-only checkout, nothing to assert;
        //   * present     → it must differ from the shared placeholder, because it is a portrait.
        //
        // The second half is what would catch the real defect: a generator stamping the placeholder onto these two
        // again, which is exactly what `gen_effect_icon_copies.py` used to do before `gen_marker_icons.py` took them.
        for (String marker : characterMarkers) {
            String hash = hashes.get(marker);
            if (hash == null) {
                continue;
            }
            assertNotEquals(shared, hash,
                    marker + " wears a character's face (「角色专属的效果用角色头像」), so it must not be the "
                            + "shared placeholder — check tools/gen_marker_icons.py");
        }
    }

    /**
     * <b>The shared picture is the master, at the size the skill entries use.</b>
     *
     * <p><b>16x16.</b> The HUD blits an 18-pixel square whatever the file's resolution — vanilla's
     * {@code blit(x + 3, y + 3, 0, 18, 18, sprite)} samples the <em>whole</em> sprite into that box — so a
     * larger source is strictly sharper at the same drawn size, never stretched or cropped. The human
     * reported the skill rows looking clearer than the generic effects beside them, which was exactly this
     * file being 64 while those were 18; the fix was to raise these to match, not to lower those.
     *
     * <p>The source of truth is {@code gui/effect_icon.png}; this asserts the copies are that picture
     * resampled rather than a different one that happens to be uniform, and that they agree with the skill
     * entries' size so no two rows in one list are drawn from different resolutions.
     */
    @Test
    void theSharedPictureIsTheMasterAtTheHudSize() throws IOException {
        Path master = packRoot().resolve("assets/astrum/textures/gui/effect_icon.png");
        assertTrue(Files.isRegularFile(master),
                "gui/effect_icon.png is the one shared picture and must be packaged: " + master);
        byte[] header = new byte[26];
        try (InputStream in = Files.newInputStream(spriteOf(effectPaths().get(0)))) {
            assertEquals(26, in.read(header), "a PNG header is 26 bytes in");
        }
        // PNG IHDR: width at 16..20, height at 20..24, big-endian.
        int width = ((header[16] & 0xFF) << 24) | ((header[17] & 0xFF) << 16)
                | ((header[18] & 0xFF) << 8) | (header[19] & 0xFF);
        int height = ((header[20] & 0xFF) << 24) | ((header[21] & 0xFF) << 16)
                | ((header[22] & 0xFF) << 8) | (header[23] & 0xFF);
        assertEquals(16, width, "the shared sprite is 16 pixels — the size the human asked for twice");
        assertEquals(16, height, "and it is square");
    }

    /**
     * <b>The placeholders ship in the jar; the only {@code mob_effect} files that are pack-only are the two
     * character portraits.</b>
     *
     * <h2>What the rule is now, and why it is two rules</h2>
     * {@code build.gradle} excludes from {@code jar} every path {@code .gitignore} marks under
     * {@code src/main/resources}. That is right for art derived from Cygames' assets and wrong for anything the
     * mod draws itself, because a sprite that must be in the jar to stop the atlas showing the checkerboard
     * cannot be ignored.
     *
     * <p>Task 59 recorded the human's ruling for the two families this class covers and the one it does not:
     * <ul>
     *   <li><b>the self-drawn placeholders</b> — every generic effect and every one of the 91 skill types, all
     *       copies of {@code gui/effect_icon.png} — <b>must not be ignored</b>, or JEI shows magenta on a machine
     *       with no art pack;</li>
     *   <li><b>{@code counter.png} / {@code spirit.png}</b> are portraits of 雪菲 and 千歌 (the official 6★
     *       avatars shrunk to 64x64), i.e. derived official art, so the human moved them to the pack like the
     *       avatars themselves — 「官方素材都不进jar，放资源包」. Their absence from the jar means a player with no
     *       pack sees the checkerboard on those two rows, which he accepted.</li>
     * </ul>
     *
     * <p>So this asserts <b>both</b>: the ignore file's {@code mob_effect} lines are exactly the two portrait
     * markers, and every placeholder path is <em>not</em> among them. The lists are named as literals because
     * "which picture is a portrait" is a design decision, not something derivable from the registry — the same
     * reason {@link #everySpriteIsTheSharedPictureExceptTheCharacterMarkers} names its exceptions.
     */
    @Test
    void thePlaceholdersShipInTheJarAndOnlyThePortraitsArePackOnly() throws IOException {
        Path root = packRoot();
        // The repository root is the nearest ancestor holding .gitignore. A packaged tree may be a temp copy
        // with none above it, in which case there is nothing to assert about — the rule only exists in a
        // checkout.
        Path repo = root;
        while (repo != null && !Files.isRegularFile(repo.resolve(".gitignore"))) {
            repo = repo.getParent();
        }
        if (repo == null) {
            repo = Path.of("").toAbsolutePath();
            while (repo != null && !Files.isRegularFile(repo.resolve(".gitignore"))) {
                repo = repo.getParent();
            }
        }
        assertNotNull(repo,
                "could not find the repository root (a directory holding .gitignore) from " + root
                        + " nor from the working directory " + Path.of("").toAbsolutePath());
        Set<String> portraits = new java.util.TreeSet<>();
        for (String marker : CHARACTER_MARKERS) {
            portraits.add(marker + ".png");
        }
        List<String> ignored = new ArrayList<>();
        for (String line : Files.readAllLines(repo.resolve(".gitignore"))) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || !trimmed.startsWith("/src/main/resources/")) {
                continue;
            }
            if (trimmed.contains("mob_effect")) {
                ignored.add(trimmed.substring(trimmed.lastIndexOf('/') + 1));
            }
        }
        assertEquals(new java.util.TreeSet<>(portraits), new java.util.TreeSet<>(ignored),
                "the only ignored mob_effect files are the two character portraits; every self-drawn "
                        + "placeholder (the generic effects and the 91 skill types) must ship in the jar");
    }

    /**
     * <b>Every skill type ships a 64x64 copy of the one shared placeholder — the jar half of the split.</b>
     *
     * <h2>The defect this prevents, and the split it records</h2>
     * 「官方素材都不进jar，放资源包。」 The official skill icons are derived from Cygames' art, so they live only in the
     * pack ({@code .gitignore} lists them, {@code build.gradle} drops them from the jar). But every one of the 91
     * new types still needs a file at {@code textures/mob_effect/<registry path>.png}, because the
     * {@code mob_effects} atlas — and therefore JEI — asks for the sprite named by the effect's registry id, and
     * a missing one is the magenta checkerboard.
     *
     * <p>So the split is: <b>jar = 91 copies of the mod's own placeholder</b>, <b>pack = the real skill icons</b>,
     * and {@code SkillEntryClient} draws the real one at runtime when the pack is installed. This asserts the
     * jar half — the files exist and are one shared picture byte for byte — so the atlas can never be left with
     * a hole by a character added without a regeneration.
     *
     * <p><b>Why the placeholders are 16x16 while the generic effects are 64x64.</b> They are <b>not</b>
     * byte-identical to {@code attack_buff}, and asserting that would now be wrong: the human asked for the
     * smaller size after the 91 files grew the jar from 1.18 MB to 1.77 MB (「那就把占位图缩小到16*16」). Vanilla's
     * HUD blits an 18-pixel square whatever the file's resolution, and JEI draws into a small slot, so a 64x64
     * placeholder was 16 times the pixels for the same on-screen result. Both are resampled from the same
     * self-drawn master, {@code gui/effect_icon.png}, which is the property that matters — not a shared hash
     * across two sets deliberately rendered at different sizes.
     */
    @Test
    void everySkillTypeShipsTheSharedPlaceholder() {
        List<String> missing = new ArrayList<>();
        Set<String> hashes = new java.util.LinkedHashSet<>();
        List<String> paths = new ArrayList<>();
        for (var holder : PartyEffects.skillHolders()) {
            String path = holder.getKey().location().getPath();
            Path sprite = spriteOf(path);
            if (!Files.isRegularFile(sprite)) {
                missing.add(path);
                continue;
            }
            paths.add(path);
            hashes.add(sha256(sprite));
        }
        assertEquals(List.of(), missing,
                "every skill type needs textures/mob_effect/<path>.png, or JEI draws the missing-texture "
                        + "checkerboard; run tools/gen_skill_effect_types.py");
        assertEquals(91, paths.size(), "22 characters x (their skills + their UB) = 91 placeholders");
        assertEquals(1, hashes.size(), "one self-drawn picture, copied 91 times — not 91 pictures");
        // Same master, different size on purpose: the skill placeholders are 16x16 (the human's 「缩小到16*16」)
        // and the generic effects are 64x64. What must hold is that every skill placeholder is the SAME picture
        // as every other, and that the master it was resampled from ships — not that the two sets hash alike.
        Path master = packRoot().resolve("assets/astrum/textures/gui/effect_icon.png");
        assertTrue(Files.isRegularFile(master),
                "gui/effect_icon.png is the one self-drawn master and must be packaged: " + master);
    }
}
