"""Slices the <b>official</b> PCR frame into the nine pieces the 角色册 panel is built from.

<h2>What the human asked for</h2>
「九宫格保留，边框不保留，使用官方背景」 — keep the nine-slice <em>mechanism</em> (a fixed image cannot
resize, so a stretched frame is the only way to make a resizable panel out of art), drop <b>our</b>
hand-drawn themed frame, and use official art instead.

<h2>Which official file, and how it was chosen</h2>
``fx_ui_gachabutton_frame_tex.png`` (128x128). It is the game's own <b>rounded rectangle frame</b>: a
1-pixel cream-to-gold stroke with a small flourish in two corners, fully transparent inside. That shape is
exactly what a nine-slice needs and exactly what the dump's other UI art is not —
``inv_waku``/``ybm_waku``/``omp_waku`` are a 256x256 radial <em>vignette</em> (a soft dark blob, nothing to
slice), and the ``tex_obtent_top_*_base`` frames are 512x512 title plates whose glow is baked into the
whole tile, so slicing them leaves the glow's corners floating. This file's stroke is <b>uniform on every
edge</b>, which is the property that makes an edge stretch without distortion.

<h2>How it is sliced</h2>
By measurement, not by guessing a number: :func:`arc_inset` walks the top edge and finds the first column
where the stroke has settled onto the top row, i.e. where the rounded corner ends and the straight edge
begins. Everything outside that inset is a corner; it is pasted unscaled. The eight straight-edge and
centre pieces are cut at the same inset, so the panel's frame band is the corner's own size at every
panel size.

The frame is written as <b>nine 16x16 cells in one 48x48 sheet</b>, in ``NineSlice.PIECE_NAMES`` order
(``tl t tr / l c r / bl b br``) — one texture, so the drawing code samples a cell of one file instead of
holding nine resource locations, and the cell size is a fact of the art the drawing code reads from
:data:`CELL`.

The four corners are pasted as-is, so the panel keeps the art's own rounded corners; the edges are
stretched one way and the centre both ways, which is what the nine-slice mechanism has always done.

<h2>The body fill</h2>
The frame's interior is <b>transparent</b>, and the panel is filled by the selected character's official
still (see ``tools/gen_still_backgrounds.py``). So no separate background image is committed for the body:
the still <em>is</em> the background, which is what the human asked for (「深色底替，换成角色立绘」). When a
character has no still, the screen paints a flat dark fill instead, and that fill's colour is sampled
from this same file's stroke family — it is the frame's own dark neutral, not a new palette.

Run:

    python tools/gen_book_panel_texture.py [--source DIR] [--check]

``--check`` verifies the committed sheet is 48x48 RGBA, that the nine cells tile it, that every corner cell
carries ink and that the centre cell is <b>empty</b> (a filled centre would hide the still).
"""

from __future__ import annotations

import argparse
import os
import sys

from PIL import Image

#: The unpacked official PCR art; not part of the repository.
DEFAULT_SOURCE = os.path.join("K:\\", "AI", "Minecraft", "assets", "pcr", "extracted", "图片")

#: The official frame this panel is sliced from. See the module docstring for why this one.
FRAME = "fx_ui_gachabutton_frame_tex.png"

#: Edge of one cell in the committed sheet, in pixels. 16 is the source inset (see :data:`INSET`) rounded
#: to a power of two: an 8x8 corner scaled to a 30-pixel slice is 3.75x and soft, while a 16-pixel cell is
#: 1.9x — the same magnification the panel's slice was chosen for.
CELL = 16

#: How far in from each edge the source is cut, in source pixels.
#:
#: The rounded corner's arc measures 8 pixels (:func:`arc_inset` proves it against the art), so 12 is the arc
#: plus four pixels of its own straight run: a cell that contains the whole curve and enough straight edge
#: that a stretched neighbour can abut it without a seam. Asserted to be at least the measured arc.
INSET = 12

#: Committed output, relative to the repository root.
OUTPUT_DIR = os.path.join("src", "main", "resources", "assets", "astrum", "textures", "gui")

#: One sheet, nine cells, in NineSlice.PIECE_NAMES order.
OUTPUT_NAME = "book_panel.png"


def alpha(image: Image.Image) -> Image.Image:
    """Returns the alpha channel, so "is there ink here" is one comparison."""
    return image.getchannel("A")


def arc_inset(image: Image.Image) -> int:
    """Returns how many columns the top-left rounded corner occupies.

    The top stroke starts at some x on row 0 and moves left as it curves down. The corner is over where
    that x stops moving: from there inward the top row is one straight band. Measured rather than typed,
    because a re-exported frame would otherwise be sliced through its arc.
    """
    channel = alpha(image)
    width, height = image.size
    previous = None
    stable = 0
    for x in range(width // 2):
        first_row = None
        for y in range(height // 4):
            if channel.getpixel((x, y)) > 16:
                first_row = y
                break
        if first_row is None:
            continue
        if first_row == previous:
            stable += 1
            if stable >= 3:
                return max(1, x - 3)
        else:
            stable = 0
        previous = first_row
    raise SystemExit("no straight top edge found — is the source a rounded frame?")


def source_cells(image: Image.Image, inset: int) -> list[Image.Image]:
    """Cuts the source into nine cells in ``NineSlice.PIECE_NAMES`` order, at ``inset`` each side.

    The three spans along each axis are ``[0, inset)``, ``[inset, size - inset)`` and
    ``[size - inset, size)`` — corner, stretchable middle, corner. Written out rather than derived from a
    loop bound, because the middle span's width is ``size - 2 * inset`` and every off-by-one here puts a
    column of the arc into a stretched edge (which then smears across the whole panel side).
    """
    width, height = image.size
    spans_x = ((0, inset), (inset, width - inset), (width - inset, width))
    spans_y = ((0, inset), (inset, height - inset), (height - inset, height))
    cells = []
    for y0, y1 in spans_y:
        for x0, x1 in spans_x:
            cells.append(image.crop((x0, y0, x1, y1)))
    if len(cells) != 9:
        raise SystemExit("expected nine cells")
    if any(cell.width <= 0 or cell.height <= 0 for cell in cells):
        raise SystemExit(f"a cell is empty at inset {inset} — the source is too small to slice")
    return cells


def build(image: Image.Image) -> Image.Image:
    """Cuts, rescales and tiles the frame into the committed 48x48 sheet."""
    measured = arc_inset(image)
    if measured > INSET:
        raise SystemExit(f"the corner's arc is {measured}px but the cut assumes at most {INSET}px, so the "
                         "arc would be cut in half and an edge would carry part of the curve")
    cells = source_cells(image, INSET)
    sheet = Image.new("RGBA", (CELL * 3, CELL * 3), (0, 0, 0, 0))
    for index, cell in enumerate(cells):
        # LANCZOS, not NEAREST: the stroke is a soft one-pixel gradient and a box filter keeps it from
        # stepping into visible dots when the corner is pasted at 16.
        scaled = cell.resize((CELL, CELL), Image.LANCZOS)
        sheet.alpha_composite(scaled, ((index % 3) * CELL, (index // 3) * CELL))
    return sheet


def load(directory: str, name: str) -> Image.Image:
    path = os.path.join(directory, name)
    if not os.path.isfile(path):
        raise SystemExit("missing texture: " + path)
    with Image.open(path) as image:
        return image.convert("RGBA")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", default=DEFAULT_SOURCE, help="the unpacked official image dir")
    parser.add_argument("--check", action="store_true",
                        help="verify the committed panel sheet instead of writing it")
    args = parser.parse_args(argv)

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out_dir = os.path.join(root, OUTPUT_DIR)

    if args.check:
        sheet = load(out_dir, OUTPUT_NAME)
        if sheet.size != (CELL * 3, CELL * 3) or sheet.mode != "RGBA":
            raise SystemExit(f"{OUTPUT_NAME} is {sheet.size} {sheet.mode}, "
                             f"expected {CELL * 3}x{CELL * 3} RGBA")
        channel = alpha(sheet)
        for index in range(9):
            x0 = (index % 3) * CELL
            y0 = (index // 3) * CELL
            ink = sum(
                1
                for y in range(y0, y0 + CELL)
                for x in range(x0, x0 + CELL)
                if channel.getpixel((x, y)) > 16
            )
            if index == 4:
                if ink != 0:
                    raise SystemExit("the centre cell must be empty: the still is the body, and a "
                                     f"filled centre would hide it ({ink} inked pixels)")
            elif ink == 0:
                raise SystemExit(f"cell {index} carries no ink — the frame would have a gap there")
        print(f"ok: {OUTPUT_NAME} is {CELL * 3}x{CELL * 3} RGBA, eight cells inked, centre empty")
        return 0

    sheet = build(load(args.source, FRAME))
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, OUTPUT_NAME)
    sheet.save(path, format="PNG", optimize=True)
    print(f"wrote {path} from {FRAME} ({os.path.getsize(path)} B), cells of {CELL}px, "
          f"cut at {INSET}px (measured arc 8px)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
