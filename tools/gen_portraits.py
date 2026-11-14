"""Copies the official PCR unit avatars the mod draws as character portraits.

<h2>What these files are for</h2>
``CharacterDef.portrait`` is a character's face in the 角色册 (roster cells, party slots). Until this
task no character shipped one, and the book fell back to a glyph disc — the roster showed a 姬 in a
circle where her face should have been, while the party effect beside it drew her avatar correctly.

There are two ways to fix that, and this script exists because of the one that was taken: **one official
picture per character**, in ``CharacterDef.avatar``, used both as the party effect icon and as the book
portrait (see ``CharacterDef#face``). Pointing ``avatar`` at the item texture would have been the other
way, and it is wrong now that the card is generic: ``textures/item/character_card.png`` is the blank
ticket shared by every character, so it cannot also be one girl's face.

<h2>Why 64x64</h2>
The official avatars are 128x128. The party effect draws at 18 px and the roster cell's portrait at 20
(compact) or 40 (comfortable) — so 64 is the smallest size that is still ahead of every draw site, at a
quarter of the committed bytes. LANCZOS, not NEAREST: this is a face, and a 128→64 box downscale with a
smoothing filter reads better than hard pixel edges at 40 px than at 16.

Run:

    python tools/gen_portraits.py [--source DIR] [--check]

``--check`` re-reads the committed files and fails when one is not 64x64 RGBA.

The game reads these through ``CharacterDef.avatar``, whose path is a full texture path
(``astrum:textures/gui/portrait/djeeta.png``) — the same shape ``djeeta.json`` already used.
"""

from __future__ import annotations

import argparse
import os
import sys

from PIL import Image

#: The unpacked official PCR art; not part of the repository.
DEFAULT_SOURCE = os.path.join("K:\\", "AI", "Minecraft", "assets", "pcr", "extracted", "图片")

#: Character id (the datapack file name) -> official 3-star avatar file, without its extension.
#:
#: 105731 is Djeeta's 3-star art (105761 is the 6-star, 105711/105712 are the 1- and 2-star): the
#: 3-star is the one the party effect has shown since it was added, and the book now shows the same face
#: so "the character in the book" and "the character on the HUD" cannot be two different pictures.
AVATARS = {
    "djeeta": "icon_unit_105731",
}

#: Output edge length. See the module docstring.
SIZE = 64

#: Destination root, relative to the repository root.
OUTPUT_DIR = os.path.join("src", "main", "resources", "assets", "astrum", "textures", "gui", "portrait")


def resample(source: str) -> Image.Image:
    with Image.open(source) as image:
        avatar = image.convert("RGBA")
    if avatar.size != (128, 128):
        raise SystemExit(f"expected a 128x128 avatar, {source} is {avatar.size[0]}x{avatar.size[1]}")
    return avatar.resize((SIZE, SIZE), Image.LANCZOS)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", default=DEFAULT_SOURCE, help="the unpacked official image dir")
    parser.add_argument("--check", action="store_true",
                        help="verify the committed portraits instead of writing them")
    args = parser.parse_args(argv)

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out_dir = os.path.join(root, OUTPUT_DIR)

    if args.check:
        for character in AVATARS:
            path = os.path.join(out_dir, character + ".png")
            if not os.path.isfile(path):
                raise SystemExit(f"missing committed portrait: {path}")
            with Image.open(path) as image:
                if image.size != (SIZE, SIZE) or image.mode != "RGBA":
                    raise SystemExit(f"{path} is {image.size} {image.mode}, expected {SIZE}x{SIZE} RGBA")
        print(f"ok: {len(AVATARS)} committed portraits are {SIZE}x{SIZE} RGBA")
        return 0

    os.makedirs(out_dir, exist_ok=True)
    for character, unit in AVATARS.items():
        source = os.path.join(args.source, unit + ".png")
        if not os.path.isfile(source):
            raise SystemExit("official avatar not found: " + source + " (pass --source DIR)")
        path = os.path.join(out_dir, character + ".png")
        resample(source).save(path, format="PNG", optimize=True)
        print(f"wrote {path} from {unit}.png")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
