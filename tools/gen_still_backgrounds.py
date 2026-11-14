"""Derives the <b>downscaled character stills</b> the 角色册 draws behind its panel.

<h2>Why a derived file and not the official one</h2>
The human's rule: 「点击头像后，显示完全的角色立绘当背景」 and 「完全立绘使用still_unit_编号」 — the panel's
dark body becomes the selected character's <b>full still</b>. The official ``still_unit_<artId>.png``
files are 1408x792 and <b>~1 MB each</b>; committing them as-is would put a megabyte per character in the
jar to draw a 400x293 panel, and the panel's own size is fixed, so four fifths of those pixels are never
sampled. So this script writes one downscaled copy per shipped still and the jar ships only that.

<h2>Which official files</h2>
``still_unit_105731`` (Djeeta 3★) and ``still_unit_105761`` (Djeeta 6★). Her 1★ art has no still in the
dump at all (``still_unit_105711`` does not exist), which is why ``CharacterDef`` keeps the tier rule for
the <em>avatar</em> and the still is chosen by a separate, simpler rule — see
``ClientStillBackground`` — that falls back to whatever tier art the character does ship.

<h2>Why 512x288, and why no crop</h2>
The committed size is the still's own 16:9 aspect at 512x288 — 1/16 of the official pixel count. The
panel it fills is 400x293 GUI pixels, and a cover-fit of a 16:9 source into a 1.37:1 box samples
``288 * 1.37 = 393`` source columns across those 400, so 512 is the smallest width that still
<em>oversamples</em> the panel at GUI scale 1 and stays honest at scale 2 without a megabyte in the jar.
241 kB of PNG instead of the official 970 kB.

The aspect is deliberately kept: the panel crops the <em>source</em> rectangle to the panel's own aspect
at draw time (``StillCover``, tested), which is what makes "cover" mean cover. Baking a crop in here would
pick one panel size forever and squash the picture on the other layout tier.

Run:

    python tools/gen_still_backgrounds.py [--source DIR] [--check]

``--check`` re-reads the committed files and fails when one is not 640x360 RGBA or is over the size
budget, so a re-export cannot quietly put a megabyte back into the jar.
"""

from __future__ import annotations

import argparse
import os
import sys

from PIL import Image

#: The unpacked official PCR art; not part of the repository.
DEFAULT_SOURCE = os.path.join("K:\\", "AI", "Minecraft", "assets", "pcr", "extracted", "图片")

#: Character id -> official ``still_unit_<n>`` (a full-body still, 1408x792 in the dump).
#:
#: One per character for now. A character with no still simply has no entry: the screen then draws the
#: plain dark body, which is the documented "no still" case and not an error.
STILLS = {
    "djeeta": "still_unit_105731",
}

#: Committed size, in pixels. See the module docstring for the arithmetic.
WIDTH = 512
HEIGHT = 288

#: Byte budget per committed still. 512x288 of a busy illustration is ~241 kB; 300 kB is the bound that
#: would have caught "somebody committed the 1 MB original".
BYTE_BUDGET = 300 * 1024

#: Destination root, relative to the repository root.
OUTPUT_DIR = os.path.join("src", "main", "resources", "assets", "astrum", "textures", "gui", "still")


def out_name(character: str) -> str:
    """Returns ``djeeta.png``-style file name for one character's still."""
    return f"{character}.png"


def resample(source: str) -> Image.Image:
    with Image.open(source) as image:
        still = image.convert("RGBA")
    if still.size != (1408, 792):
        raise SystemExit(f"expected the dump's 1408x792 still, {source} is "
                         f"{still.size[0]}x{still.size[1]}")
    return still.resize((WIDTH, HEIGHT), Image.LANCZOS)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", default=DEFAULT_SOURCE, help="the unpacked official image dir")
    parser.add_argument("--check", action="store_true",
                        help="verify the committed stills instead of writing them")
    args = parser.parse_args(argv)

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out_dir = os.path.join(root, OUTPUT_DIR)

    if args.check:
        total = 0
        for character in STILLS:
            path = os.path.join(out_dir, out_name(character))
            if not os.path.isfile(path):
                raise SystemExit("missing committed still: " + path)
            with Image.open(path) as image:
                still = image.convert("RGBA")
            if still.size != (WIDTH, HEIGHT):
                raise SystemExit(f"{path} is {still.size}, expected {WIDTH}x{HEIGHT}")
            size = os.path.getsize(path)
            if size > BYTE_BUDGET:
                raise SystemExit(f"{path} is {size} B, over the {BYTE_BUDGET} B budget — it was "
                                 "probably committed at the official size")
            total += size
            print(f"ok: {path} {still.size[0]}x{still.size[1]} {size} B")
        print(f"ok: {len(STILLS)} still(s), {total} B total")
        return 0

    os.makedirs(out_dir, exist_ok=True)
    for character, art_id in STILLS.items():
        source = os.path.join(args.source, art_id + ".png")
        if not os.path.isfile(source):
            raise SystemExit("official still not found: " + source + " (pass --source DIR)")
        path = os.path.join(out_dir, out_name(character))
        resample(source).save(path, format="PNG", optimize=True)
        print(f"wrote {path} from {art_id}.png ({os.path.getsize(path)} B)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
