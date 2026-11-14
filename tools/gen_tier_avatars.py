"""Copies the official <b>1★ / 3★ / 6★</b> unit avatars the book and the HUD draw per tier.

<h2>Why these exist</h2>
The human's rule: 「角色头像要跟随好感度进行变化，符合前面的1* 3* 6*的要求」 — the face a character shows
follows her <b>affection tier</b>, and the official art set ships exactly that: for every unit there is a
``icon_unit_<artId>.png`` per rarity, and on Djeeta the three are visibly different pictures (1★ is her
starter white dress, 3★ the pink outfit, 6★ the bridal-style white gown).

So each character gets up to three avatar files, named by tier, and ``CharacterDef.face(int tier)``
picks between them. A character whose pack names no tier art keeps the single ``avatar`` it always had —
this script only writes files a definition may point at.

<h2>Which official files, and why Djeeta's are these</h2>
``icon_unit_105711`` (1★), ``icon_unit_105731`` (3★), ``icon_unit_105761`` (6★). The 1057xx block is
Djeeta's unit; the last digit is the rarity index (1 = 1★, 3 = 3★, 6 = 6★); ``105712`` is her 2★ art,
which no tier draws because the affection ladder has three rungs and the middle one is the 3★ picture
(spec §9.4).

<h2>Why 64x64</h2>
The official avatars are 128x128. The largest draw site is the book's roster cell portrait at 40 px (the
comfortable tier) and the party effect's HUD icon at 18 px, so 64 is comfortably ahead of every draw and
a quarter of the bytes. LANCZOS, like ``gen_portraits.py``: this is a face.

Run:

    python tools/gen_tier_avatars.py [--source DIR] [--check]

``--check`` re-reads the committed files and fails when one is not 64x64 RGBA, or when two tiers of one
character are the same bytes (which would mean the tier rule is undetectable in game).
"""

from __future__ import annotations

import argparse
import os
import sys

from PIL import Image

#: The unpacked official PCR art; not part of the repository.
DEFAULT_SOURCE = os.path.join("K:\\", "AI", "Minecraft", "assets", "pcr", "extracted", "图片")

#: Character id -> {tier: official ``icon_unit_<n>``}. One entry per shipped character.
#:
#: The tiers are the affection ladder's three rungs (spec §6.2), not a rarity dump: 1★ is drawn at
#: affection tier 1, 3★ at tier 2, 6★ at tier 3.
TIER_AVATARS = {
    "djeeta": {1: "icon_unit_105711", 3: "icon_unit_105731", 6: "icon_unit_105761"},
}

#: Output edge length. See the module docstring.
SIZE = 64

#: Destination root, relative to the repository root.
OUTPUT_DIR = os.path.join("src", "main", "resources", "assets", "astrum", "textures", "gui", "portrait")


def out_name(character: str, star: int) -> str:
    """Returns ``djeeta_1.png``-style file name for one character's one tier."""
    return f"{character}_{star}.png"


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
                        help="verify the committed tier avatars instead of writing them")
    args = parser.parse_args(argv)

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out_dir = os.path.join(root, OUTPUT_DIR)

    if args.check:
        for character, tiers in TIER_AVATARS.items():
            digests = {}
            for star in tiers:
                path = os.path.join(out_dir, out_name(character, star))
                if not os.path.isfile(path):
                    raise SystemExit("missing committed tier avatar: " + path)
                with Image.open(path) as image:
                    avatar = image.convert("RGBA")
                if avatar.size != (SIZE, SIZE):
                    raise SystemExit(f"{path} is {avatar.size}, expected {SIZE}x{SIZE}")
                digests[star] = avatar.tobytes()
            if len(set(digests.values())) != len(digests):
                raise SystemExit(f"{character}: two tiers are the same picture — the tier rule would "
                                 "be invisible in game")
        print(f"ok: {sum(len(t) for t in TIER_AVATARS.values())} tier avatars are {SIZE}x{SIZE} RGBA "
              "and every tier of a character differs")
        return 0

    os.makedirs(out_dir, exist_ok=True)
    for character, tiers in TIER_AVATARS.items():
        for star, art_id in sorted(tiers.items()):
            source = os.path.join(args.source, art_id + ".png")
            if not os.path.isfile(source):
                raise SystemExit("official avatar not found: " + source + " (pass --source DIR)")
            path = os.path.join(out_dir, out_name(character, star))
            resample(source).save(path, format="PNG", optimize=True)
            print(f"wrote {path} from {art_id}.png")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
