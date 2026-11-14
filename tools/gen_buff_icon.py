"""Derives the <b>self-buff</b> icon from one official source, for both surfaces that show it.

<h2>The human's rule, in his words</h2>
「这种自buff提升，统一使用之前决定的自buff图标」 — every <em>self-buff</em> effect shares the self-buff icon
that was already chosen for the 自buff category: the official ``icon_skill_3015``. So ``attack_buff`` and
``stack`` show the <b>same picture</b>, and that is intended, not a collision. The earlier attempt gave
``attack_buff`` its own ``caravan_icon_buff_*`` badge; the human rejected it (「那个攻击提升的图标不对，应该显示的
是buff图标」), and this script is the correction.

<h2>Why 3015</h2>
3015 is the 自buff row of the official category table the human gave, and it is what ``stack`` already uses
(``tools/gen_skill_icons.py``). One category, one picture: a player who learned "this is what a self-buff
looks like" on one row must not have to learn a second picture for the next row.

<h2>Two outputs, one source, one script</h2>
The two surfaces are different textures at different resolutions, so they cannot literally share one file:

* the <b>book's hook row</b> draws ``assets/astrum/textures/gui/skill/attack_buff.png`` at 64x64 through
  ``SkillIcons.textureFor`` — the same tree and the same size as the eight skill icons beside it;
* the <b>HUD effect icon</b> resolves ``assets/astrum/textures/mob_effect/attack_buff.png``, because that is
  the path vanilla's ``MobEffectTextureManager`` asks for: ``textures/mob_effect/<effect path>.png``, indexed
  by ``assets/minecraft/atlases/mob_effects.json``. Its absence is what the human saw as a purple/magenta
  checkerboard (Minecraft's missing-texture sprite). Vanilla's own files there are <b>18x18</b>, so this one
  is too.

Both are generated here from ``icon_skill_3015.png`` and nothing else, so the two surfaces cannot drift:
a re-pick is one constant, and ``--check`` proves both committed files are byte-identical to a fresh
resample. Byte-identical to each other too, at 64x64 — ``SkillIconsTest`` asserts that, which is what makes
"the two self-buffs share one picture" a fact rather than a comment.

Run:

    python tools/gen_buff_icon.py [--source DIR] [--check]
"""

from __future__ import annotations

import argparse
import os
import sys

from PIL import Image

#: The unpacked official PCR art; not part of the repository.
DEFAULT_SOURCE = os.path.join("K:\\", "AI", "Minecraft", "assets", "pcr", "extracted", "图片")

#: The official <b>自buff</b> icon every self-buff effect is drawn from. One source, by the human's rule.
#: It is also ``tools/gen_skill_icons.py``'s number for ``stack``; the two must stay equal.
SELF_BUFF = 3015

#: The effect this module owns a derived file for. ``stack`` gets its copy from ``gen_skill_icons.py``.
EFFECT = "attack_buff"

#: Edge of the book's icon: the same 64x64 the eight skill icons beside it are committed at.
BOOK_SIZE = 64

#: Edge of the HUD's icon. Not a guess: vanilla's own ``assets/minecraft/textures/mob_effect/*.png`` are
#: 18x18 (``strength.png`` and ``health_boost.png`` both are), and that is the sprite the effect HUD blits.
HUD_SIZE = 18

#: Destination directories, relative to the repository root: the two surfaces' trees.
BOOK_DIR = os.path.join("src", "main", "resources", "assets", "astrum", "textures", "gui", "skill")
HUD_DIR = os.path.join("src", "main", "resources", "assets", "astrum", "textures", "mob_effect")


def load(source_dir: str) -> Image.Image:
    """Loads the official 128x128 self-buff icon, refusing anything else."""
    path = os.path.join(source_dir, "icon_skill_%d.png" % SELF_BUFF)
    if not os.path.isfile(path):
        raise SystemExit("official self-buff icon not found: " + path + " (pass --source DIR)")
    with Image.open(path) as loaded:
        image = loaded.convert("RGBA")
    if image.size != (128, 128):
        raise SystemExit(f"expected a 128x128 skill icon, {path} is {image.size[0]}x{image.size[1]}")
    return image


def resample(image: Image.Image, size: int) -> Image.Image:
    """Downscales with LANCZOS, like the skill icons: the badge has a thin frame NEAREST would eat."""
    return image.resize((size, size), Image.LANCZOS)


def targets(root: str) -> list[tuple[str, int]]:
    """{@return the two (path, size) pairs this script owns}"""
    return [
        (os.path.join(root, BOOK_DIR, EFFECT + ".png"), BOOK_SIZE),
        (os.path.join(root, HUD_DIR, EFFECT + ".png"), HUD_SIZE),
    ]


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", default=DEFAULT_SOURCE, help="the unpacked official image dir")
    parser.add_argument("--check", action="store_true",
                        help="verify the committed icons instead of writing them")
    args = parser.parse_args(argv)

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    source = load(args.source)

    if args.check:
        for path, size in targets(root):
            if not os.path.isfile(path):
                raise SystemExit("missing committed icon: " + path)
            wanted = resample(source, size)
            with Image.open(path) as image:
                committed = image.convert("RGBA")
                actual = image.size
            if actual != (size, size):
                raise SystemExit(f"{path} is {actual[0]}x{actual[1]}, expected {size}x{size}")
            if committed.tobytes() != wanted.tobytes():
                raise SystemExit(f"{path} is not icon_skill_{SELF_BUFF}.png at {size}x{size} "
                                 "(run without --check to rewrite it)")
        print(f"ok: {EFFECT} is icon_skill_{SELF_BUFF} at {BOOK_SIZE}x{BOOK_SIZE} (book) and "
              f"{HUD_SIZE}x{HUD_SIZE} (HUD)")
        return 0

    for path, size in targets(root):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        resample(source, size).save(path, format="PNG", optimize=True)
        print(f"wrote {path} from icon_skill_{SELF_BUFF}.png ({os.path.getsize(path)} B)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
