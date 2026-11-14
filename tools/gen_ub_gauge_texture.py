"""Cuts the UB gauge's track and fill out of the official PCR gauge art.

<h2>What the human asked for</h2>
「看看能不能使用pcr官方素材替代UB条的充能条」 — the HUD's charge bar was two flat coloured rectangles.
The official dump does contain a gauge pair, and this script is what makes it a texture the HUD can
blit instead of a colour it has to invent.

<h2>Which files, and why these two</h2>
``abyss_ef_result_gauge.png`` / ``abyss_ef_result_gauge_back.png`` (128x128 each, identical twins
under the ``invasion_`` prefix) are the game's own <b>result-screen gauge</b>: a dark track with a
soft gold rim, and a bright gold bar with a white-hot core that sits inside it. They are a *pair* —
one is the empty channel, the other is what fills it — which is exactly the two layers a charge bar
needs, from one visual family rather than two unrelated sprites.

Both are 128x128 <em>tiles</em>, not bars: the bar band sits in the middle rows and the rest is glow.
So the band is cropped out by measurement, not by a guessed constant: :func:`band_rows` finds the
rows where the art is actually solid and keeps those, which is what stops the glow (alpha 30-ish)
from becoming a grey halo around the HUD widget.

<h2>Why 128x16</h2>
The HUD draws the bar at 90x10 GUI pixels, so 128x16 is the smallest size that is still ahead of the
draw at GUI scale 2 and keeps the horizontal gradient of the art (a 90-wide texture would be sampled
1:1 and lose the rounded end). It is stretched, not tiled: the art's ends are rounded, and tiling
would repeat that rounding in the middle of the bar.

<h2>Regenerate</h2>

    python tools/gen_ub_gauge_texture.py             # writes the two committed PNGs
    python tools/gen_ub_gauge_texture.py --check     # verifies size, mode and that they differ
"""

from __future__ import annotations

import argparse
import os
import sys

from PIL import Image

#: The unpacked official PCR art; not part of the repository.
DEFAULT_SOURCE = os.path.join("K:\\", "AI", "Minecraft", "assets", "pcr", "extracted", "图片")

#: Track (the empty channel) and fill (what the charge puts in it), from the same official gauge pair.
TRACK = "abyss_ef_result_gauge_back.png"
FILL = "abyss_ef_result_gauge.png"

#: The band's committed size. See the module docstring.
WIDTH = 128
HEIGHT = 16

#: Alpha below this is glow, not bar. The band is cropped to the rows that are at least this solid.
SOLID_ALPHA = 96

#: Committed output, relative to the repository root.
OUTPUT_DIR = os.path.join("src", "main", "resources", "assets", "astrum", "textures", "gui")


def band_rows(image: Image.Image) -> tuple[int, int]:
    """Returns the first and last row (inclusive) that carry real bar pixels.

    A row counts as solid when at least a quarter of its pixels are above :data:`SOLID_ALPHA`. Using a
    *fraction* rather than "any pixel" is what rejects the single bright pixel the glow leaves at the
    top of the tile; using a fraction rather than "all pixels" keeps the rounded left end, whose rows
    are a pixel or two shorter than the middle.
    """
    alpha = image.getchannel("A")
    width, height = image.size
    needed = max(1, width // 4)
    rows = []
    for y in range(height):
        row = [alpha.getpixel((x, y)) for x in range(width)]
        if sum(1 for a in row if a >= SOLID_ALPHA) >= needed:
            rows.append(y)
    if not rows:
        raise SystemExit("no solid band found — is the source the gauge tile?")
    return rows[0], rows[-1]


def cut(source_dir: str, name: str) -> Image.Image:
    """Crops the bar band out of one official gauge tile and scales it to the committed size."""
    path = os.path.join(source_dir, name)
    if not os.path.isfile(path):
        raise SystemExit("official gauge art not found: " + path + " (pass --source DIR)")
    with Image.open(path) as loaded:
        image = loaded.convert("RGBA")
    top, bottom = band_rows(image)
    band = image.crop((0, top, image.size[0], bottom + 1))
    # LANCZOS, not NEAREST: this is a light gradient, and a box downscale keeps it smooth instead of
    # banding it into visible steps at HUD size.
    return band.resize((WIDTH, HEIGHT), Image.LANCZOS)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", default=DEFAULT_SOURCE, help="the unpacked official image dir")
    parser.add_argument("--check", action="store_true",
                        help="verify the committed gauge textures instead of writing them")
    args = parser.parse_args(argv)

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out_dir = os.path.join(root, OUTPUT_DIR)

    if args.check:
        track = load(out_dir, "ub_gauge_track.png")
        fill = load(out_dir, "ub_gauge_fill.png")
        for name, image in (("ub_gauge_track", track), ("ub_gauge_fill", fill)):
            if image.size != (WIDTH, HEIGHT) or image.mode != "RGBA":
                raise SystemExit(f"{name}.png is {image.size} {image.mode}, expected {WIDTH}x{HEIGHT} RGBA")
        if track.tobytes() == fill.tobytes():
            raise SystemExit("track and fill are identical — the pair was cut from one file")
        print(f"ok: 2 gauge textures are {WIDTH}x{HEIGHT} RGBA and differ")
        return 0

    os.makedirs(out_dir, exist_ok=True)
    for name, out_name in ((TRACK, "ub_gauge_track.png"), (FILL, "ub_gauge_fill.png")):
        band = cut(args.source, name)
        path = os.path.join(out_dir, out_name)
        band.save(path, format="PNG", optimize=True)
        print(f"wrote {path} from {name}")
    return 0


def load(directory: str, name: str) -> Image.Image:
    """Loads a committed texture for ``--check``."""
    path = os.path.join(directory, name)
    if not os.path.isfile(path):
        raise SystemExit("missing committed gauge texture: " + path)
    with Image.open(path) as image:
        return image.convert("RGBA")


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
