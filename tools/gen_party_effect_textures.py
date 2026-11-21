"""Generates the five fallback textures for the party effect icons.

Each party slot registers its own ``MobEffect`` (``astrum:party_1`` ... ``astrum:party_5``). On a
client the icon is drawn by our ``IClientMobEffectExtensions`` from the character's own avatar; these
five PNGs are what vanilla draws when a character in the slot has **no** ``avatar`` in its datapack
definition (or before the client has received the party sync). Without them, the effect's sprite
would resolve to Minecraft's missing-texture sprite, which reads as a broken mod.

They are deliberately *not* avatars: a generic rune with the slot's colour and one pip per slot, so
"this effect has no art yet" is visible rather than disguised as a character.

The vanilla mob-effect atlas indexes ``assets/<namespace>/textures/mob_effect/<path>.png`` for every
registered effect, so these files are mandatory even though the avatar path normally replaces them.

Run:

    python tools/gen_party_effect_textures.py [--check]
"""

from __future__ import annotations

import argparse
import os
import sys

from PIL import Image, ImageDraw

SIZE = 16   # same 16x16 as every other effect sprite - see gen_effect_icon_copies.py

#: One colour per slot: five visually distinct entries even when the fallback is what is drawn.
SLOT_COLORS = (
    (86, 156, 255, 255),   # 1 blue
    (255, 122, 190, 255),  # 2 pink
    (112, 214, 128, 255),  # 3 green
    (255, 198, 72, 255),   # 4 amber
    (186, 132, 255, 255),  # 5 violet
)

GOLD = (232, 194, 90, 255)
WHITE = (255, 255, 255, 255)

OUTPUT_DIR = os.path.join("src", "main", "resources", "assets", "astrum", "textures", "mob_effect")


def build(slot: int) -> Image.Image:
    image = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    colour = SLOT_COLORS[slot]

    # A gold-outlined diamond, the same emblem family as the card and book frames.
    diamond = [(16, 2), (30, 16), (16, 30), (2, 16)]
    draw.polygon(diamond, fill=colour)
    draw.line(diamond + [diamond[0]], fill=GOLD, width=2)

    # One pip per one-based slot number, centred in two columns when there are five.
    pips = slot + 1
    positions = {
        1: [(16, 16)],
        2: [(11, 16), (21, 16)],
        3: [(10, 16), (16, 16), (22, 16)],
        4: [(10, 11), (22, 11), (10, 21), (22, 21)],
        5: [(10, 11), (22, 11), (16, 16), (10, 21), (22, 21)],
    }[pips]
    for x, y in positions:
        draw.ellipse((x - 3, y - 3, x + 3, y + 3), fill=WHITE, outline=(40, 40, 40, 255))
    return image


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="verify the committed textures instead of writing them")
    args = parser.parse_args(argv)

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out_dir = os.path.join(root, OUTPUT_DIR)

    if args.check:
        for slot in range(len(SLOT_COLORS)):
            path = os.path.join(out_dir, "party_%d.png" % (slot + 1))
            if not os.path.isfile(path):
                raise SystemExit("missing fallback effect texture: " + path)
            with Image.open(path) as image:
                if image.size != (SIZE, SIZE):
                    raise SystemExit(f"{path} is {image.size}, expected {SIZE}x{SIZE}")
        print("ok: %d fallback effect textures" % len(SLOT_COLORS))
        return 0

    os.makedirs(out_dir, exist_ok=True)
    for slot in range(len(SLOT_COLORS)):
        path = os.path.join(out_dir, "party_%d.png" % (slot + 1))
        build(slot).save(path, format="PNG", optimize=True)
        print("wrote " + path)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))