"""Derives every mechanic effect's HUD sprite from its official ``icon_skill_<n>.png``, at 64x64.

<h2>Why a script and not hand-made files</h2>
Vanilla's effect HUD resolves a ``MobEffect``'s sprite from
``assets/astrum/textures/mob_effect/<effect path>.png``, indexed by
``assets/minecraft/atlases/mob_effects.json`` (a plain directory source over ``mob_effect``). A missing
file there is not "no icon" — it is Minecraft's purple/magenta missing-texture checkerboard, which the
human photographed twice. So every registered mechanic effect needs a file, the file has to be the art
that effect's book row wears, and both facts have to be checkable without launching a client. Deriving
them here from the same official source the book icon uses makes "the HUD picture and the book picture
are one picture" a property of the pipeline rather than a promise.

<h2>Why 64x64 is the committed size</h2>
The ``mob_effects`` atlas honours any sprite size (``SpriteLoader.stitch`` imposes none and scales
nothing), and the HUD's blit is a fixed 18x18 (``Gui.renderEffects``). A 64x64 source therefore gives
the GPU eight times the detail to box-filter down to 18x18, which is the sharpness the human asked for
(「药水效果栏的图标，分辨率有点低了」). ``18`` was never a hard limit; it only matched vanilla's own
file sizes. See ``tools/gen_buff_icon.py``'s docstring for the measurements.

<h2>Which effects get a file, and which deliberately do not</h2>
The eight ``MECHANIC`` entries below are the effects ``PartyEffects.mechanicHolders()`` registers. The
five party slots are not here: ``tools/gen_party_effect_textures.py`` owns those, because they are
procedural runes rather than official art. And four of the generic kernel's ten effect <em>types</em> get
no file at all — ``heal_area``, ``heal_self``, ``remove_shield`` and ``percent_damage`` are instantaneous
(``heal()``, ``setAbsorptionAmount``, one damage batch), hold no ``MobEffectInstance``, and therefore
never appear on the effect bar. Registering a marker for them would put a flickering entry on the HUD for
something that finished in the same tick. (The other seven types — ``extra_damage``, ``lifesteal``,
``splash``, ``gain_charge``, ``area_charge``, ``stack``, ``detonate``, ``apply_effect``, ``heal`` and
``ally_effect`` — are hook-only mechanics with no registered effect, so they have a book icon and no
HUD sprite.)

<h2>attack_buff keeps 3001, stack keeps 3015</h2>
The human's final rule is one picture per category: every attack-up effect wears the fist ``3001``,
every other self-buff wears ``3010`` and is told apart by a border colour. ``attack_buff``'s two files
belong to ``tools/gen_buff_icon.py`` and ``stack``'s book icon to ``tools/gen_skill_icons.py``; they are
listed in ``MECHANIC`` so ``--check`` still verifies them, and ``OWNED_HERE`` says which of the eight
this file writes.

<h2>Four of the eight are tinted, and this script must not touch them</h2>
``phys_defence``, ``magic_defence``, ``haste`` and ``charge_rate`` all wear ``3010`` here, distinguished
only by a border colour, so ``tools/gen_tinted_icons.py`` is their single writer. A plain resample in
this script would strip the border and leave the code believing the effect is colour-coded — that was a
real defect: both scripts wrote ``mob_effect/haste.png`` and the two ``--check`` modes disagreed about
which was right. They are in ``MECHANIC`` (so the numbers and the registry paths are in one place) but
deliberately not in ``OWNED_HERE``.

Run:

    python tools/gen_effect_icons.py [--source DIR] [--check]
"""

from __future__ import annotations

import argparse
import os
import sys

from PIL import Image

#: The unpacked official PCR art; not part of the repository.
DEFAULT_SOURCE = os.path.join("K:\\", "AI", "Minecraft", "assets", "pcr", "extracted", "图片")

#: Registry path of each mechanic effect -> the official ``icon_skill_<n>`` its art comes from.
#:
#: The keys are <b>registry paths</b>, not ``EffectType`` names: ``BUFF_PHYS_DEFENCE_AREA`` is the effect
#: ``astrum:phys_defence`` and ``CHARGE_RATE_UP`` is ``astrum:charge_rate``, and those path names are what
#: the file must be called. The numbers must agree with ``impl/client/SkillIcons`` (its ``SOURCE_ID``,
#: looked up through its ``HUD_PATH`` that maps a type to the registry path) and with
#: ``tools/gen_buff_icon.py`` (``attack_buff`` = 3001). ``SkillIconsTest`` asserts the two agree.
MECHANIC = {
    "attack_buff": 3001,        # gen_buff_icon.py owns this pair
    "defence_down": 3011,
    "attack_area": 3001,        # the same fist as attack_buff: all attack-up effects share one picture
    "phys_defence": 3010,       # gen_tinted_icons.py owns this (green border)
    "magic_defence": 3010,      # gen_tinted_icons.py owns this (violet border)
    "haste": 3010,              # gen_tinted_icons.py owns this (gold border)
    "panic": 2010,
    "charge_rate": 3010,        # gen_tinted_icons.py owns this (blue border)
}

#: The subset this script writes. ``attack_buff`` is excluded so the human's pinned decision keeps its
#: single owner (``gen_buff_icon.py``), the four tinted ones because ``gen_tinted_icons.py`` draws their
#: borders, and a re-run of any script therefore cannot flip a file another one owns.
OWNED_HERE = ("defence_down", "attack_area", "panic")

#: Edge of the committed sprite. See the module docstring for why 64 and not vanilla's 18.
SIZE = 64

#: Destination, relative to the repository root.
OUTPUT_DIR = os.path.join("src", "main", "resources", "assets", "astrum", "textures", "mob_effect")


def resample(source: str) -> Image.Image:
    """Loads one official 128x128 icon and downscales it to :data:`SIZE`."""
    with Image.open(source) as image:
        icon = image.convert("RGBA")
    if icon.size != (128, 128):
        raise SystemExit(f"expected a 128x128 skill icon, {source} is {icon.size[0]}x{icon.size[1]}")
    # LANCZOS, the same filter the book icons use: these are pictures with a thin gold frame, and
    # NEAREST eats a 1-pixel frame at 64.
    return icon.resize((SIZE, SIZE), Image.LANCZOS)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", default=DEFAULT_SOURCE, help="the unpacked official image dir")
    parser.add_argument("--check", action="store_true",
                        help="verify the committed sprites instead of writing them")
    args = parser.parse_args(argv)

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out_dir = os.path.join(root, OUTPUT_DIR)

    def path_of(effect: str) -> str:
        return os.path.join(out_dir, effect + ".png")

    if args.check:
        for effect in MECHANIC:
            path = path_of(effect)
            if not os.path.isfile(path):
                raise SystemExit("missing committed HUD sprite: " + path
                                 + " (the effect would draw the missing-texture checkerboard)")
            with Image.open(path) as image:
                actual = image.size
            if actual != (SIZE, SIZE):
                raise SystemExit(f"{path} is {actual[0]}x{actual[1]}, expected {SIZE}x{SIZE}")
        for effect in OWNED_HERE:
            wanted = resample(os.path.join(args.source, "icon_skill_%d.png" % MECHANIC[effect]))
            with Image.open(path_of(effect)) as image:
                committed = image.convert("RGBA")
            if committed.tobytes() != wanted.tobytes():
                raise SystemExit(f"{path_of(effect)} is not icon_skill_{MECHANIC[effect]}.png at "
                                 f"{SIZE}x{SIZE} (run without --check to rewrite it)")
        print("ok: %d mechanic HUD sprites at %dx%d (%d derived here, %d owned elsewhere)"
              % (len(MECHANIC), SIZE, SIZE, len(OWNED_HERE), len(MECHANIC) - len(OWNED_HERE)))
        return 0

    os.makedirs(out_dir, exist_ok=True)
    for effect in OWNED_HERE:
        number = MECHANIC[effect]
        source = os.path.join(args.source, "icon_skill_%d.png" % number)
        image = resample(source)
        image.save(path_of(effect), format="PNG", optimize=True)
        print("wrote %s from icon_skill_%d.png" % (path_of(effect), number))
    print("%d sprites written; attack_buff stays gen_buff_icon.py's (icon_skill_3001), and the four "
          "tinted ones stay gen_tinted_icons.py's" % len(OWNED_HERE))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
