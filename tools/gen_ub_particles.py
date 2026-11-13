"""Build the UB particle atlases out of the official Princess Connect effect art.

Why this script exists
----------------------
The UB is a real particle effect now, so it needs *particle sprites*: small tiles of pure light
whose alpha reaches zero before the tile edge.  The art is the human's unpacked official PCR
effect set (``图片/fx_djeeta*``), which is far better than anything drawn or generated here — so
this script does not draw.  It **cuts sprites out of the official sheets** and only commits the
derived atlas.

The official textures are two different kinds, and both need handling
---------------------------------------------------------------------
* Some have a real alpha channel (``fx_djeeta1_tex_009``, ``tex_015``, ...).  Those are plain
  cut-outs: alpha is the shape.
* Most are **additive-blend** textures: the canvas is fully opaque and the *luminance* is the
  light (the game adds them to the scene, so black is "nothing").  For those the shape is the
  light mask ``luminance * alpha``, and that mask is also exactly the alpha a particle sprite
  wants.  A naive ``getchannel("A")`` sees a 256x256 opaque square there and finds no sprites at
  all — that was the first thing this script got wrong.

Sprite sheets
-------------
``tex_009``, ``tex_015``, ``tex_011``, ``tex_013``, ``glory tex_005``, ``wr tex_139`` and others
hold **several sprites on one canvas**, in no regular grid.  So the sub-rectangles are found by
labeling the light mask's connected components and taking each component's bounding box — not by
guessing a grid.  ``--list`` prints the components of a file so a sprite can be named by index;
``SPARKS``/``MOTES`` below name exactly those indices, and every chosen box is printed on a run so
the choice is auditable.

The one property the whole task turns on
----------------------------------------
**Alpha fades to 0 at every tile border.**  Each sprite is fitted into a centred box of
``CORE`` pixels inside a ``TILE``x``TILE`` cell and multiplied by a smoothstep ramp that is exactly
0 on the outermost pixel ring and reaches 1 at ``CORE``.  No particle can ever show a square edge —
which is the defect this task replaces, at the scale of a sprite instead of the screen.
``UbParticleAtlasTest`` re-asserts the property on the shipped PNGs.

Output (committed, these two files and nothing else):
    assets/astrum/textures/particle/ub_spark.png   TILE x TILE*len(SPARKS)
    assets/astrum/textures/particle/ub_mote.png    TILE x TILE*len(MOTES)

Usage:
    <python> tools/gen_ub_particles.py            # write the atlases, print every choice
    <python> tools/gen_ub_particles.py --check    # write nothing, only report
    <python> tools/gen_ub_particles.py --list fx_djeeta1_tex_015.png
"""

from __future__ import annotations

import math
import sys
from collections import deque
from pathlib import Path

from PIL import Image

SRC = Path(r"K:\AI\Minecraft\assets\pcr\extracted\图片")
OUT = Path(__file__).resolve().parent.parent / "src" / "main" / "resources" / "assets" / "astrum" / "textures" / "particle"

TILE = 64
CORE = 32           # sprite is fitted into a CORE x CORE box, centred in the TILE
LIGHT_FLOOR = 0.06  # luminance below this is not light: it is the black canvas
MIN_AREA = 40       # components smaller than this are speckle, not a sprite

# (file, component index in the sorted component list, what it is)
#
# The component sort is (top edge rounded to a 32 px band, then left edge), so an index names a
# sprite stably as long as the source file is the one on disk. Every box is printed on a run.
SPARKS = [
    ("fx_djeeta1_tex_015.png", 0, "white slash streak, the widest of the four in the sheet (245x29)"),
    ("fx_djeeta1_tex_015.png", 2, "white slash streak, shorter than index 0 (240x28)"),
    ("fx_djeeta1_tex_011.png", 0, "large blue crescent, the burst's signature arc (100x82)"),
    ("fx_djeeta1_tex_011.png", 2, "small blue crescent from the same sheet (72x55)"),
    ("fx_djeeta1_tex_016.png", 0, "single blue crescent, full canvas (228x230)"),
    ("fx_djeeta1_tex_013.png", 0, "blue horizontal blade streak (184x29)"),
    ("fx_djeeta1_tex_009.png", 3, "white flame tongue cluster (66x80)"),
    ("fx_djeetaglory1_tex_029.png", 1, "white sparkle streak (171x51)"),
    ("fx_djeetaglory1_tex_022.png", 2, "white chevron slash streak (389x96)"),
    ("fx_djeetawr1_tex_141.png", 2, "golden lightning streak, the burst's warm accent (433x71)"),
]

MOTES = [
    ("fx_djeetaglory1_tex_008.png", 0, "soft round white orb, the ember core"),
    ("fx_djeetaglory1_tex_043.png", 0, "golden glow ball, the warm ember"),
    ("fx_djeetawr1_tex_118.png", 0, "soft grey-white orb, dimmer than the Glory one"),
    ("fx_djeetaglory1_tex_004.png", 0, "four-point sparkle star"),
    ("fx_djeetaglory1_tex_032.png", 0, "blue eight-point starburst"),
    ("fx_djeetaglory1_tex_013.png", 0, "golden diamond with a lit edge"),
]


# ---------------------------------------------------------------------- the light mask


def light_mask(im: Image.Image) -> Image.Image:
    """``luminance * alpha``, 0-255: the shape of an additive texture, and the alpha we want.

    Using luminance rather than the alpha channel is what makes the additive half of the official
    set usable at all -- on those files every pixel's alpha is 255 and the drawing is in the colour.
    """
    rgb = im.convert("RGB")
    a = im.getchannel("A") if "A" in im.getbands() else None
    out = Image.new("L", rgb.size)
    src = rgb.load()
    dst = out.load()
    mask = a.load() if a is not None else None
    for y in range(rgb.height):
        for x in range(rgb.width):
            r, g, b = src[x, y]
            lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
            if mask is not None:
                lum *= mask[x, y] / 255.0
            dst[x, y] = int(lum + 0.5)
    return out


def components(mask: Image.Image, floor: int) -> list[tuple[int, int, int, int, int]]:
    """4-connected components of ``mask > floor``: (x0, y0, x1, y1, area), sorted.

    Sorted by (top edge in 32 px bands, then left edge) so an index in ``SPARKS`` means the same
    sprite on every machine, while still allowing an irregular layout: a band groups sprites that
    sit on roughly the same row of a sheet, and within a band they read left to right.
    """
    w, h = mask.size
    px = mask.load()
    alive = [[px[x, y] > floor for x in range(w)] for y in range(h)]
    seen = [[False] * w for _ in range(h)]
    found = []
    for y in range(h):
        for x in range(w):
            if not alive[y][x] or seen[y][x]:
                continue
            queue = deque([(x, y)])
            seen[y][x] = True
            x0 = x1 = x
            y0 = y1 = y
            area = 0
            while queue:
                cx, cy = queue.popleft()
                area += 1
                x0 = min(x0, cx)
                x1 = max(x1, cx)
                y0 = min(y0, cy)
                y1 = max(y1, cy)
                for nx, ny in ((cx + 1, cy), (cx - 1, cy), (cx, cy + 1), (cx, cy - 1)):
                    if 0 <= nx < w and 0 <= ny < h and alive[ny][nx] and not seen[ny][nx]:
                        seen[ny][nx] = True
                        queue.append((nx, ny))
            if area >= MIN_AREA:
                found.append((x0, y0, x1 + 1, y1 + 1, area))
    found.sort(key=lambda b: (b[1] // 32, b[0]))
    return found


# ---------------------------------------------------------------------- tile rendering


def smoothstep(edge0: float, edge1: float, x: float) -> float:
    if edge1 <= edge0:
        return 0.0 if x < edge0 else 1.0
    t = (x - edge0) / (edge1 - edge0)
    t = 0.0 if t < 0.0 else (1.0 if t > 1.0 else t)
    return t * t * (3.0 - 2.0 * t)


def border_ramp(x: int, y: int) -> float:
    """0 on the outermost pixel ring, ramps to 1 at the edge of the inner ``CORE`` box.

    ``min`` over the four edges, so a corner fades on both axes.  The ramp starts 1.5 px in, which is
    what makes ``alpha == 0`` on the whole first row/column exactly true (pixel centres are 0.5 px
    from the mathematical edge, so the first *two* rings multiply to zero) instead of merely small —
    the atlas test asserts the zero, not an epsilon.  Content lives only inside the ``CORE`` box, so
    a sprite's brightest pixel sits where the ramp is already ~1 and the light decays outward.
    """
    inset = (TILE - CORE) / 2.0
    d = min(x + 0.5, y + 0.5, TILE - 0.5 - x, TILE - 0.5 - y)
    return smoothstep(1.5, inset + 1.5, d)


def render_tile(im: Image.Image, box: tuple[int, int, int, int]) -> Image.Image:
    """One component -> a TILE x TILE RGBA tile with a soft, border-safe alpha."""
    crop = im.crop(box).convert("RGBA")
    mask = light_mask(crop)

    # Fit the sprite's *content* box into CORE x CORE, preserving aspect, so a wide slash stays a
    # wide slash and a round orb stays round.
    content = mask.point(lambda v: 255 if v > int(LIGHT_FLOOR * 255) else 0).getbbox()
    if content:
        crop = crop.crop(content)
        mask = mask.crop(content)
    scale = min(CORE / max(1, crop.width), CORE / max(1, crop.height))
    size = (max(1, round(crop.width * scale)), max(1, round(crop.height * scale)))
    rgb = crop.convert("RGB").resize(size, Image.LANCZOS)
    lum = mask.resize(size, Image.LANCZOS)

    # Colour: normalize so the brightest channel is 255.  The official sprites already carry the
    # right hue (blue crescents, white slashes, gold diamonds); only the exposure is normalized, so
    # a dim additive sprite still reads as light instead of vanishing under alpha blending.
    peak = max(rgb.getchannel(c).getextrema()[1] for c in ("R", "G", "B")) or 1
    gain = 255.0 / peak

    tile = Image.new("RGBA", (TILE, TILE), (0, 0, 0, 0))
    ox = (TILE - size[0]) // 2
    oy = (TILE - size[1]) // 2
    src = rgb.load()
    light = lum.load()
    dst = tile.load()

    raw = [[0.0] * TILE for _ in range(TILE)]
    for y in range(size[1]):
        for x in range(size[0]):
            l = light[x, y] / 255.0
            if l <= LIGHT_FLOOR:
                continue
            # The floor is subtracted and rescaled so the black canvas contributes nothing and the
            # sprite's own falloff is not flattened into a plateau.
            l = (l - LIGHT_FLOOR) / (1.0 - LIGHT_FLOOR)
            raw[oy + y][ox + x] = min(1.0, l * border_ramp(ox + x, oy + y))

    top = max((v for row in raw for v in row), default=0.0)
    if top <= 0.0:
        raise ValueError("component renders to an empty tile")
    for y in range(TILE):
        for x in range(TILE):
            a = raw[y][x] / top
            if a < 0.012:
                continue
            r, g, b = src[x - ox, y - oy] if (0 <= x - ox < size[0] and 0 <= y - oy < size[1]) else (255, 255, 255)
            dst[x, y] = (
                min(255, int(r * gain + 0.5)),
                min(255, int(g * gain + 0.5)),
                min(255, int(b * gain + 0.5)),
                min(255, int(a * 255 + 0.5)),
            )
    return tile


def border_alpha_max(tile: Image.Image) -> int:
    """The largest alpha on a tile's outermost ring; must be 0 for every tile."""
    a = tile.getchannel("A").load()
    return max([a[x, 0] for x in range(TILE)] + [a[x, TILE - 1] for x in range(TILE)]
               + [a[0, y] for y in range(TILE)] + [a[TILE - 1, y] for y in range(TILE)])


# ---------------------------------------------------------------------- driver


def pick(name: str, index: int) -> tuple[Image.Image, tuple[int, int, int, int], int]:
    im = Image.open(SRC / name).convert("RGBA")
    boxes = components(light_mask(im), int(LIGHT_FLOOR * 255))
    if not boxes:
        raise SystemExit(f"{name}: no sprite found above the light floor")
    if index >= len(boxes):
        raise SystemExit(f"{name}: component {index} does not exist ({len(boxes)} found)")
    return im, boxes[index][:4], len(boxes)


def build(name: str, spec: list[tuple[str, int, str]], dry: bool) -> None:
    tiles = []
    print(f"\n{name}: {len(spec)} tiles, {TILE}x{TILE} each")
    for src_name, index, what in spec:
        im, box, total = pick(src_name, index)
        tile = render_tile(im, box)
        worst = border_alpha_max(tile)
        if worst != 0:
            raise SystemExit(f"{src_name}[{index}]: border alpha {worst} — a square edge would show")
        tiles.append(tile)
        print(f"  {src_name:32} comp {index}/{total - 1:<3} box {box!s:26} "
              f"border={worst} peak={tile.getchannel('A').getextrema()[1]:3}  {what}")

    atlas = Image.new("RGBA", (TILE * len(tiles), TILE), (0, 0, 0, 0))
    for i, tile in enumerate(tiles):
        atlas.paste(tile, (i * TILE, 0))
    dest = OUT / name
    if dry:
        print(f"  would write {dest.name} ({atlas.width}x{atlas.height})")
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    atlas.save(dest, "PNG", optimize=True)
    print(f"  wrote {dest} ({atlas.width}x{atlas.height}, {dest.stat().st_size / 1024:.1f} KB)")


def list_file(name: str) -> None:
    im = Image.open(SRC / name).convert("RGBA")
    boxes = components(light_mask(im), int(LIGHT_FLOOR * 255))
    print(f"{name}: {im.size[0]}x{im.size[1]}, {len(boxes)} components above the light floor")
    for i, (x0, y0, x1, y1, area) in enumerate(boxes):
        print(f"  [{i:2}] box=({x0},{y0},{x1},{y1}) {x1 - x0}x{y1 - y0} area={area}")


def main() -> int:
    args = sys.argv[1:]
    if "--list" in args:
        for name in args[args.index("--list") + 1:]:
            list_file(name)
        return 0
    if not SRC.is_dir():
        print(f"官方特效目录不存在: {SRC}", file=sys.stderr)
        return 2
    dry = "--check" in args
    build("ub_spark.png", SPARKS, dry)
    build("ub_mote.png", MOTES, dry)
    print(f"\n输出目录 {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())