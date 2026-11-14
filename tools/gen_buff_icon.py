"""Copies the official <b>buff</b> icon the book and the effect row draw for 攻击提升.

<h2>What the human said</h2>
「获得的攻击提升图标就使用提供的buff图标」 — the gained-attack-buff row must use one of the official
``caravan_icon_buff_*`` icons, not ``icon_skill_3010``.

<h2>Which one, and why</h2>
``caravan_icon_buff_00005``. The 107 ``caravan_icon_buff_*`` files split into two families, and only one of
them is usable here:

* ``caravan_icon_buff_100101`` … ``140301`` (96 files) are <b>character portraits in a gold ring</b> — one per
  unit, a face. Those are banners for "this character joined", not buffs.
* ``caravan_icon_buff_00001`` … ``00011`` (11 files) are actual <b>status-effect badges</b>: dice, a pause
  clock, a gold star burst, a shop tag, a pair of fists, coloured cards.

Among the eleven, the ones that read as "attack up" are the two that carry a percentage:

* ``00003`` — gold star burst, ``+100%``;
* ``00005`` — <b>two raised fists</b>, ``+100%``;
* ``00007`` — gold star burst, ``+200%``.

``00005`` is the pick: a fist is the one glyph in the set that means <em>attack</em> rather than
<em>generic reward</em>, and the two fists read at 9 pixels (the height of a book hook row) where a burst's
star points turn to mush. ``00007``'s bigger number would also lie: the shipped Djeeta hook is
``multiplier 1.1`` and her UB buff is ``1.5``, not 200% or 300%, while ``+100%`` is the neutral "boosted"
reading. ``00005`` is also pixel-for-pixel the same badge shape as ``00003``/``00007``, so if the human prefers
a burst, swapping the id here is the whole change.

<h2>Where it is drawn</h2>
Two places, both through ``SkillIcons.textureFor(EffectType.ATTACK_BUFF)``: the book's skill row (9x9) and the
party-effect / HUD name row. Both read the same texture, so a re-pick is one constant.

<h2>Why 64x64</h2>
Same as the skill icons: the official files are 64x64 and the largest draw is 9 pixels, so the file is
committed at its natural size rather than downscaled — ``00005`` is a badge with a 1-pixel rim and text under
it, and resampling it would blur both.

Run:

    python tools/gen_buff_icon.py [--source DIR] [--check]

``--check`` proves the committed file is byte-identical to the official ``caravan_icon_buff_00005.png``.
"""

from __future__ import annotations

import argparse
import os
import sys

from PIL import Image

#: The unpacked official PCR art; not part of the repository.
DEFAULT_SOURCE = os.path.join("K:\\", "AI", "Minecraft", "assets", "pcr", "extracted", "图片")

#: The official status-badge file the attack buff is drawn from. See the module docstring for the choice.
BUFF = "caravan_icon_buff_00005"

#: The effect type this icon belongs to: the file the drawing code looks up.
EFFECT = "attack_buff"

#: Destination, relative to the repository root. The official size is kept, so no resampling happens.
OUTPUT_DIR = os.path.join("src", "main", "resources", "assets", "astrum", "textures", "gui", "skill")


def load(source_dir: str) -> Image.Image:
    """Loads the official badge, refusing anything that is not the 64x64 the drawing code assumes."""
    path = os.path.join(source_dir, BUFF + ".png")
    if not os.path.isfile(path):
        raise SystemExit("official buff icon not found: " + path + " (pass --source DIR)")
    with Image.open(path) as loaded:
        image = loaded.convert("RGBA")
    if image.size != (64, 64):
        raise SystemExit(f"expected a 64x64 badge, {path} is {image.size[0]}x{image.size[1]}")
    return image


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", default=DEFAULT_SOURCE, help="the unpacked official image dir")
    parser.add_argument("--check", action="store_true",
                        help="verify the committed icon instead of writing it")
    args = parser.parse_args(argv)

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out_dir = os.path.join(root, OUTPUT_DIR)
    path = os.path.join(out_dir, EFFECT + ".png")

    if args.check:
        wanted = load(args.source)
        if not os.path.isfile(path):
            raise SystemExit("missing committed icon: " + path)
        with Image.open(path) as image:
            committed = image.convert("RGBA")
        if committed.size != wanted.size:
            raise SystemExit(f"{path} is {committed.size}, expected {wanted.size}")
        if committed.tobytes() != wanted.tobytes():
            raise SystemExit(f"{path} is not {BUFF}.png (run without --check to rewrite it)")
        print(f"ok: {EFFECT}.png is {BUFF}.png, {committed.size[0]}x{committed.size[1]}")
        return 0

    os.makedirs(out_dir, exist_ok=True)
    load(args.source).save(path, format="PNG", optimize=True)
    print(f"wrote {path} from {BUFF}.png ({os.path.getsize(path)} B)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
