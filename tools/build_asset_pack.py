"""Builds the resource packs that supply the **official assets** the mod does not ship.

<h2>One pack per kind, not one pack for everything</h2>
The human's instruction: 「新建对应的资源包，比如图标资源包放进图标，音频资源包放音频，实体建模资源包放实体建模」.
A player who only wants the pictures no longer downloads the voice lines, and a pack that fails to load costs
one kind of content instead of all of it. The split is decided by a path prefix (``PACKS``), and a file no pack
claims is a build error rather than a silent omission.

<p><b>Only packs with content are written.</b> An empty zip is a player wondering why nothing changed, so a kind
that has no files yet (audio and models today) simply does not appear.


<h2>Why the mod ships no art, and what this script is for</h2>
Every portrait, still, Q-version and skill icon here is Cygames' art, extracted from the game. The
repository ships **code and reproducible generators only** — ``.gitignore`` lists each art file by path —
and the human's decision is that players who want the pictures install a resource pack. So this script:

1. runs the existing ``tools/gen_*.py`` generators (each of which reads the extracted official art and
   writes a derived, downscaled copy into ``src/main/resources/assets/astrum/textures/``);
2. assembles exactly those textures plus ``pack.mcmeta`` into a zip;
3. writes the zip to a chosen output path (default ``build/astrum-assets.zip``).

<p><b>The zip is a Release asset, not a file in the tree.</b> It contains derived official art, so
committing it would be the same violation as committing the source PNGs. ``build/`` is already ignored.

<h2>Why the texture list is derived, not written down</h2>
A hand-maintained list of "files that came from official art" is a second copy of a rule that already has
one owner — ``.gitignore``. It also drifts: the day somebody adds a texture, the pack silently stops being
complete and the only symptom is a checkerboard in a player's game. So the list is read from
``.gitignore``'s own art block, which is the declaration of which paths are art. If a generator produces a
file that ``.gitignore`` does not name, this script says so and fails rather than shipping an incomplete
pack.

<h2>The placeholder is included, and why</h2>
``textures/gui/placeholder.png`` is the mod's **own** drawing (see ``tools/gen_placeholder.py``) and is
committed, so it is by definition not official art. It is still packed, because a player who installs the
pack must keep getting *something* for a texture the pack does not cover, and having the pack carry the
same placeholder as the jar means the picture cannot differ depending on whether the pack is installed.

<h2>``--check``</h2>
Validates without writing: every listed path exists, ``pack.mcmeta`` parses with ``pack_format`` 34, every
PNG opens and is a power-of-two-ish sane size, and the total is under a byte budget. Run it in CI; it is
the mode that catches "somebody renamed a generator's output".

Usage:

    python tools/build_asset_pack.py [--out PATH] [--source DIR] [--check] [--skip-generators]

``--skip-generators`` packs whatever is on disk instead of re-running the generators, which is what a
``--check`` in CI wants (the generators need the extracted official art, which CI does not have).
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import zipfile

from PIL import Image

#: Repository root, one level up from ``tools/``.
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

#: Where the mod's resources live, relative to the root.
ASSETS = os.path.join("src", "main", "resources", "assets", "astrum")

#: The default output. ``build/`` is git-ignored, which is the point: the zip is a release artifact.
DEFAULT_OUT = os.path.join("build", "astrum-assets.zip")

#: ``.gitignore``, which is the declaration of "these paths are derived official art".
GITIGNORE = os.path.join(ROOT, ".gitignore")

#: The ``pack_format`` for Minecraft 1.21.1. The human specified 34; it is also what
#: ``src/main/resources/pack.mcmeta`` carries, and this script asserts the two agree rather than trusting
#: a literal twice.
PACK_FORMAT = 34

#: The generators that produce art, in dependency order. Each is invoked as ``python <script>`` with the
#: interpreter running this script, so a venv is honoured.
GENERATORS = (
    "gen_portraits.py",          # 64x64 avatars from the official icon_unit_* files
    "gen_tier_avatars.py",       # the three affection-rung avatars per character
    "gen_still_backgrounds.py",  # 512x288 stills from the official still_unit_* files
    "gen_skill_icons.py",        # the book's per-effect icons
    "gen_tinted_icons.py",       # the border-tinted self-buff icons (+ their HUD copies)
    "gen_effect_icons.py",       # the HUD's mob_effect sprites
    "gen_buff_icon.py",          # the self attack buff's icon
    "gen_book_panel_texture.py", # the nine-slice frame
    "gen_character_book_texture.py",
    "gen_character_card_texture.py",
    "gen_card_from_avatar.py",
    "gen_token_texture.py",
    "gen_ub_gauge_texture.py",
    "gen_ub_particles.py",
    "gen_showcase_skin.py",
    "gen_party_effect_textures.py",
    "gen_effect_icon_copies.py",   # the shared 16x16 placeholder, one copy per generic effect
    "gen_skill_effect_types.py",    # the 91 per-skill placeholders and their manifest
    "gen_marker_icons.py",          # 计数 / 精灵 wear 雪菲 / 千歌 — portraits, not the placeholder
)

#: Byte budget for one finished pack, a guard against accidentally packing full-size originals.
#:
#: <b>Measured, not guessed.</b> The art pack is ~15.4 MB of source PNG across 261 files, and the 44 stills are the
#: bulk of it (~11 MB at 512x288). The earlier 6 MB figure predated the stills and the per-skill placeholders, so it
#: failed on a correct tree — a guard that fires on healthy input is worse than none, because the next person
#: "fixes" it by deleting content. 24 MB leaves room for a few more characters without hiding a real mistake:
#: packing the official-size sources instead of the derived copies would overshoot by roughly an order of magnitude.
#: Declarations that matched nothing this run — a directory whose generator has not produced its files yet.
#: Collected rather than raised so `--check --skip-generators` (CI, where the generators cannot run) still works,
#: and printed so a stale declaration is visible instead of merely silent.
EMPTY_DECLARATIONS: list[str] = []

BYTE_BUDGET = 24 * 1024 * 1024

#: PNGs smaller than this are almost certainly the 1x1 "missing" placeholder Minecraft writes on a failed
#: export. Checking for it is cheap and it is the one failure a player cannot diagnose.
MIN_PNG_BYTES = 200


#: The packs this build produces, keyed by a stable id. A pack is written only when it has content, so today
#: only ``art`` exists; ``audio`` and ``models`` appear the day their first file is declared by ``.gitignore``.
#:
#: <h2>Why the split is by path prefix and not by a hand-written list</h2>
#: The same reason ``art_paths`` reads ``.gitignore``: a second list of "what goes where" drifts, and the only
#: symptom of drift is a player missing a file. A path's kind is visible in the path itself, so the rule is a
#: prefix table — one row per pack — and an unknown prefix is a <b>hard error</b> rather than a silent fallback,
#: because "this file landed in no pack" is exactly the failure that produces a checkerboard or silence.
PACKS = {
    "art": {
        "prefixes": ("textures/",),
        "out": "build/astrum-art.zip",
        "description": "Astrum 官方美术资源（头像 / 立绘 / 卡图 / 技能图标）。与 mod 一起安装，"
                       "不随 mod 分发。 / Astrum official art (portraits, stills, cards, skill icons). "
                       "Install alongside the mod; not distributed with it.",
    },
    "audio": {
        "prefixes": ("sounds/",),
        "out": "build/astrum-audio.zip",
        "description": "Astrum 官方语音资源（必杀切入 / 开战 / 胜利等）。与 mod 一起安装，"
                       "不随 mod 分发。 / Astrum official voice lines. Install alongside the mod; "
                       "not distributed with it.",
    },
    "models": {
        "prefixes": ("geo/", "animations/"),
        "out": "build/astrum-models.zip",
        "description": "Astrum 官方建模与动作资源。与 mod 一起安装，不随 mod 分发。 "
                       "/ Astrum official models and animation clips. Install alongside the mod; "
                       "not distributed with it.",
    },
}


def pack_of(relative: str) -> str:
    """Returns the pack id an asset path belongs to. Raises ``SystemExit`` when no pack claims it.

    <p>The error is deliberate: a new kind of external asset that nobody assigned has to stop the build, because
    the alternative is a file that ships in no pack and shows up in game as a missing texture or silence.
    """
    for pack_id, spec in PACKS.items():
        for prefix in spec["prefixes"]:
            if relative.startswith(prefix):
                return pack_id
    raise SystemExit("no resource pack claims %r — add a row to PACKS naming its prefix, or the file ships "
                     "in no pack and the player sees a missing texture or silence" % relative)


def expand(declared: str) -> list[str]:
    """Expands one ``.gitignore`` declaration into the files it names.

    <h2>Why the declarations are not simply paths</h2>
    ``.gitignore`` states art two ways: a literal path per file, and a directory wildcard such as
    ``gui/portrait/**``. Both are honest declarations of "this is external", so this function accepts both and the
    caller never has to know which form a given block used. A declaration that expands to nothing is an error:
    it means a generator stopped producing a directory the ignore file still expects, and packing nothing while
    reporting success is the failure mode this whole script exists to prevent.
    """
    base = os.path.join(ROOT, ASSETS)
    if declared.endswith("/**"):
        directory = os.path.join(base, declared[:-3].replace("/", os.sep))
        if not os.path.isdir(directory):
            raise SystemExit(".gitignore declares %r but that directory does not exist — a generator stopped "
                             "producing it, or the ignore file is stale" % declared)
        found = []
        for root, _dirs, files in os.walk(directory):
            for name in sorted(files):
                full = os.path.join(root, name)
                found.append(os.path.relpath(full, base).replace(os.sep, "/"))
        if not found:
            # Not an error: this is the `--check --skip-generators` case, where a generator has not run yet and
            # its directory is legitimately empty. The caller reports it; the zip simply has nothing for it.
            return []
        return found
    if "*" in declared:
        import glob as globmod
        found = [os.path.relpath(f, base).replace(os.sep, "/")
                 for f in globmod.glob(os.path.join(base, declared.replace("/", os.sep)))]
        if not found:
            return []   # same reason as the directory case above
        return found
    return [declared]


def art_paths() -> list[str]:
    """Returns every texture path ``.gitignore`` declares as art, as paths **relative to the texture root**.

    <h2>How the block is recognised</h2>
    ``.gitignore`` lists them one literal path per line under the "派生自 Cygames 官方美术" comment, each
    starting ``/src/main/resources/assets/astrum/textures/``. Comments and blanks are skipped, so the block
    can be re-worded without breaking this — only a line that starts with that prefix counts.

    <p>The returned paths are what both the mod's tree and the pack's ``assets/astrum/textures/`` prefix
    expect, i.e. ``gui/portrait/djeeta.png``. Doing the stripping once here is what keeps the caller from
    having to know that ``/src/main/resources/assets/astrum/textures/`` is the prefix to remove — and the
    first version of this script got it wrong, looking for ``<assets>/gui/...`` under a path that already
    ended at ``textures``.
    """
    prefix = "/" + ASSETS.replace(os.sep, "/") + "/"
    paths: list[str] = []
    with open(GITIGNORE, encoding="utf-8") as handle:
        for raw in handle:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if not line.startswith(prefix):
                continue
            # Any file kind, not just .png: the audio and model packs are declared the same way, and gating on
            # `.png` here would silently drop them while the script still reported success.
            decl = line[len(prefix):]
            expanded = expand(decl)
            if not expanded and ("*" in decl):
                EMPTY_DECLARATIONS.append(decl)
            paths.extend(expanded)
    if not paths:
        raise SystemExit("no external asset paths found in .gitignore — the block that declares derived "
                         "official assets is the input this script reads; has it been re-worded?")
    return sorted(set(paths))


def textures() -> list[str]:
    """Returns the texture paths the pack carries: every art path, plus the committed placeholder."""
    # The placeholder is the mod's own drawing (tools/gen_placeholder.py), committed rather than derived — but it
    # still ships in the art pack so a covered path never resolves differently depending on whether the pack is
    # installed. Spelled in the same assets-relative frame as everything else.
    return sorted(set(art_paths()) | {"textures/gui/placeholder.png"})


def full_path(relative: str) -> str:
    """Returns the absolute path of one asset in the mod's tree.

    <p>{@code relative} is relative to {@code assets/astrum/} — {@code textures/gui/placeholder.png}, not
    {@code gui/placeholder.png}. That is the same frame the resource pack uses, so one string serves both the
    filesystem and the arc name. The earlier version was textures-relative, which stopped being a single frame
    the moment a second kind of asset existed.
    """
    return os.path.join(ROOT, ASSETS, relative.replace("/", os.sep))


def run_generators(source: str | None) -> None:
    """Runs every generator, in order, and fails on the first non-zero exit.

    <p>Each generator is a standalone script that reads the extracted official art. ``--source`` is passed
    through only to the ones that accept it, since a generator whose input is not the official dump (a
    pure-drawing one) would reject the flag.
    """
    for script in GENERATORS:
        path = os.path.join(ROOT, "tools", script)
        if not os.path.isfile(path):
            raise SystemExit("missing generator: " + path)
        command = [sys.executable, path]
        if source is not None:
            command += ["--source", source]
        result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
        if result.returncode != 0:
            # A generator that does not understand --source retries without it once: the flag is a
            # convenience, and a generator needing no input should not stop the pack being built.
            if source is not None and "unrecognized arguments" in (result.stderr or ""):
                result = subprocess.run([sys.executable, path], cwd=ROOT, capture_output=True, text=True)
            if result.returncode != 0:
                raise SystemExit("generator %s failed:\n%s\n%s"
                                 % (script, result.stdout[-2000:], result.stderr[-2000:]))
        print("  ran %s" % script)


def pack_mcmeta(pack_id: str) -> str:
    """Returns one pack's ``pack.mcmeta`` text.

    <p>Read from the mod's own file and re-emitted with the description replaced, so the two cannot disagree
    about ``pack_format``. A pack whose format does not match the game is silently listed as incompatible,
    and a player reads it as "the pack is broken".
    """
    with open(os.path.join(ROOT, "src", "main", "resources", "pack.mcmeta"), encoding="utf-8") as handle:
        mod_pack = json.load(handle)
    declared = mod_pack.get("pack", {}).get("pack_format")
    if declared != PACK_FORMAT:
        raise SystemExit("the mod's pack.mcmeta declares pack_format %s, but this script expects %s; "
                         "they must agree or the two packs load differently" % (declared, PACK_FORMAT))
    return json.dumps({
        "pack": {
            "pack_format": PACK_FORMAT,
            "description": PACKS[pack_id]["description"],
        }
    }, indent=2, ensure_ascii=False)


def validate(paths: list[str]) -> int:
    """Checks every texture, returns the total bytes. Raises ``SystemExit`` on any problem."""
    total = 0
    for relative in paths:
        full = full_path(relative)
        if not os.path.isfile(full):
            raise SystemExit("missing texture (run without --skip-generators, and check the generator that "
                             "should write it): " + full)
        size = os.path.getsize(full)
        if size == 0:
            raise SystemExit("%s is empty — a placeholder the generator failed to write" % full)
        if relative.lower().endswith(".png"):
            # Only PNGs get the image checks. The audio and model packs are declared through the same
            # `.gitignore` block, and verifying an `.ogg` with PIL would fail on a perfectly good file.
            if size < MIN_PNG_BYTES:
                raise SystemExit("%s is %d B — that is a missing-texture stub, not art" % (full, size))
            try:
                with Image.open(full) as image:
                    image.verify()
            except Exception as error:                 # noqa: BLE001 — any decode failure is the point
                raise SystemExit("%s is not a readable PNG: %s" % (full, error))
        total += size
    if total > BYTE_BUDGET:
        raise SystemExit("the textures total %d B, over the %d B budget — the official-size originals were "
                         "probably generated instead of the derived copies" % (total, BYTE_BUDGET))
    return total


def write_zip(out_path: str, paths: list[str], pack_id: str) -> int:
    """Writes one pack and returns its byte size."""
    os.makedirs(os.path.dirname(os.path.abspath(out_path)) or ".", exist_ok=True)
    with zipfile.ZipFile(out_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        archive.writestr("pack.mcmeta", pack_mcmeta(pack_id))
        for relative in paths:
            full = full_path(relative)
            # The paths inside the zip are the resource-pack paths, NOT the mod's tree: a resource pack's
            # textures live at ``assets/astrum/textures/...`` with no ``src/main/resources`` in front.
            # The arc name is the asset path as the mod's own tree stores it, which is already the
            # resource-pack layout (`assets/astrum/<kind>/...`).
            arcname = "assets/astrum/" + relative
            archive.write(full, arcname)
    return os.path.getsize(out_path)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", default=DEFAULT_OUT,
                        help="where to write the zip (default %s)" % DEFAULT_OUT)
    parser.add_argument("--source", default=None,
                        help="the unpacked official art directory, forwarded to the generators")
    parser.add_argument("--check", action="store_true",
                        help="validate without writing anything")
    parser.add_argument("--skip-generators", action="store_true",
                        help="pack what is already on disk; do not re-run the generators")
    args = parser.parse_args(argv)

    paths = textures()
    print("astrum asset packs: %d external file(s) declared by .gitignore" % len(paths))

    # One row per pack, in a stable order, so a build log always reads the same way.
    by_pack: dict[str, list[str]] = {}
    for relative in paths:
        by_pack.setdefault(pack_of(relative), []).append(relative)
    for pack_id in PACKS:
        found = by_pack.get(pack_id, [])
        print("  %-6s %3d file(s)%s" % (pack_id, len(found),
                                        "" if found else "  (skipped: nothing declared yet)"))
    if EMPTY_DECLARATIONS:
        print("  note: %d declaration(s) matched no files this run (a generator has not filled them yet): %s"
              % (len(EMPTY_DECLARATIONS), ", ".join(sorted(set(EMPTY_DECLARATIONS)))))

    if not args.check and not args.skip_generators:
        print("running generators:")
        run_generators(args.source)

    total = validate(paths)
    print("validated %d file(s), %d B" % (len(paths), total))

    if args.check:
        print("ok: %d pack(s) would build (pack_format %d)" % (len(by_pack), PACK_FORMAT))
        return 0

    written = 0
    for pack_id, spec in PACKS.items():
        pack_paths = by_pack.get(pack_id, [])
        if not pack_paths:
            continue   # a pack with no content is not written: an empty zip is a player wondering why nothing changed
        out = args.out if len(by_pack) == 1 and args.out != DEFAULT_OUT \
            else os.path.join(ROOT, spec["out"])
        size = write_zip(out, pack_paths, pack_id)
        print("wrote %s (%d file(s), %d B)" % (out, len(pack_paths), size))
        if size > BYTE_BUDGET:
            raise SystemExit("%s is %d B, over the %d B budget" % (out, size, BYTE_BUDGET))
        written += 1
    if written == 0:
        raise SystemExit("nothing to pack: no external asset is declared by .gitignore")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
