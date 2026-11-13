"""Generates the three character-card item textures from Djeeta's official PCR avatar.

The card used to be a hand-drawn 16x16 placeholder (tools/gen_character_card_texture.py), because
this slice had no art pipeline for item icons. The official art *is* available now: the unpacked PCR
resources at ``K:\\AI\\Minecraft\\assets\\pcr\\extracted\\图片`` hold one 128x128 avatar per unit,
and Djeeta's 3-star avatar is ``icon_unit_105731.png``.

The card is the player's first contact with a character, so it should show her, not a drawn stand-in:
this script resamples that avatar to 32x32 with NEAREST and writes the three themed card textures.
NEAREST (not LANCZOS) is deliberate — a 128 -> 32 box downscale of a face loses the eyes into mush
with a smoothing filter, while NEAREST keeps hard pixel edges and stays readable in a 16-pixel slot.

The three themes get the *same* image: the request was "the official avatar on the card", and the
theme is already carried by the item name, the tooltip line and the book screen's panel art. Tinting
the avatar per theme would put a coloured wash over the face, which is worse than three identical
icons. ``character_book`` textures are untouched: the book is the collection album and keeps its
hand-drawn art.

Regenerate with:

    python tools/gen_card_from_avatar.py [--source PATH] [--check]

``--source`` defaults to the unpacked official path above; the source PNG is NOT committed (only the
32x32 results are). ``--check`` re-reads the committed files and fails when one is not a 32x32 RGBA
image, so a later edit cannot silently reintroduce a different size.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import sys

from PIL import Image

#: Where the official, unpacked PCR art lives on this machine. Not part of the repository.
DEFAULT_SOURCE = os.path.join(
    "K:\\", "AI", "Minecraft", "assets", "pcr", "extracted", "图片", "icon_unit_105731.png"
)

#: The unit whose avatar the card shows: Djeeta, 3-star (105731; 105761 is the 6-star art).
UNIT = "105731"

#: Card texture size, as requested: the official 128x128 avatar resampled to 32x32.
SIZE = 32

#: Committed outputs, relative to the repository root, one per card theme.
OUTPUTS = (
    os.path.join("src", "main", "resources", "assets", "astrum", "textures", "item",
                 "character_card.png"),
    os.path.join("src", "main", "resources", "assets", "astrum", "textures", "item",
                 "theme_sakura", "character_card.png"),
    os.path.join("src", "main", "resources", "assets", "astrum", "textures", "item",
                 "theme_forest", "character_card.png"),
)


def build(source: str) -> Image.Image:
    """Loads the official avatar and resamples it to SIZE x SIZE with NEAREST."""
    if not os.path.isfile(source):
        raise SystemExit(
            "source avatar not found: " + source +
            "\nThe official art is not in the repository; pass --source PATH to the 128x128 PNG."
        )
    with Image.open(source) as source_image:
        avatar = source_image.convert("RGBA")
    if avatar.size != (128, 128):
        raise SystemExit(f"expected a 128x128 avatar, {source} is {avatar.size[0]}x{avatar.size[1]}")
    return avatar.resize((SIZE, SIZE), Image.NEAREST)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", default=DEFAULT_SOURCE, help="the official 128x128 avatar PNG")
    parser.add_argument("--check", action="store_true",
                        help="verify the committed textures instead of writing them")
    args = parser.parse_args(argv)

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    outputs = [os.path.join(root, path) for path in OUTPUTS]

    if args.check:
        for path in outputs:
            with Image.open(path) as image:
                if image.size != (SIZE, SIZE) or image.mode != "RGBA":
                    raise SystemExit(f"{path} is {image.size} {image.mode}, expected {SIZE}x{SIZE} RGBA")
        print("ok: %d committed card textures are %dx%d" % (len(outputs), SIZE, SIZE))
        return 0

    card = build(args.source)
    for path in outputs:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        card.save(path, format="PNG", optimize=True)
        print("wrote %s (%dx%d)" % (path, SIZE, SIZE))
    print("sha256 %s" % hashlib.sha256(card.tobytes()).hexdigest())
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))