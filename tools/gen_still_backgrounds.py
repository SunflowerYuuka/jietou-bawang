"""Derives the <b>downscaled, per-tier character stills</b> the 角色册 draws behind its panel.

<h2>Why a derived file and not the official one</h2>
The human's rule: 「点击头像后，显示完全的角色立绘当背景」 and 「完全立绘使用still_unit_编号」 — the panel's dark body
becomes the selected character's <b>full still</b>. The official stills are 1408x792 to 2816x1584 and
<b>0.5–6 MB each</b>; committing them as-is would put megabytes per character in the jar to draw a ~400x293
panel, and the panel's own size is fixed, so most of those pixels are never sampled. So this script writes one
downscaled copy per shipped still and the jar ships only that.

<h2>Why there are two per character</h2>
「背景立绘要随着好感的升阶更换背景立绘」 — the background follows the affection tier, the way the avatar already
does. The dump gives a 3★ full-body still and a 6★ one, and <b>no 1★</b>, so ``CharacterDef.still(int)``
degrades downwards: tier 1 and tier 2 draw the 3★ art, tier 3 draws the 6★ art. That is why this script
writes ``djeeta_3.png`` and ``djeeta_6.png`` and nothing at the 1★ rung — inventing a 1★ file would mean
showing a picture the official set does not have.

<h2>The change this task made: the source moved to the bwiki folders, and lookup is by FOLDER</h2>
The dump used for the 3★/6★ stills is now ``assets/bwiki_按角色/<id>_<name>/``, which carries
``立绘\\`` (the 3★ still, named ``<id>_31_<cn name>.jpg``) and ``六星立绘\\`` (the 6★ still). Two facts about
that tree forced this script's shape:

<ol>
  <li><b>The 6★ filenames are the character's REAL name, not her game name.</b> The human's note is exact:
      姬塔 → ``ジータ6星.png``, 可可萝 → ``枣可萝6星.png``, 佩可莉姆 → ``尤丝蒂亚娜・冯・阿斯特莱亚6星.png``,
      凯露 → ``百地希留耶6星.png``. So a name-based lookup built from our own character id or her display name
      <b>fails</b> — ``姬塔6星.png`` does not exist for 姬塔 under the name we would guess, and one folder
      (姬塔's) even holds <em>two</em> 6★ files, only one of which is the official still.</li>
  <li><b>So the lookup is by folder, and inside a folder it is by a rule, not a literal.</b> The 6★ rung is
      resolved as: ``<日文名>6星.png`` from ``角色数据.json`` if present (which picks
      ``ジータ6星.png`` for 姬塔 and nothing else), otherwise the folder's <b>single</b> ``*6星.png``. The 3★
      rung is the folder's ``立绘/*_31_*.jpg``, which is the one 2816x1584 picture there. Nothing is
      hard-coded by artist name, so a re-export with a different romanisation still works, and an ambiguous
      folder is a named error rather than a silent pick.</li>
</ol>

<h2>Why 512x288, and why no crop</h2>
The committed size is 16:9 at 512x288 — 1/16 of a 1408x792 original's pixel count. The panel it fills is
~400x293 GUI pixels, and a cover-fit of a 16:9 source into a 1.37:1 box samples ``288 * 1.37 = 393`` source
columns across those 400, so 512 is the smallest width that still <em>oversamples</em> the panel at GUI scale 1
and stays honest at scale 2 without a megabyte in the jar.

<p>The aspect is deliberately kept: the panel crops the <em>source</em> rectangle to the panel's own aspect at
draw time (``StillCover``, tested), which is what makes "cover" mean cover. Baking a crop in here would pick
one panel size forever and squash the picture on the other layout tier. Sources that are not exactly 16:9 are
cover-cropped to 16:9 here (centred) rather than squashed, because squashing a person is visible on a face.

Run:

    python tools/gen_still_backgrounds.py [--source DIR] [--check]

``--check`` re-reads the committed files and fails when one is not 512x288, is over the size budget, or does
not match a fresh re-derivation from the bwiki tree — so a re-export cannot quietly put a megabyte back into
the jar, and a hand-edited PNG is caught.
"""

from __future__ import annotations

import argparse
import json
import os
import sys

from PIL import Image

def _dump_root() -> str:
    """素材提取目录的根。用 ASTRUM_DUMP 指向你自己机器上的位置，默认取仓库上两级的 ``assets``。

    这里原来写的是一个绝对路径（维护者本机的盘符），既泄露了本机布局，也让脚本在别人的机器上必然失效。
    """
    return os.environ.get("ASTRUM_DUMP", os.path.join("..", ".."))


#: The bwiki per-character tree. Each character is a folder ``<id>_<name>`` holding ``角色数据.json``,
#: ``立绘\\`` (the 3★ still) and ``六星立绘\\`` (the 6★ still).
DEFAULT_SOURCE = os.path.join(_dump_root(), "assets", "bwiki_按角色")

#: Character id (the datapack file name) -> the folder under :data:`DEFAULT_SOURCE`.
#:
#: The keys are our ids and the values are the dump's folders. 1058 is ペコリーヌ and 1059 is コッコロ —
#: verified against the name plates and recorded in ``ThreeCharactersResourceTest``, because the task brief
#: had two of them backwards.
FOLDERS = {
    "djeeta": "1057_姬塔",
    "pecorine": "1058_佩可莉姆",
    "kokkoro": "1059_可可萝",
    "kyaru": "1060_凯露",
}

#: The suffix every 6★ still carries in the dump. Matched, never guessed from a name.
SIX_STAR_SUFFIX = "6星.png"

#: The 3★ still's directory inside a folder, and the infix its filename carries.
THREE_STAR_DIR = "立绘"
THREE_STAR_MARKER = "_31_"

#: The 6★ still's directory inside a folder.
SIX_STAR_DIR = "六星立绘"

#: Committed size, in pixels. See the module docstring for the arithmetic.
WIDTH = 512
HEIGHT = 288

#: Byte budget per committed still. 512x288 of a busy illustration is ~240-280 kB; 320 kB is the bound that
#: would have caught "somebody committed the multi-megabyte original".
BYTE_BUDGET = 320 * 1024

#: Destination root, relative to the repository root.
OUTPUT_DIR = os.path.join("src", "main", "resources", "assets", "astrum", "textures", "gui", "still")


def out_name(character: str, rung: str) -> str:
    """Returns ``djeeta_3.png``-style file name for one character's still at one rarity rung."""
    return f"{character}_{rung}.png"


def declared_name(folder: str) -> str:
    """Returns the character's Japanese name from ``角色数据.json``, or ``""`` when it cannot be read.

    <p>This is the key the 6★ lookup prefers, because the human's note says those files are named by the
    character's <b>real</b> name: 姬塔's still is ``ジータ6星.png``, i.e. her 日文名, not ``姬塔``. Reading it from
    the data rather than repeating it here is what keeps this script honest if a name is ever corrected.
    """
    path = os.path.join(folder, "角色数据.json")
    if not os.path.isfile(path):
        return ""
    try:
        with open(path, encoding="utf-8") as handle:
            return (json.load(handle).get("日文名") or "").strip()
    except (json.JSONDecodeError, OSError):
        return ""


def three_star_source(folder: str) -> str:
    """Returns the 3★ still's path inside ``folder``.

    <p>The dump names it ``<id>_31_<cn name>.jpg`` — one file, 2816x1584. Matched by the ``_31_`` infix rather
    than by any name, because the name after the infix is the Chinese one and that is exactly the kind of
    literal that breaks on a re-export.
    """
    art_dir = os.path.join(folder, THREE_STAR_DIR)
    if not os.path.isdir(art_dir):
        raise SystemExit(f"no {THREE_STAR_DIR} directory in {folder}")
    candidates = [name for name in sorted(os.listdir(art_dir)) if THREE_STAR_MARKER in name]
    if len(candidates) != 1:
        raise SystemExit(f"expected exactly one {THREE_STAR_DIR}/*{THREE_STAR_MARKER}* still in {folder}, "
                         f"found {candidates}")
    return os.path.join(art_dir, candidates[0])


def six_star_source(folder: str) -> str:
    """Returns the 6★ still's path inside ``folder``.

    <h2>The rule, and the folder that forced it</h2>
    <ol>
      <li>``<日文名>6星.png`` when that file exists — 姬塔's ``ジータ6星.png``;</li>
      <li>otherwise the folder's <b>single</b> ``*6星.png`` — 可可萝's ``枣可萝6星.png``,
          佩可莉姆's ``尤丝蒂亚娜・冯・阿斯特莱亚6星.png`` and 凯露's ``百地希留耶6星.png``, all of which are
          the character's real name in Chinese and none of which any name of ours would produce;</li>
      <li>otherwise a <b>named error</b> listing what was found. 姬塔's folder holds two 6★ files
          (``ジータ6星.png``, the official 1408x792 still, and ``姬塔6星.png``, a 1920x1080 RGBA picture), so
          "pick any" would silently ship the wrong one — which is precisely why rule 1 exists rather than
          "take the only file".</li>
    </ol>
    """
    art_dir = os.path.join(folder, SIX_STAR_DIR)
    if not os.path.isdir(art_dir):
        raise SystemExit(f"no {SIX_STAR_DIR} directory in {folder}")
    available = [name for name in sorted(os.listdir(art_dir)) if name.endswith(SIX_STAR_SUFFIX)]
    if not available:
        raise SystemExit(f"no *{SIX_STAR_SUFFIX} in {art_dir}")
    real_name = declared_name(folder)
    if real_name:
        preferred = real_name + SIX_STAR_SUFFIX
        if preferred in available:
            return os.path.join(art_dir, preferred)
    if len(available) == 1:
        return os.path.join(art_dir, available[0])
    raise SystemExit(f"{art_dir} holds {len(available)} candidate 6★ stills {available} and none is named "
                     f"{real_name + SIX_STAR_SUFFIX if real_name else '<日文名>6星.png'}; refusing to guess")


def cover_crop_to_sixteen_by_nine(image: Image.Image) -> Image.Image:
    """Returns ``image`` centre-cropped to 16:9, so a resize to 512x288 cannot squash a face.

    <p>Every still in the dump is already 16:9 to within a pixel, so on the shipped data this is a no-op. It
    exists so a re-export at another aspect is <em>cropped</em> rather than stretched — a 4:3 source squashed
    into 16:9 would visibly narrow everybody, and the panel would then be showing a distorted likeness.
    """
    width, height = image.size
    wanted = 16.0 / 9.0
    actual = width / float(height)
    if abs(actual - wanted) < 0.01:
        return image
    if actual > wanted:
        new_width = int(round(height * wanted))
        left = (width - new_width) // 2
        return image.crop((left, 0, left + new_width, height))
    new_height = int(round(width / wanted))
    top = (height - new_height) // 2
    return image.crop((0, top, width, top + new_height))


def resample(source: str) -> Image.Image:
    """Loads one official still and returns the committed 512x288 copy."""
    with Image.open(source) as image:
        still = cover_crop_to_sixteen_by_nine(image.convert("RGB"))
    resized = still.resize((WIDTH, HEIGHT), Image.LANCZOS)
    # A still is a photograph of a character on her own background: every pixel is opaque. The 3★ files are
    # JPEGs (already RGB) and the 6★ ones PNG. Keeping a constant alpha channel would be 512*288 bytes of 255
    # that PNG still has to filter and deflate, so an image whose alpha is uniform is flattened.
    return resized


def sources(source_root: str) -> dict[str, dict[str, str]]:
    """Returns ``character -> {rung -> source path}``, resolved by folder."""
    resolved: dict[str, dict[str, str]] = {}
    for character, folder_name in FOLDERS.items():
        folder = os.path.join(source_root, folder_name)
        if not os.path.isdir(folder):
            raise SystemExit("character folder not found: " + folder + " (pass --source DIR)")
        resolved[character] = {"3": three_star_source(folder), "6": six_star_source(folder)}
    return resolved


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", default=DEFAULT_SOURCE, help="the bwiki_按角色 tree")
    parser.add_argument("--check", action="store_true",
                        help="verify the committed stills instead of writing them")
    args = parser.parse_args(argv)

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out_dir = os.path.join(root, OUTPUT_DIR)
    wanted = sources(args.source)

    if args.check:
        total = 0
        count = 0
        for character, rungs in wanted.items():
            for rung, source in rungs.items():
                path = os.path.join(out_dir, out_name(character, rung))
                if not os.path.isfile(path):
                    raise SystemExit("missing committed still: " + path)
                with Image.open(path) as image:
                    committed = image.convert("RGBA")
                if committed.size != (WIDTH, HEIGHT):
                    raise SystemExit(f"{path} is {committed.size}, expected {WIDTH}x{HEIGHT}")
                size = os.path.getsize(path)
                if size > BYTE_BUDGET:
                    raise SystemExit(f"{path} is {size} B, over the {BYTE_BUDGET} B budget — it was "
                                     "probably committed at the official size")
                # The re-derivation, so a hand-edited or stale file is caught rather than merely sized.
                expected = resample(source)
                if committed.convert("RGB").tobytes() != expected.tobytes():
                    raise SystemExit(f"{path} does not match a fresh downscale of {source} "
                                     "(run without --check to rewrite it)")
                total += size
                count += 1
                print(f"ok: {path} {committed.size[0]}x{committed.size[1]} {size} B "
                      f"from {os.path.basename(source)}")
        print(f"ok: {count} still(s), {total} B total")
        return 0

    os.makedirs(out_dir, exist_ok=True)
    for character, rungs in wanted.items():
        for rung, source in rungs.items():
            path = os.path.join(out_dir, out_name(character, rung))
            resample(source).save(path, format="PNG", optimize=True)
            print(f"wrote {path} from {os.path.basename(source)} ({os.path.getsize(path)} B)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
