"""Copies the eight official PCR skill icons the book uses, at 64x64, into the mod.

The character book lists a character's effect hooks (one row per hook). Rows are tiny, so each row
needs a picture as well as words: the 61 official skill icons at
``K:\\AI\\Minecraft\\assets\\pcr\\extracted\\图片\\icon_skill_<n>.png`` are 128x128 gold-framed art,
and exactly eight of them are shipped under ``assets/astrum/textures/gui/skill/<effect type>.png``.
Nothing else is vendored: the other 53 stay where they are.

The mapping was chosen by looking at all 61 icons in a labelled contact sheet (not by guessing from
file names) and is pinned in ``impl/client/SkillIcons.java``; this script must agree with it or it
would write a texture the code never draws. Run:

    python tools/gen_skill_icons.py [--source DIR] [--check]

``--check`` verifies the committed files (size, RGBA, byte-identical to a fresh resample) instead of
writing them.
"""

from __future__ import annotations

import argparse
import os
import sys

from PIL import Image

#: The unpacked official PCR art; not part of the repository.
DEFAULT_SOURCE = os.path.join("K:\\", "AI", "Minecraft", "assets", "pcr", "extracted", "图片")

#: ``EffectType`` (api.v1, lower-case name) -> official ``icon_skill_<n>``.
#:
#: This is the human's <b>official category</b> assignment, not a guess from shapes. The 61 official
#: icons are organised in families by their first digit, and the row states which family the effect
#: belongs to; the earlier table invented a reason per picture and got several of them wrong, which is
#: what the review caught. Keep the two columns in step — ``SkillIconsTest`` asserts this dict and
#: ``impl/client/SkillIcons`` carry the same pairs.
#:
#:   1001  物理攻击 (physical attack)   -> extra_damage
#:   2005  回血     (healing)           -> lifesteal
#:   3013  破甲     (armour break)      -> splash
#:   3014  充能     (charge)            -> gain_charge
#:   2006  充能     (charge)            -> area_charge
#:   3015  自buff   (self buff)         -> stack
#:   1023  灼烧     (burn)              -> detonate
#:   1022  中毒     (poison)            -> apply_effect
#:   3010  自buff   (self buff, attack) -> attack_buff
ICONS = {
    "extra_damage": 1001,
    "lifesteal": 2005,
    "splash": 3013,
    "gain_charge": 3014,
    "area_charge": 2006,
    "stack": 3015,
    "detonate": 1023,
    "apply_effect": 1022,
    "attack_buff": 3010,
}

#: Output size. 64 keeps the gold frame and the art's shapes readable when the book draws a row icon;
#: 32 would blur the frame into the picture.
SIZE = 64

#: Destination, relative to the repository root.
OUTPUT_DIR = os.path.join("src", "main", "resources", "assets", "astrum", "textures", "gui", "skill")


def resample(source: str) -> Image.Image:
    with Image.open(source) as image:
        icon = image.convert("RGBA")
    if icon.size != (128, 128):
        raise SystemExit(f"expected a 128x128 skill icon, {source} is {icon.size[0]}x{icon.size[1]}")
    # LANCZOS, unlike the card: a skill icon is a picture with a thin gold frame, and NEAREST would
    # eat the frame's 1-pixel lines at 64. The card is a face, where hard edges read better.
    return icon.resize((SIZE, SIZE), Image.LANCZOS)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", default=DEFAULT_SOURCE, help="the unpacked official image dir")
    parser.add_argument("--check", action="store_true",
                        help="verify the committed icons instead of writing them")
    args = parser.parse_args(argv)

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out_dir = os.path.join(root, OUTPUT_DIR)
    expected = {name: os.path.join(out_dir, name + ".png") for name in ICONS}

    available = [name for name in os.listdir(args.source) if name.startswith("icon_skill_")]
    if not available:
        raise SystemExit("no icon_skill_*.png under " + args.source)

    if args.check:
        for effect, number in ICONS.items():
            path = expected[effect]
            wanted = resample(os.path.join(args.source, "icon_skill_%d.png" % number))
            with Image.open(path) as image:
                committed = image.convert("RGBA")
            if image.size != (SIZE, SIZE):
                raise SystemExit(f"{path} is {image.size}, expected {SIZE}x{SIZE}")
            if committed.tobytes() != wanted.tobytes():
                raise SystemExit(f"{path} does not match icon_skill_{number}.png at {SIZE}x{SIZE}")
        print("ok: %d skill icons match their official sources" % len(ICONS))
        return 0

    os.makedirs(out_dir, exist_ok=True)
    for effect, number in ICONS.items():
        source = os.path.join(args.source, "icon_skill_%d.png" % number)
        image = resample(source)
        path = expected[effect]
        image.save(path, format="PNG", optimize=True)
        print("wrote %s from icon_skill_%d.png" % (path, number))
    print("%d of %d official skill icons are shipped; the rest are not copied"
          % (len(ICONS), len(available)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))