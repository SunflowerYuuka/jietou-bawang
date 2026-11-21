"""Draws the **placeholder** the mod shows when the resource pack is not installed.

<h2>Why this exists at all</h2>
The mod ships **zero** official art: every character portrait, still, Q-version and skill icon is
Cygames' and lives outside the repository (see ``.gitignore``). Players who want the pictures install
``astrum-assets.zip`` — a release asset, not a file in the tree — and until they do, every
``astrum:textures/gui/...`` path the client asks for resolves to nothing.

<p>Nothing is the wrong answer. Vanilla draws Minecraft's **missing-texture checkerboard** (magenta and
black squares) for a texture that cannot be found, and the human has photographed that defect twice. So
this script draws one honest picture, ``placeholder.png``, and the client draws *it* wherever an art path
is absent — see ``impl/client/ArtFallback`` for the mechanism and for exactly which surfaces can and
cannot be intercepted.

<h2>What it says</h2>
「请安装资源包」 — install the resource pack. That is the only instruction a player needs, and it is the one
thing the checkerboard cannot say. A second, smaller line names the file: 「资源包」/ ``astrum-assets``.

<h2>Why the text is rendered with a real font, and what that costs</h2>
The first version hand-drew the four characters as 12x12 bitmaps so the script would have no dependency
at all. At 64 pixels it was unreadable, and at 128 pixels with hand-built bitmaps it was still only
*suggestive* of 请安装资源包 — a 12x12 grid cannot carry a CJK glyph's stroke order, and inventing one is
how a placeholder ends up looking like a different, wrong character. So the text is now rendered with
the first available system CJK font at a size that is legible when the book draws the cell at 24 GUI
pixels:

    <size>56</size>  「请安装」  on the first line
    <size>40</size>  「资源包」  on the second

<p><b>The dependency, stated plainly.</b> Re-deriving the file therefore needs a CJK font. The candidates
are listed in :data:`FONT_CANDIDATES` (Windows' Microsoft YaHei / SimHei, macOS' PingFang, and the two
common Linux Noto paths). ``--check`` names the font it used and <b>fails honestly</b> when none is
installed rather than silently accepting a different rendering — a check that can pass two ways is not a
check. The committed PNG is what ships; the font is only needed to regenerate it.

Run:

    python tools/gen_placeholder.py [--check]

``--check`` re-derives the committed file and fails on any pixel difference.
"""

from __future__ import annotations

import argparse
import os
import sys

from PIL import Image, ImageDraw, ImageFont

#: Edge of the committed placeholder, in pixels. 128 because the two text lines above need ~56 + ~40 pixels
#: of type plus margins, and because the two surfaces that draw it (a 24-pixel roster cell and a 400x293
#: panel body) both downsample from it without visible aliasing.
SIZE = 128

#: Destination, relative to the repository root. It lives in the **mod's own** texture tree because it is
#: ours — it carries no official art, so it is committed rather than generated into the release pack.
OUTPUT = os.path.join("src", "main", "resources", "assets", "astrum", "textures", "gui",
                      "placeholder.png")

#: Every font that can render the two lines, in preference order, with the two TTC face indices worth
#: trying. A ``.ttc`` is a collection; index 0 is the regular face for all three Microsoft ones.
FONT_CANDIDATES = (
    (r"C:\Windows\Fonts\msyh.ttc", 0),          # Microsoft YaHei — Windows' default UI face
    (r"C:\Windows\Fonts\msyhbd.ttc", 0),        # ... its bold face, if the regular one is absent
    (r"C:\Windows\Fonts\simhei.ttf", 0),        # SimHei — present on every Windows since XP
    (r"C:\Windows\Fonts\Deng.ttf", 0),          # DengXian
    ("/System/Library/Fonts/PingFang.ttc", 0),  # macOS
    ("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", 0),
    ("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc", 0),
)

#: The four colours, and nothing else: a deep neutral ground, a warm border, cream text, and a dim tone
#: for the rule. Deliberately the same family as ``OfficialPanel.BODY_FLOOR`` and the frame's cream gold,
#: so a placeholder cell does not look like a foreign object inside the 角色册.
GROUND = (20, 22, 28, 255)
BORDER = (221, 169, 60, 255)
TEXT = (240, 230, 200, 255)
DIM = (154, 164, 184, 255)

#: The two lines, and the size each is set at.
#:
#: Chosen against the box, not by eye: three CJK characters at size N are about ``3N`` wide, and the inner
#: area is 128 - 2*8 = 112 pixels. So the largest legal size is ~37, and 34 leaves a readable margin on
#: both sides. The first attempt at 56/40 overflowed — the border cut through 装 and 包 — which is exactly
#: the failure a fixed size is meant to prevent and only a rendered picture can reveal.
LINE_1 = "请安装"
SIZE_1 = 34
LINE_2 = "资源包"
SIZE_2 = 26

#: The name of the file a player is being told to install, set small under the rule. Latin only, so it is
#: legible in any font — and it is the one word that turns 「install a resource pack」 into a searchable
#: filename.
LINE_3 = "astrum-assets.zip"
SIZE_3 = 15


def find_font() -> tuple[str, int]:
    """Returns the first candidate font that exists, as ``(path, face_index)``.

    Raises ``SystemExit`` naming every candidate when none does: a clear failure here is far better than
    a placeholder rendered in a Latin-only fallback, which would put four boxes where the instruction goes.
    """
    for path, index in FONT_CANDIDATES:
        if os.path.isfile(path):
            return path, index
    raise SystemExit(
        "no CJK font found, so the placeholder cannot be rendered honestly. Looked for:\n  "
        + "\n  ".join(path for path, _ in FONT_CANDIDATES)
        + "\nInstall one of them (or add its path to FONT_CANDIDATES) and re-run."
    )


def load(path: str, index: int, size: int) -> ImageFont.FreeTypeFont:
    """Loads one face at one size."""
    return ImageFont.truetype(path, size, index=index)


def centred(draw: ImageDraw.ImageDraw, text: str, font: ImageFont.FreeTypeFont, top: int) -> int:
    """Draws ``text`` centred horizontally at ``top``; returns the baseline height it consumed."""
    left, upper, right, lower = draw.textbbox((0, 0), text, font=font)
    width = right - left
    height = lower - upper
    draw.text(((SIZE - width) // 2 - left, top - upper), text, font=font, fill=TEXT)
    return height


def render() -> Image.Image:
    """Returns the 128x128 placeholder."""
    path, index = find_font()
    image = Image.new("RGBA", (SIZE, SIZE), GROUND)
    draw = ImageDraw.Draw(image)

    # A three-pixel border, inset two, so the picture reads as a *cell* inside the book's frame rather
    # than as a picture that failed to lay out.
    draw.rectangle([2, 2, SIZE - 3, SIZE - 3], outline=BORDER, width=3)

    # 请安装  /  资源包, then a rule, then the file name. The gaps are 2 and 6 pixels: the two CJK lines are
    # the caption and must read as one block; the file name is a separate footnote under the rule.
    first = centred(draw, LINE_1, load(path, index, SIZE_1), 12)
    second = centred(draw, LINE_2, load(path, index, SIZE_2), 12 + first + 2)
    rule_y = 12 + first + 2 + second + 6
    draw.rectangle([10, rule_y, SIZE - 11, rule_y], fill=DIM)
    centred(draw, LINE_3, load(path, index, SIZE_3), rule_y + 4)
    return image


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="verify the committed placeholder instead of writing it")
    args = parser.parse_args(argv)

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    path = os.path.join(root, OUTPUT)
    wanted = render()

    if args.check:
        if not os.path.isfile(path):
            raise SystemExit("missing committed placeholder: " + path)
        with Image.open(path) as committed:
            actual = committed.convert("RGBA")
        if actual.size != (SIZE, SIZE):
            raise SystemExit(f"{path} is {actual.size}, expected {SIZE}x{SIZE}")
        if actual.tobytes() != wanted.tobytes():
            raise SystemExit(f"{path} does not match a fresh render with {find_font()[0]} "
                             "(run without --check to rewrite it)")
        print("ok: placeholder matches a fresh render with %s, %dx%d, %d B"
              % (find_font()[0], SIZE, SIZE, os.path.getsize(path)))
        return 0

    os.makedirs(os.path.dirname(path), exist_ok=True)
    wanted.save(path, format="PNG", optimize=True)
    print("wrote %s (%dx%d, %d B) using %s"
          % (path, SIZE, SIZE, os.path.getsize(path), find_font()[0]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
