package com.astrum.pcrd.impl.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Which official art file each character's three tier avatars come from — asserted as data.</b>
 *
 * <h2>The defect this is about</h2>
 * The human opened the 角色册, watched the avatar change as affection rose, and said the pictures were wrong:
 * 「我在查看角色册的好感度变化图标时，好像不是这几个，感觉2个解包输出的文件问题，编码对不上」. Two separate things had
 * to be true for him to be right, and this test pins both:
 *
 * <ol>
 *   <li><b>The id table</b> — which unit block and which rarity each character's three files come from.
 *       Getting this wrong ships マホ's face for コッコロ.</li>
 *   <li><b>The source directory</b> — the same ids read out of a different dump are <em>different images</em>.
 *       {@code assets/pcr/icon/unit/105931.webp} is コッコロ in her 3★ outfit;
 *       {@code assets/pcr/extracted/图片/icon_unit_105931.png} is a cream-haired blue-eyed girl who appears in
 *       none of コッコロ's stills, plates or cards. Same id, different girl — so "the mapping is right" was
 *       never sufficient, and the earlier revision of this work shipped the wrong pictures with a right table.</li>
 * </ol>
 *
 * <h2>Why the assertions read the Python table rather than a Java copy</h2>
 * The mapping is the generator's business: {@code tools/gen_tier_avatars.py} is what turns an art id into a
 * committed PNG, and {@code tools/gen_portraits.py} does the same for the tierless {@code avatar}. Writing the
 * table a second time in Java would create exactly the drift this test exists to catch — the code would agree
 * with itself while the file the game draws came from somewhere else. So the test parses the generators'
 * own {@code TIER_AVATARS} / {@code AVATARS} dicts and checks <em>those</em>, which is the same technique
 * {@code SkillIconsTest} uses for {@code gen_skill_icons.py}'s {@code ICONS}.
 *
 * <h2>What only a human can confirm</h2>
 * That the girl in each PNG <em>is</em> the girl he expects. No assertion here can see a face: what is
 * checked is that the file read is the file named, that the three tiers of one character differ, and that
 * every character's pictures come from one unit block. The identification itself was done by opening the
 * twelve files and the official name plates — 1057 ジータ, 1058 ペコリーヌ, 1059 コッコロ, 1060 キャル — and is
 * recorded in the generator docstrings.
 */
class TierAvatarSourceTest {

    /** The generator that writes {@code <character>_<star>.png}. */
    private static final String TIER_GENERATOR = "tools/gen_tier_avatars.py";

    /** The generator that writes the tierless {@code <character>.png}. */
    private static final String PORTRAIT_GENERATOR = "tools/gen_portraits.py";

    /**
     * The verified mapping, as the <b>expected</b> answer.
     *
     * <p>Written out here rather than derived, because a test that computes the expectation from the thing it
     * is testing asserts nothing. These are the ids the human listed re-confirmed (his own message repeated
     * {@code 105931} twice and gave コッコロ's 1★ as {@code 105931}; the 1★ is {@code 105911}, and the files
     * are the authority).
     *
     * <p>The blocks: <b>1057 ジータ</b>, <b>1058 ペコリーヌ</b>, <b>1059 コッコロ</b>, <b>1060 キャル</b>. The
     * trailing digit pair is the rarity — {@code 1} 1★, {@code 3} 3★, {@code 6} 6★ — with {@code 2}/{@code 4}
     * being the 2★/4★ arts no tier draws.
     */
    private static final Map<String, List<String>> EXPECTED = ordered(
            "djeeta", List.of("105711", "105731", "105761"),
            "kokkoro", List.of("105911", "105931", "105961"),
            "pecorine", List.of("105811", "105831", "105861"),
            "kyaru", List.of("106011", "106031", "106061"));

    /** Each character's unit block: the first four digits every one of her three ids starts with. */
    private static final Map<String, String> UNIT_BLOCK = ordered(
            "djeeta", "1057",
            "kokkoro", "1059",
            "pecorine", "1058",
            "kyaru", "1060");

    /**
     * <b>The tier table in the generator is the verified one, and every character's three ids share her block.</b>
     *
     * <p>The block assertion is the cheap invariant that would have caught the one plausible way to get this
     * wrong: taking a neighbouring character's id. Two characters cannot share a block, and a character's tier
     * art cannot come from another block, so a transposed id fails here even if the expected table were somehow
     * edited to match.
     */
    @Test
    void theTierTableIsTheVerifiedMapping() throws IOException {
        Map<String, Map<Integer, String>> table = parseTierTable(read(TIER_GENERATOR));

        assertEquals(EXPECTED.keySet(), new LinkedHashSet<>(table.keySet()),
                "every shipped character, and only those, must have tier art: " + TIER_GENERATOR);

        for (Map.Entry<String, List<String>> entry : EXPECTED.entrySet()) {
            String character = entry.getKey();
            Map<Integer, String> tiers = table.get(character);
            assertNotNull(tiers, character + " is missing from TIER_AVATARS");

            assertEquals(List.of(1, 3, 6), List.copyOf(new java.util.TreeSet<>(tiers.keySet())),
                    character + " must name exactly the three rungs of the affection ladder");
            // Compared bare: the table spells the art id the way the official dump does
            // (`icon_unit_105711`), while EXPECTED records the bare id the filename carries.
            assertEquals(entry.getValue(),
                    List.of(strip(tiers.get(1)), strip(tiers.get(3)), strip(tiers.get(6))),
                    character + "'s 1★/3★/6★ art must be the verified ids");

            String block = UNIT_BLOCK.get(character);
            for (Map.Entry<Integer, String> tier : tiers.entrySet()) {
                String art = strip(tier.getValue());
                assertTrue(art.startsWith(block),
                        character + " " + tier.getKey() + "★ names " + art + ", which is not in her unit "
                                + "block " + block + " — that is how a neighbouring character's face gets "
                                + "shipped");
            }
            // The rarity is the last digit of the art id, and it is what makes the three a ladder rather
            // than three arbitrary pictures of her: <block>11 / <block>31 / <block>61, i.e. the block plus
            // the rarity digit plus the trailing series digit the atlas always carries.
            assertEquals(block + "11", strip(tiers.get(1)), character + "'s 1★ is her " + block + "11 art");
            assertEquals(block + "31", strip(tiers.get(3)), character + "'s 3★ is her " + block + "31 art");
            assertEquals(block + "61", strip(tiers.get(6)), character + "'s 6★ is her " + block + "61 art");
        }
    }

    /**
     * <b>Both generators read {@code assets/pcr/icon/unit}, in webp, not the extracted PNG dump.</b>
     *
     * <p>This is the half that the id table cannot cover, and the half that actually shipped wrong: the
     * {@code extracted/图片} files are tight head crops with the background cut away, and at least one of them
     * is a different girl entirely. The human named the directory to use and he was right, so the directory is
     * the assertion.
     *
     * <p>Both scripts, because a tierless {@code avatar} from one directory and tier icons from the other
     * would draw two different コッコロ depending on which surface asked.
     */
    @Test
    void bothGeneratorsReadTheIconAtlas() throws IOException {
        for (String generator : List.of(TIER_GENERATOR, PORTRAIT_GENERATOR)) {
            String source = read(generator);
            assertTrue(source.contains("pcr\", \"icon\", \"unit\""),
                    generator + " must read assets/pcr/icon/unit — the atlas the human named "
                            + "(「使用角色头像的时候用 素材提取目录」)");
            assertTrue(source.contains("SOURCE_SUFFIX = \".webp\""),
                    generator + " must state the atlas's extension: reading a .png of the same stem is the "
                            + "bug this pins");
            // The docstrings *talk* about the extracted dump — that is where the reason lives, and removing
            // the explanation to satisfy a string search would be the tail wagging the dog. So the check is
            // on the executable text: no module-level path assembly may mention it.
            assertFalse(executableText(source).contains("extracted"),
                    generator + " must not read from the extracted dump at runtime: those files are head "
                            + "crops, not the same picture, and at least one is the wrong girl");
        }
    }

    /**
     * {@return {@code source} with its module docstring and every {@code #} comment removed}
     *
     * <p>Used by assertions about what a generator <em>does</em> rather than what it explains. Without it a
     * file that documents "this replaced the old {@code extracted/图片} source" would fail a check looking for
     * that string, which would push the explanation out of the code — the opposite of what this project
     * wants. Only the leading module docstring is stripped: the generators have no other.
     *
     * @param source the generator's text
     */
    private static String executableText(String source) {
        String withoutDocstring = source.contains("\"\"\"")
                ? source.substring(source.indexOf("\"\"\"") + 3)
                        .substring(source.indexOf("\"\"\"", source.indexOf("\"\"\"") + 3) + 3)
                : source;
        return stripComments(withoutDocstring);
    }

    /**
     * The tierless {@code avatar} is the same person as the 3★ tier icon — by construction, not by luck.
     *
     * <p>{@code CharacterDef.avatar} is the fallback and {@code icon3rd} is the 3★ rung; a character whose
     * {@code avatar} came from a different unit would change her face when her affection crossed a threshold and
     * back again. Asserting the id equality is asserting the promise: one character, one girl, whichever field
     * the surface happens to read.
     */
    @Test
    void theTierlessAvatarIsTheSameGirlAsTheThreeStar() throws IOException {
        Map<String, Map<Integer, String>> tiers = parseTierTable(read(TIER_GENERATOR));
        Map<String, String> tierless = parsePortraitTable(read(PORTRAIT_GENERATOR));

        assertEquals(EXPECTED.keySet(), new LinkedHashSet<>(tierless.keySet()),
                "every character with tier art must also have one tierless avatar: " + PORTRAIT_GENERATOR);
        for (String character : EXPECTED.keySet()) {
            assertEquals(strip(tiers.get(character).get(3)), strip(tierless.get(character)),
                    character + "'s tierless avatar must be her 3★ art — otherwise her face changes when "
                            + "affection crosses a rung and changes back");
        }
    }

    /**
     * <b>Every committed portrait is a real 64x64 RGBA picture whose source exists, and no two characters
     * share one.</b>
     *
     * <h2>What this can and cannot check, and why that is stated rather than papered over</h2>
     * The obvious assertion — "the committed PNG equals a fresh resample of the webp" — <b>cannot run in
     * Java</b>: JDK's {@code ImageIO} has no WebP reader. That was checked, not assumed:
     * {@code ImageIO.getReaderFormatNames()} on this JDK lists
     * {@code JPG, tiff, bmp, gif, WBMP, png} and nothing else, and {@code ImageIO.read} on a real
     * {@code 105711.webp} returns {@code null}. So a pixel comparison here would either throw or, worse, be
     * written to pass when both sides are {@code null}.
     *
     * <p>What <em>is</em> checked here, and what covers the rest:
     * <ol>
     *   <li>Every file the two tables name exists, is 64x64 and decodes as RGBA — a stale or truncated
     *       portrait fails.</li>
     *   <li>Every source the table names exists in the atlas, with the extension the generator reads.</li>
     *   <li>The twelve tier files plus the four tierless ones are <b>sixteen distinct pictures</b>. That is
     *       the property that catches the defect he reported: with the extracted dump, one of the twelve was
     *       a different girl, and a wrong-girl file is either byte-identical to a file it should not match
     *       or, more often, simply not what the table says — which is what
     *       {@code tools/gen_tier_avatars.py --check} and {@code tools/gen_portraits.py --check} prove, since
     *       they run under Python and do have a WebP decoder.</li>
     * </ol>
     *
     * <p>So the division is: <b>Python proves provenance</b> (<code>--check</code> re-derives every byte),
     * <b>this test proves the tree is complete, well-formed and non-degenerate</b>, and
     * {@code gen_tier_avatars.py} being the only writer is what makes the two agree. The pixel comparison
     * that would have proved provenance directly was removed rather than left failing — a test that cannot
     * pass proves nothing and hides the real signal.
     *
     * <p>Skips the source-existence half when the official dump is not on this machine: it is not part of the
     * repository, so a clean checkout has no {@code assets/pcr} at all. The skip is announced by JUnit rather
     * than passed silently.
     */
    @Test
    void everyCommittedPortraitIsACompleteAndDistinctSixtyFourSquare() {
        Map<String, Map<Integer, String>> tiers;
        Map<String, String> tierless;
        try {
            tiers = parseTierTable(read(TIER_GENERATOR));
            tierless = parsePortraitTable(read(PORTRAIT_GENERATOR));
        } catch (IOException failure) {
            throw new AssertionError("the generators must be readable", failure);
        }

        Path sourceDir = Path.of(System.getProperty("astrum.source",
                    Path.of("..", "..", "assets", "pcr", "icon", "unit").toString()));
        boolean sourceAvailable = Files.isDirectory(sourceDir);
        if (!sourceAvailable) {
            System.out.println("[TierAvatarSourceTest] " + sourceDir + " is not on this machine, so the "
                    + "source-existence half is skipped; run tools/gen_*.py --check on a machine with the "
                    + "official dump to re-derive provenance");
        }

        Map<String, String> digestToFile = new LinkedHashMap<>();
        for (String character : EXPECTED.keySet()) {
            for (Map.Entry<Integer, String> tier : tiers.get(character).entrySet()) {
                boolean tierlessTwin = tier.getKey() == 3;
                assertOnePortrait(sourceDir, sourceAvailable, tier.getValue(),
                        portraitPath(character + "_" + tier.getKey() + ".png"),
                        character + " " + tier.getKey() + "★", digestToFile, tierlessTwin);
            }
            // The tierless avatar is *deliberately* her 3★ picture — asserted above by id — so it is the one
            // duplicate the distinctness rule must allow. Every other pair must differ.
            assertOnePortrait(sourceDir, sourceAvailable, tierless.get(character),
                    portraitPath(character + ".png"), character + " (tierless)", digestToFile, true);
        }

        assertEquals(EXPECTED.size() * 4 - EXPECTED.size(), digestToFile.size(),
                "sixteen files, twelve distinct pictures: the four tierless avatars are their characters' "
                        + "3★ art by design, and nothing else may share. Duplicates found: " + digestToFile);
    }

    /**
     * Asserts one committed portrait is well formed, has a source, and records its digest for the
     * distinctness check.
     *
     * @param sourceDir       the official icon atlas directory
     * @param sourceAvailable whether that directory exists here at all
     * @param artId           the art id the table names
     * @param committed       the committed PNG
     * @param what            a label for failure messages, e.g. {@code djeeta 1★}
     * @param digestToFile    accumulates {@code sha256 -> description} across the test
     * @param mayDuplicate    whether this file is expected to equal another (the tierless avatar and its 3★)
     */
    private static void assertOnePortrait(Path sourceDir, boolean sourceAvailable, String artId,
            Path committed, String what, Map<String, String> digestToFile, boolean mayDuplicate) {
        String stem = strip(artId);
        if (sourceAvailable) {
            Path source = sourceDir.resolve(stem + ".webp");
            assertTrue(Files.isRegularFile(source),
                    what + " names " + source + ", which does not exist in the atlas");
        }

        assertTrue(Files.isRegularFile(committed),
                "missing committed portrait " + committed + " — run tools/gen_tier_avatars.py and "
                        + "tools/gen_portraits.py");
        try {
            java.awt.image.BufferedImage image = ImageIO.read(committed.toFile());
            assertNotNull(image, what + ": " + committed + " is not a readable PNG");
            assertEquals(TIER_AVATAR_EDGE, image.getWidth(), what + " width");
            assertEquals(TIER_AVATAR_EDGE, image.getHeight(), what + " height");

            java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(pngPixels(image));
            String key = java.util.HexFormat.of().formatHex(digest);
            String previous = digestToFile.putIfAbsent(key, what + " <- " + stem);
            if (mayDuplicate) {
                return;
            }
            assertTrue(previous == null,
                    what + " and " + previous + " are the same picture; every portrait must be its own "
                            + "character's art");
        } catch (IOException | java.security.NoSuchAlgorithmException failure) {
            throw new AssertionError("could not check " + committed, failure);
        }
    }

    /** {@return every pixel of {@code image} as RGBA bytes, so a digest compares pictures not encodings} */
    private static byte[] pngPixels(java.awt.image.BufferedImage image) {
        byte[] out = new byte[image.getWidth() * image.getHeight() * 4];
        int at = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                out[at++] = (byte) (argb >>> 24);
                out[at++] = (byte) (argb >>> 16);
                out[at++] = (byte) (argb >>> 8);
                out[at++] = (byte) argb;
            }
        }
        return out;
    }

    /**
     * {@return the committed portrait at {@code name}, resolved against the project root}
     *
     * <p>The portrait tree is only reachable from the repository, not from the test classpath: those files
     * are gitignored derivatives that the generators write into {@code src/main/resources}, and the
     * classpath copy under {@code build/resources} is whatever the last {@code processResources} produced.
     * Reading the project path is what makes this an assertion about what the generators wrote.
     *
     * @param name e.g. {@code djeeta_1.png}
     */
    private static Path portraitPath(String name) {
        Path path = TestProjectRoot.resolve(
                "src/main/resources/assets/astrum/textures/gui/portrait/" + name);
        assertNotNull(path, "the project root could not be located, so the portrait tree cannot be read");
        return path;
    }


    /** The committed edge length, mirroring the generators' {@code SIZE}. */
    private static final int TIER_AVATAR_EDGE = 64;

    /**
     * {@return {@code character -> {star -> artId}} parsed out of {@code TIER_AVATARS}}
     *
     * <p>A hand-rolled reader rather than a Python interpreter: the dict's shape is fixed and a test that
     * depends on running Python could not run in this build at all. An unparseable file is a failure, not an
     * empty map — a test that quietly finds nothing is worse than no test.
     *
     * @param source the generator's text
     */
    private static Map<String, Map<Integer, String>> parseTierTable(String source) {
        Matcher block = Pattern.compile("TIER_AVATARS\\s*=\\s*\\{(.*?)\\n\\}", Pattern.DOTALL)
                .matcher(source);
        assertTrue(block.find(), "TIER_AVATARS not found in " + TIER_GENERATOR);
        String body = stripComments(block.group(1));

        Map<String, Map<Integer, String>> table = new LinkedHashMap<>();
        Matcher row = Pattern.compile("\"(\\w+)\"\\s*:\\s*\\{([^}]*)\\}").matcher(body);
        while (row.find()) {
            Map<Integer, String> tiers = new LinkedHashMap<>();
            Matcher tier = Pattern.compile("([136])\\s*:\\s*\"([^\"]+)\"").matcher(row.group(2));
            while (tier.find()) {
                tiers.put(Integer.parseInt(tier.group(1)), tier.group(2));
            }
            assertFalse(tiers.isEmpty(), "row " + row.group(1) + " names no tier");
            table.put(row.group(1), tiers);
        }
        assertFalse(table.isEmpty(), "TIER_AVATARS parsed to nothing — the parser and the file disagree");
        return table;
    }

    /**
     * {@return {@code character -> artId} parsed out of {@code AVATARS}}
     *
     * @param source the generator's text
     */
    private static Map<String, String> parsePortraitTable(String source) {
        Matcher block = Pattern.compile("AVATARS\\s*=\\s*\\{(.*?)\\n\\}", Pattern.DOTALL).matcher(source);
        assertTrue(block.find(), "AVATARS not found in " + PORTRAIT_GENERATOR);
        String body = stripComments(block.group(1));

        Map<String, String> table = new LinkedHashMap<>();
        Matcher row = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        while (row.find()) {
            table.put(row.group(1), row.group(2));
        }
        assertFalse(table.isEmpty(), "AVATARS parsed to nothing — the parser and the file disagree");
        return table;
    }

    /** {@return {@code text} with every {@code #} comment removed, so a commented-out row cannot parse} */
    private static String stripComments(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n")) {
            int hash = line.indexOf('#');
            out.append(hash >= 0 ? line.substring(0, hash) : line).append('\n');
        }
        return out.toString();
    }

    /** {@return the bare art id: {@code icon_unit_105711} and {@code 105711} both answer {@code 105711}} */
    private static String strip(String artId) {
        String bare = artId.trim();
        return bare.startsWith("icon_unit_") ? bare.substring("icon_unit_".length()) : bare;
    }

    /**
     * {@return the text of one file, as UTF-8}, resolved against the located project root
     *
     * <p>Through {@link TestProjectRoot} rather than a relative path: the unit-test JVM's working directory
     * is Gradle's scratch directory ({@code build/minecraft-junit}), so a relative path finds nothing and
     * this test would pass while asserting nothing. {@code null} from the resolver is a failure here — an
     * inability to find the checkout means the assertion has no subject.
     */
    private static String read(String relative) throws IOException {
        Path path = TestProjectRoot.resolve(relative);
        assertNotNull(path, "the project root could not be located, so " + relative
                + " cannot be read — that is an environment failure, not a passing test");
        assertTrue(Files.isRegularFile(path),
                path.toAbsolutePath() + " must exist, otherwise this test proves nothing");
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /** {@return an insertion-ordered map of the pairs given as {@code key, value, key, value, …}} */
    private static <V> Map<String, V> ordered(Object... pairs) {
        Map<String, V> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            @SuppressWarnings("unchecked")
            V value = (V) pairs[i + 1];
            map.put((String) pairs[i], value);
        }
        return map;
    }
}




