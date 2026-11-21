"""Copies the official <b>1★ / 3★ / 6★</b> unit avatars the book and the HUD draw per tier.

<h2>Why these exist</h2>
The human's rule: 「角色头像要跟随好感度进行变化，符合前面的1* 3* 6*的要求」 — the face a character shows
follows her <b>affection tier</b>, and the official art set ships exactly that: for every unit there is an
``icon_unit_<artId>`` per rarity, and on Djeeta the three are visibly different pictures (1★ is her
starter headband, 3★ the pink one, 6★ the bridal-style gown).

So each character gets up to three avatar files, named by tier, and ``CharacterDef.face(int tier)``
picks between them. A character whose pack names no tier art keeps the single ``avatar`` it always had —
this script only writes files a definition may point at.

<h2>Which official files, and why Djeeta's are these</h2>
``icon_unit_105711`` (1★), ``icon_unit_105731`` (3★), ``icon_unit_105761`` (6★). The 1057xx block is
Djeeta's unit; the last digit is the rarity index (1 = 1★, 3 = 3★, 6 = 6★); ``105712`` is her 2★ art,
which no tier draws because the affection ladder has three rungs and the middle one is the 3★ picture
(spec §9.4).

<h2>Which directory the pixels come from — and why it changed</h2>
**``assets/pcr/icon/unit/<artId>.webp``**, not ``assets/pcr/extracted/图片/icon_unit_<artId>.png``.

The human reported the book's tier art as wrong and named the cause himself: 「感觉2个解包输出的文件问题，
编码对不上，使用角色头像的时候用 ``assets/pcr/icon/unit``，这个路径下的头像。」 He was right, and the two
directories are not two encodings of one picture:

* ``icon/unit/*.webp`` is the game's own icon atlas — the **whole 128x128 avatar cell**, background and
  composition intact, which is the picture a player recognises as her portrait.
* ``extracted/图片/icon_unit_*.png`` is a **tight head crop with the background cut away**, produced by an
  earlier extraction pass. As art it is a *different picture*, and for at least one unit it is the wrong
  girl: ``icon_unit_105931.png`` (コッコロ 3★) is a cream-haired, blue-eyed stranger who appears in none of
  コッコロ's own stills, plates or cards, while ``icon/unit/105931.webp`` is unmistakably her. So the whole
  1059 row of the old table was reproducing a bad extraction, not a mapping error.

``tools/gen_portraits.py`` reads the same directory for the tierless ``avatar``, and
``TierAvatarSourceTest`` asserts every committed file equals a fresh resample of the source this table
names — so a revert to the extracted directory fails a test instead of shipping.

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

def _dump_root() -> str:
    """素材提取目录的根。用 ASTRUM_DUMP 指向你自己机器上的位置，默认取仓库上两级的 ``assets``。

    这里原来写的是一个绝对路径（维护者本机的盘符），既泄露了本机布局，也让脚本在别人的机器上必然失效。
    """
    return os.environ.get("ASTRUM_DUMP", os.path.join("..", ".."))


#: The game's own icon atlas: 128x128 webp, one cell per (unit, rarity). See the module docstring for why
#: this replaced ``extracted/图片``.
DEFAULT_SOURCE = os.path.join(_dump_root(), "assets", "pcr", "icon", "unit")

#: The extension of the files in :data:`DEFAULT_SOURCE`. Note it is **not** ``.png``: the icon atlas is
#: webp, and reading the png of the same stem from another directory is the bug this constant prevents.
SOURCE_SUFFIX = ".webp"

#: Prefix the art ids carry in this table but **not** in the filename. The official dump spells the files
#: ``icon_unit_105711`` in ``extracted/图片`` and ``105711`` in ``icon/unit``; the table keeps the long form
#: because that is what the dump and the code's own comments call it, and this constant is the one place
#: the two spellings are reconciled.
SOURCE_PREFIX = "icon_unit_"

#: Character id -> {tier: official ``icon_unit_<n>``}. One entry per shipped character.
#:
#: The tiers are the affection ladder's three rungs (spec §6.2), not a rarity dump: 1★ is drawn at
#: affection tier 1, 3★ at tier 2, 6★ at tier 3.
#:
#: The ids are the **unit blocks** 1057 (ジータ), 1058 (ペコリーヌ), 1059 (コッコロ) and 1060 (キャル), each
#: with its rarities as the trailing digit pair. They were re-confirmed by opening all twelve files after
#: the human suspected them: each character's three are the same girl in three outfits, and the block
#: assignment was right. His own message listing them repeated ``105931`` twice and gave コッコロ's 1★ as
#: ``105931``; the 1★ is ``105911``, and the files — not the message — are the authority.
TIER_AVATARS = {
    "djeeta": {1: "icon_unit_105711", 3: "icon_unit_105731", 6: "icon_unit_105761"},
    "kokkoro": {1: "icon_unit_105911", 3: "icon_unit_105931", 6: "icon_unit_105961"},
    "pecorine": {1: "icon_unit_105811", 3: "icon_unit_105831", 6: "icon_unit_105861"},
    "kyaru": {1: "icon_unit_106011", 3: "icon_unit_106031", 6: "icon_unit_106061"},
}

#: Output edge length. See the module docstring.
SIZE = 64

#: Destination root, relative to the repository root.
OUTPUT_DIR = os.path.join("src", "main", "resources", "assets", "astrum", "textures", "gui", "portrait")


def out_name(character: str, star: int) -> str:
    """Returns ``djeeta_1.png``-style file name for one character's one tier."""
    return f"{character}_{star}.png"


def source_path(source_dir: str, art_id: str) -> str:
    """Returns the official avatar file this table names, in ``source_dir``.

    The table spells the art id the way the official dump names it (``icon_unit_105711``); the icon atlas
    stores it bare (``105711.webp``). Both spellings go through :data:`SOURCE_PREFIX` here so nothing else
    has to know which directory uses which.
    """
    stem = art_id[len(SOURCE_PREFIX):] if art_id.startswith(SOURCE_PREFIX) else art_id
    return os.path.join(source_dir, stem + SOURCE_SUFFIX)


def resample(source: str) -> Image.Image:
    with Image.open(source) as image:
        avatar = image.convert("RGBA")
    if avatar.size != (128, 128):
        raise SystemExit(f"expected a 128x128 avatar, {source} is {avatar.size[0]}x{avatar.size[1]}")
    return avatar.resize((SIZE, SIZE), Image.LANCZOS)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", default=DEFAULT_SOURCE,
                        help="the official icon/unit directory (webp)")
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
            source = source_path(args.source, art_id)
            if not os.path.isfile(source):
                raise SystemExit("official avatar not found: " + source + " (pass --source DIR)")
            path = os.path.join(out_dir, out_name(character, star))
            resample(source).save(path, format="PNG", optimize=True)
            print(f"wrote {path} from {os.path.basename(source)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
