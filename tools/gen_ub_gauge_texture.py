"""Cuts the UB gauge's trough and fill out of the official <b>fever bar</b>.

<h2>What the human said</h2>
「UB槽的UI不行，用官方的」 — and his screenshot shows a pink circle beside a yellow bar with a dark
three-segment tail, which is a square gauge tile stretched into a bar shape. That is what
``abyss_ef_result_gauge`` is (a 128x128 <em>square</em> tile of the result screen) and stretching it
horizontally is exactly why it read wrong.

<h2>What replaced it</h2>
``pct_fever_bar.png`` (512x32) — the game's own <b>horizontal</b> fever gauge. It is a real bar: a
rounded-cap capsule whose middle rows are fully opaque and whose colour ramps left-to-right through a
rainbow (mint → cyan → blue → violet → pink → peach → yellow). A bar-shaped sprite needs no invented
geometry, and the rainbow is the reason the official gauge reads as "charging" at a glance.

<h2>Why two committed files, and how each is made</h2>
The HUD needs a <b>trough</b> (what the channel looks like when empty) and a <b>fill</b> (what the charge
puts in it), and the pair has to be the same shape or the fill will not sit inside the track. So both are
derived from the one official bar:

* <b>``ub_gauge_track.png``</b> — the official bar's own alpha and rounded-capsule silhouette, filled with a
  single dark neutral (the trough). Keeping the <em>shape</em> is what makes the two layers agree; making it
  one colour is what stops an empty bar from looking half-charged.
* <b>``ub_gauge_fill.png``</b> — the official bar's own colours, unchanged. This is the art the human
  pointed at.

Both are cut to the bar's <b>solid band</b> by measurement (:func:`band_rows`), for the same reason the
previous generator did: the capsule has a soft two-pixel glow above and below, and letting it into the
texture puts a grey halo around the HUD widget.

<h2>Why 256x24</h2>
The HUD draws the bar at 90x10 GUI pixels, i.e. 180x20 physical pixels at GUI scale 2. 256x24 is the
smallest size that still oversamples that draw, keeps the capsule's rounded ends crisp, and preserves the
rainbow's gradient across the full width — a 90-wide texture would be sampled 1:1 at scale 1 and lose the
caps.

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

#: The official horizontal bar both layers are derived from. See the module docstring.
BAR = "pct_fever_bar.png"

#: The committed size. See the module docstring for the arithmetic.
WIDTH = 256
HEIGHT = 24

#: Alpha below this is glow, not bar. The band is cropped to the rows that are at least this solid.
SOLID_ALPHA = 96

#: The trough's one colour: the same dark neutral the book panel's floor uses, so the two HUD surfaces do
#: not disagree about what "an empty channel" looks like.
TROUGH = (20, 22, 28, 255)

#: Committed output, relative to the repository root.
OUTPUT_DIR = os.path.join("src", "main", "resources", "assets", "astrum", "textures", "gui")


def band_rows(image: Image.Image) -> tuple[int, int]:
    """Returns the first and last row (inclusive) that carry real bar pixels.

    A row counts as solid when at least a quarter of its pixels are above :data:`SOLID_ALPHA`. A fraction
    rather than "any pixel" is what rejects the glow above the capsule's top edge; a fraction rather than
    "all pixels" keeps the rounded caps, whose rows are a pixel or two shorter than the middle.
    """
    alpha = image.getchannel("A")
    width, height = image.size
    needed = max(1, width // 4)
    rows = []
    for y in range(height):
        row = [alpha.getpixel((x, y)) for x in range(width)]
        if sum(1 for value in row if value >= SOLID_ALPHA) >= needed:
            rows.append(y)
    if not rows:
        raise SystemExit("no solid band found — is the source the fever bar?")
    return rows[0], rows[-1]


def load_bar(source_dir: str) -> Image.Image:
    """Loads the official bar and crops it to its own solid band."""
    path = os.path.join(source_dir, BAR)
    if not os.path.isfile(path):
        raise SystemExit("official fever bar not found: " + path + " (pass --source DIR)")
    with Image.open(path) as loaded:
        image = loaded.convert("RGBA")
    top, bottom = band_rows(image)
    band = image.crop((0, top, image.size[0], bottom + 1))
    # LANCZOS for the fill: this is a colour ramp, and a box downscale keeps the rainbow smooth instead of
    # banding it into visible steps at HUD size.
    return band.resize((WIDTH, HEIGHT), Image.LANCZOS)


def trough(fill: Image.Image) -> Image.Image:
    """Returns the fill's silhouette painted in one colour: the empty channel."""
    track = Image.new("RGBA", fill.size, TROUGH)
    track.putalpha(fill.getchannel("A"))
    return track


def load(directory: str, name: str) -> Image.Image:
    """Loads a committed texture for ``--check``."""
    path = os.path.join(directory, name)
    if not os.path.isfile(path):
        raise SystemExit("missing committed gauge texture: " + path)
    with Image.open(path) as image:
        return image.convert("RGBA")


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
                raise SystemExit(f"{name}.png is {image.size} {image.mode}, "
                                 f"expected {WIDTH}x{HEIGHT} RGBA")
        if track.tobytes() == fill.tobytes():
            raise SystemExit("track and fill are identical — the pair was cut from one file")
        if track.getchannel("A").tobytes() != fill.getchannel("A").tobytes():
            raise SystemExit("the track and the fill do not share a silhouette, so the fill cannot sit "
                             "inside the trough")
        print(f"ok: 2 gauge textures are {WIDTH}x{HEIGHT} RGBA, share a silhouette and differ in colour")
        return 0

    os.makedirs(out_dir, exist_ok=True)
    fill = load_bar(args.source)
    for name, image in (("ub_gauge_track.png", trough(fill)), ("ub_gauge_fill.png", fill)):
        path = os.path.join(out_dir, name)
        image.save(path, format="PNG", optimize=True)
        print(f"wrote {path} from {BAR} ({os.path.getsize(path)} B)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
