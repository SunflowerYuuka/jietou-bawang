"""Derives the <b>attack buff</b> icon from one official source, for both surfaces that show it.

<h2>Which official icon, in the human's words</h2>
「你用的3015/2012/1012，我要的是3010/2003/1003。」 — he named three of the sixty-one official
``icon_skill_*`` files and picked the first of each family. For ``attack_buff`` the answer is
``icon_skill_3010``: a figure engulfed in golden flames inside a gold frame, the official 攻击提升
picture. The previously shipped ``3015`` is a figure on a podium under a spotlight — a perfectly good
icon, and the wrong one for this effect.

<h2>Why ``attack_buff`` and ``stack`` are two pictures now</h2>
An earlier revision gave both effects ``3015`` on a "every self-buff shares one picture" rule. That rule
was misfiled: ``stack`` is <b>not a self-buff</b>. It is a per-target layer counter —
``StackKernel``/``StackState``, spec §4.4 — that adds a layer to the <em>victim</em> and is consumed by
``detonate``; its target is the enemy, not the caster. So there was never a category for the two to
belong to together, and the two files are now <b>different bytes from different official sources</b>.
``SkillIconsTest`` asserts that they differ, which is the inverse of what it asserted before.

<h2>Two outputs, one source, one script</h2>
The two surfaces are different textures at different resolutions, so they cannot literally share one file:

* the <b>book's hook row</b> draws ``assets/astrum/textures/gui/skill/attack_buff.png`` at 64x64 through
  ``SkillIcons.textureFor`` — the same tree and the same size as the eight skill icons beside it;
* the <b>HUD effect icon</b> resolves ``assets/astrum/textures/mob_effect/attack_buff.png``, because that is
  the path vanilla's ``MobEffectTextureManager`` asks for: ``textures/mob_effect/<effect path>.png``, indexed
  by ``assets/minecraft/atlases/mob_effects.json``. Its absence is what the human saw as a purple/magenta
  checkerboard (Minecraft's missing-texture sprite).

<h2>The HUD sprite is 64x64, and that is a measured decision</h2>
The human asked for a sharper effect icon (「药水效果栏的图标，分辨率有点低了」). The pipeline decides how far
that can go, so it was read rather than assumed:

* ``MobEffectTextureManager`` extends ``TextureAtlasHolder`` and stitches through ``SpriteLoader`` with
  ``mipLevel = 0``. ``SpriteLoader.stitch`` builds one ``Stitcher`` from ``maxSupportedTextureSize`` — it
  imposes <b>no per-sprite size</b> and no power-of-two requirement, and scales nothing. A sprite of any
  size is packed as-is, so a larger source really does carry more detail into the atlas.
* The HUD still draws it at a <b>fixed 18x18 GUI pixels</b>
  (``Gui.renderEffects`` → ``Graphcis.blit(x+3, y+3, 0, 18, 18, sprite)``), so a bigger file does
  <em>not</em> make the icon bigger. What it does is give the sampler more pixels to average, which is
  where the sharpness comes from.
* ``TextureAtlasHolder.apply`` calls only ``TextureAtlas.upload``; ``upload`` never calls
  ``TextureAtlas.updateFilter``, so the atlas keeps ``AbstractTexture``'s default
  ``blur=false, mipmap=false`` — GL ``NEAREST`` for both minification and magnification. Under
  ``NEAREST`` an 18x18 source is already a 1:1 blit, so 18x18 was <b>not</b> the ceiling; a larger sprite
  is box-filtered by the GPU down to 18x18, which is exactly the "more detail survives" the human asked
  for.

So ``HUD_SIZE`` is 64 — the same source pixels as the book copy, so the two surfaces cannot disagree about
which art the effect wears, and the HUD's 18x18 footprint averages 8x8 blocks of official art instead of
1x1. ``18`` was not enforced by anything; it was a match to vanilla's *files*, and matching a file size is
not a reason to throw away 64px of detail.

Both outputs are generated here from ``icon_skill_3010.png`` and nothing else, so a re-pick is one
constant, and ``--check`` proves both committed files are byte-identical to a fresh resample.

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

#: The official icon ``attack_buff`` is drawn from: 「我要的是3010/2003/1003」 — the 攻击提升 picture, a figure
#: engulfed in golden flames in a gold frame. Not ``3015``, which ``stack`` uses and which is a different
#: effect (a per-target layer counter, spec §4.4) rather than a self-buff.
ATTACK_BUFF = 3010

#: The effect this module owns a derived file for. ``stack`` gets its copy from ``gen_skill_icons.py``.
EFFECT = "attack_buff"

#: Edge of the book's icon: the same 64x64 the eight skill icons beside it are committed at.
BOOK_SIZE = 64

#: Edge of the HUD's icon. 64 rather than vanilla's 18: the ``mob_effects`` atlas honours any sprite size
#: (``SpriteLoader`` imposes none), the HUD's 18x18 blit is fixed, and the atlas is unfiltered
#: (``TextureAtlasHolder.apply`` never calls ``updateFilter``, so GL ``NEAREST`` box-filters 64→18 on the
#: GPU), which is strictly more detail than an 18x18 source can carry. See the module docstring.
HUD_SIZE = 64

#: Destination directories, relative to the repository root: the two surfaces' trees.
BOOK_DIR = os.path.join("src", "main", "resources", "assets", "astrum", "textures", "gui", "skill")
HUD_DIR = os.path.join("src", "main", "resources", "assets", "astrum", "textures", "mob_effect")


def load(source_dir: str) -> Image.Image:
    """Loads the official 128x128 attack-buff icon, refusing anything else."""
    path = os.path.join(source_dir, "icon_skill_%d.png" % ATTACK_BUFF)
    if not os.path.isfile(path):
        raise SystemExit("official attack-buff icon not found: " + path + " (pass --source DIR)")
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
                raise SystemExit(f"{path} is not icon_skill_{ATTACK_BUFF}.png at {size}x{size} "
                                 "(run without --check to rewrite it)")
        print(f"ok: {EFFECT} is icon_skill_{ATTACK_BUFF} at {BOOK_SIZE}x{BOOK_SIZE} (book) and "
              f"{HUD_SIZE}x{HUD_SIZE} (HUD)")
        return 0

    for path, size in targets(root):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        resample(source, size).save(path, format="PNG", optimize=True)
        print(f"wrote {path} from icon_skill_{ATTACK_BUFF}.png ({os.path.getsize(path)} B)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
