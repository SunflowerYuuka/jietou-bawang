"""Writes the two **character-marker** effect sprites: ``counter.png`` (雪菲) and ``spirit.png`` (千歌).

<h2>Why these two are portraits and the other effects are not</h2>
The human's ruling, twice: 「计数这种角色专属的效果，图标使用角色的头像，方便查看」 and 「冰龙之印这种类型的效果使用角色头像」.
A marker whose whole content belongs to one character reads as *hers* when it wears her face, where a generic
symbol reads as nothing. Every other effect keeps the shared placeholder.

<h2>Why this script exists at all</h2>
Both files are declared by ``.gitignore`` as derived official art, and ``tools/build_asset_pack.py`` builds the
pack from that declaration — so if nothing writes them, the pack build fails with "missing texture" and the two
rows show the magenta checkerboard. They used to be written by ``gen_effect_icon_copies.py``, which was wrong in
the other direction: that script's job is to stamp the *same placeholder* onto every generic effect, so a run
would overwrite the portraits with a placeholder. A dedicated script is the only shape where both facts hold:
they are art, and they are not the placeholder.

<h2>Size</h2>
16×16, the size every effect sprite uses. Vanilla's HUD blits an 18-pixel square whatever the file holds, so a
larger file is more pixels for the same result — and there are two of these next to ninety-one others.

Usage:

    python tools/gen_marker_icons.py [--source DIR]

``--source`` is accepted and ignored so ``build_asset_pack.py`` can pass it to every generator uniformly; the
input is the portrait tree inside this repository, not the official dump.
"""

from __future__ import annotations

import argparse
import os
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

#: Where the portraits live, relative to the repository root.
PORTRAITS = os.path.join("src", "main", "resources", "assets", "astrum", "textures", "gui", "portrait")

#: Where the effect sprites live, relative to the repository root.
SPRITES = os.path.join("src", "main", "resources", "assets", "astrum", "textures", "mob_effect")

#: The size every effect sprite uses.
SIZE = 16

#: (output sprite, source portrait, character) — the portrait is the 6★ one, which is the fullest picture of her.
MARKERS = (
    ("counter.png", "sheffy_6.png", "雪菲 / 计数"),
    ("spirit.png", "chika_6.png", "千歌 / 精灵"),
)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", default=None,
                        help="ignored; accepted so the pack builder can pass it to every generator alike")
    parser.parse_args(argv)

    if not os.path.isdir(PORTRAITS):
        raise SystemExit("no portrait directory at %s — run tools/gen_portraits.py first, because these two "
                         "sprites are copies of the portraits it produces" % PORTRAITS)

    written = 0
    for sprite, portrait, label in MARKERS:
        source = os.path.join(PORTRAITS, portrait)
        if not os.path.isfile(source):
            raise SystemExit("missing portrait %s (needed for %s) — run tools/gen_portraits.py first"
                             % (source, label))
        destination = os.path.join(SPRITES, sprite)
        os.makedirs(SPRITES, exist_ok=True)
        with Image.open(source) as image:
            image.convert("RGBA").resize((SIZE, SIZE), Image.LANCZOS).save(destination, "PNG", optimize=True)
        print("wrote %s <- %s (%s)" % (destination, portrait, label))
        written += 1

    print("wrote %d character-marker sprite(s) at %dx%d" % (written, SIZE, SIZE))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
