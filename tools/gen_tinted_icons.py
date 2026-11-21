"""Derives the <b>tinted</b> effect icons — one official picture, a border colour per effect.

<h2>What the human decided</h2>
His final rule is two pictures for the buffs:

* <b>every attack-up effect</b> wears ``icon_skill_3001``, the raised clenched fist — ``attack_buff`` and
  ``buff_attack_area``, byte-identical, no border. ``tools/gen_buff_icon.py`` writes the former;
* <b>every other self-buff</b> wears ``icon_skill_3010``, the figure engulfed in golden flames, and is
  told apart <b>only by a border colour</b>: physical defence green, magical defence violet, haste gold,
  charge rate blue. That is this script.

The two heals he also wanted on one picture (「自回血跟群体回血用同一个图标」, ``2005``); they keep the
<b>rose</b> and <b>teal</b> borders they already had, so the six tinted effects are three pictures plus a
colour each. Red, the code an earlier revision named for attack, is <b>no longer used</b>: attack effects
carry no border.

<h2>What this script draws, and where</h2>
For each tinted type: the official source resampled to 64x64, plus a <b>3-pixel border</b> of the
effect's colour inset one pixel from the edge, with the outermost pixel left as the art's own dark outline
so the border never fights the frame. The book copy goes to
``assets/astrum/textures/gui/skill/<type>.png`` — the file ``SkillIcons.textureFor`` blits.

The HUD copy goes to ``assets/astrum/textures/mob_effect/<registry path>.png`` and <b>the name is the
registry path, not the type name</b>: vanilla asks the ``mob_effects`` atlas for the effect's registry id,
so ``buff_phys_defence_area``'s HUD sprite is ``phys_defence.png``. Writing it under the type name (what
this script did before) produced files no client ever samples, and left the real sprite showing whatever
another script last wrote. The two heals have <b>no HUD copy at all</b>: they are instantaneous, hold no
``MobEffectInstance``, and never appear on the effect bar — ``SkillIcons.hudPathOf`` is what says so, and
it is read out of the Java rather than duplicated here.

The two surfaces that do exist must carry the same picture *and the same border*: a player reading the
book and a player glancing at the effect bar are asking the same question.

<h2>Why the colours live in Java, not here</h2>
``SkillIcons.TINT_RGB`` is the table; this script reads it out of the Java source, as it does
``SOURCE_ID`` and ``HUD_PATH``. That is deliberate and it is the opposite of the usual direction
(generators own the art table). The reason: the border colour is a <em>rule about what an effect is</em> —
the same kind of fact as "which picture" — and the drawing code, the lang-side distinction and the tests
all need it at runtime. Duplicating the six hex values in Python would be a second copy of a rule, which
this codebase forbids. ``TintedIconTest`` asserts the two agree by re-deriving the border from the
committed files.

Run:

    python tools/gen_tinted_icons.py [--source DIR] [--check]

``--check`` re-derives every committed file and fails on any difference, so a hand-edited PNG is caught.
"""

from __future__ import annotations

import argparse
import os
import re
import sys

from PIL import Image, ImageDraw

def _dump_root() -> str:
    """素材提取目录的根。用 ASTRUM_DUMP 指向你自己机器上的位置，默认取仓库上两级的 ``assets``。

    这里原来写的是一个绝对路径（维护者本机的盘符），既泄露了本机布局，也让脚本在别人的机器上必然失效。
    """
    return os.environ.get("ASTRUM_DUMP", os.path.join("..", ".."))


#: The unpacked official skill icons; not part of the repository.
DEFAULT_SOURCE = os.path.join(_dump_root(), "assets", "pcr", "extracted", "图片")

#: The Java tables this script's numbers come from. See the module docstring for why they are read rather
#: than duplicated.
TINT_TABLE = os.path.join("src", "main", "java", "com", "astrum", "pcrd", "impl", "client",
                          "SkillIcons.java")

#: The same file holds ``SOURCE_ID`` (the base art) and ``HUD_PATH`` (the registry path per type).
SOURCE_TABLE = TINT_TABLE

#: Edge of both committed files: the same 64 as ``gen_skill_icons.py`` and ``gen_effect_icons.py``.
SIZE = 64

#: The two output trees. Both are written for a registered effect; only the book copy exists for the
#: instantaneous ones. See the module docstring.
BOOK_DIR = os.path.join("src", "main", "resources", "assets", "astrum", "textures", "gui", "skill")
HUD_DIR = os.path.join("src", "main", "resources", "assets", "astrum", "textures", "mob_effect")

#: Thickness of the border stroke, in pixels at :data:`SIZE`.
STROKE = 3

#: Pixels of the art's own frame kept outside the border, so the border sits inside the gold frame instead
#: of covering it.
OUTER_MARGIN = 1


def parse_tints(java: str) -> dict[str, int]:
    """Returns ``EFFECT_TYPE -> 0xRRGGBB`` from ``SkillIcons.TINT_RGB``."""
    block = re.search(r"TINT_RGB\s*=\s*java\.util\.Map\.ofEntries\((.*?)\);", java, re.DOTALL)
    if not block:
        raise SystemExit("TINT_RGB not found in " + TINT_TABLE)
    # Drop // comments so a commented-out row is not parsed.
    body = re.sub(r"//[^\n]*", "", block.group(1))
    pairs = re.findall(r"Map\.entry\(EffectType\.([A-Z_]+)\s*,\s*(0x[0-9A-Fa-f]{6})\s*\)", body)
    if not pairs:
        raise SystemExit("TINT_RGB parsed to nothing — the parser and the Java disagree")
    return {name: int(value, 16) for name, value in pairs}


def parse_sources(java: str) -> dict[str, int]:
    """Returns ``EFFECT_TYPE -> icon_skill_<n>`` from ``SkillIcons.SOURCE_ID``."""
    block = re.search(r"SOURCE_ID\s*=\s*java\.util\.Map\.ofEntries\((.*?)\);", java, re.DOTALL)
    if not block:
        raise SystemExit("SOURCE_ID not found in " + SOURCE_TABLE)
    body = re.sub(r"//[^\n]*", "", block.group(1))
    pairs = re.findall(r"Map\.entry\(EffectType\.([A-Z_]+)\s*,\s*(\d+)\s*\)", body)
    if not pairs:
        raise SystemExit("SOURCE_ID parsed to nothing — the parser and the Java disagree")
    return {name: int(value) for name, value in pairs}


def parse_hud_paths(java: str) -> dict[str, str]:
    """Returns ``EFFECT_TYPE -> registry path`` from ``SkillIcons.HUD_PATH``.

    Only the types in that table are registered effects, so it is also the answer to "does this effect have
    a HUD sprite at all" — the two heals do not.
    """
    block = re.search(r"HUD_PATH\s*=\s*java\.util\.Map\.ofEntries\((.*?)\);", java, re.DOTALL)
    if not block:
        raise SystemExit("HUD_PATH not found in " + SOURCE_TABLE
                         + " — the parser and the Java disagree")
    body = re.sub(r"//[^\n]*", "", block.group(1))
    pairs = re.findall(r'Map\.entry\(EffectType\.([A-Z_]+)\s*,\s*"([a-z_]+)"\s*\)', body)
    if not pairs:
        raise SystemExit("HUD_PATH parsed to nothing — the parser and the Java disagree")
    return dict(pairs)


def resample(source: str) -> Image.Image:
    """Loads one official 128x128 skill icon and downscales it to :data:`SIZE`."""
    with Image.open(source) as image:
        icon = image.convert("RGBA")
    if icon.size != (128, 128):
        raise SystemExit(f"expected a 128x128 skill icon, {source} is {icon.size[0]}x{icon.size[1]}")
    # LANCZOS, the same filter every other icon generator here uses: these pictures carry a thin gold
    # frame, and NEAREST eats it at 64.
    return icon.resize((SIZE, SIZE), Image.LANCZOS)


def bordered(art: Image.Image, rgb: int) -> Image.Image:
    """Returns ``art`` with a :data:`STROKE`-pixel border of ``rgb`` inset from its edge.

    The border is drawn as a rectangle outline, opaque, so it reads on any art and cannot be mistaken for
    part of the picture. It sits inside a one-pixel margin because the official icons all have their own
    frame there, and a border painted over it would make the icon look like a different art style rather
    than the same art with a colour code.
    """
    out = art.copy()
    draw = ImageDraw.Draw(out)
    inset = OUTER_MARGIN
    draw.rectangle(
        [inset, inset, SIZE - 1 - inset, SIZE - 1 - inset],
        outline=(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, 255),
        width=STROKE,
    )
    return out


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", default=DEFAULT_SOURCE, help="the unpacked official image dir")
    parser.add_argument("--check", action="store_true",
                        help="verify the committed files instead of writing them")
    args = parser.parse_args(argv)

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    with open(os.path.join(root, TINT_TABLE), encoding="utf-8") as handle:
        java = handle.read()
    tints = parse_tints(java)
    sources = parse_sources(java)
    hud_paths = parse_hud_paths(java)

    missing = sorted(name for name in tints if name not in sources)
    if missing:
        raise SystemExit("tinted effect types with no SOURCE_ID entry: " + ", ".join(missing))

    def wanted(effect: str) -> Image.Image:
        number = sources[effect]
        path = os.path.join(args.source, "icon_skill_%d.png" % number)
        if not os.path.isfile(path):
            raise SystemExit("official skill icon not found: " + path + " (pass --source DIR)")
        return bordered(resample(path), tints[effect])

    def outputs(effect: str) -> list[tuple[str, Image.Image]]:
        """Returns the (path, image) pairs this effect owns: the book copy, and the HUD copy if registered."""
        image = wanted(effect)
        pairs = [(os.path.join(BOOK_DIR, effect.lower() + ".png"), image)]
        hud_name = hud_paths.get(effect)
        if hud_name is not None:
            pairs.append((os.path.join(HUD_DIR, hud_name + ".png"), image))
        return pairs

    if args.check:
        for effect in sorted(tints):
            for relative, expected in outputs(effect):
                path = os.path.join(root, relative)
                if not os.path.isfile(path):
                    raise SystemExit("missing committed tinted icon: " + path)
                with Image.open(path) as image:
                    committed = image.convert("RGBA")
                    actual = image.size
                if actual != (SIZE, SIZE):
                    raise SystemExit(f"{path} is {actual}, expected {SIZE}x{SIZE}")
                if committed.tobytes() != expected.tobytes():
                    raise SystemExit(f"{path} is not icon_skill_{sources[effect]} with a "
                                     f"#{tints[effect]:06X} border (run without --check to rewrite it)")
        print("ok: %d tinted effects, %d book copies and %d HUD copies, %dx%d, borders match "
              "SkillIcons.TINT_RGB" % (len(tints), len(tints),
                                       len([e for e in tints if e in hud_paths]), SIZE, SIZE))
        return 0

    for effect in sorted(tints):
        for relative, image in outputs(effect):
            path = os.path.join(root, relative)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            image.save(path, format="PNG", optimize=True)
            print("wrote %s from icon_skill_%d.png, border #%06X"
                  % (path, sources[effect], tints[effect]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
