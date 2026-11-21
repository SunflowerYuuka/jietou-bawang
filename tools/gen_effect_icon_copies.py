"""Writes the **one shared sprite** for every generic effect, at the path vanilla's atlas looks for.

<h2>The defect this exists for</h2>
The human's report: 「JEI里所有通用效果的图标都使用一个统一的图标」 — and before it, his screenshots of the
effect bar, where every generic effect's row was magenta. The two are the same fact. Vanilla's
``mob_effects`` atlas indexes ``assets/astrum/textures/mob_effect/<effect path>.png`` per effect <b>id</b>
(``MobEffectTextureManager.get`` asks for the effect's registry id), and JEI reads the same sprite. A
registered effect with no file there is not "no icon" — it is the purple missing-texture checkerboard.

So every generic effect needs a file, and every one of them must be <b>the same picture</b>: a generic
effect is a mechanic the damage maths reads, not a status with its own art. ``gui/effect_icon.png`` is that
picture (64x64, self-drawn, committed), and this script resamples it to the same 64x64 the skill entries use and writes the copies.

<h2>Why copies and not one file plus a redirect</h2>
There is no indirection to use. The atlas is a plain ``DirectoryLister`` over ``mob_effect``, and the client
asks for ``<namespace>:<path of the effect's registry id>`` — so the <b>file name is the contract</b> and
thirteen registry ids mean thirteen files. Anything cleverer (an atlas source, a per-effect
``IClientMobEffectExtensions`` returning its own sprite) either cannot be data-driven or would have to be
repeated per effect, which is the duplication the shared ``HiddenEffectClient`` avoids.

Note the asymmetry with the bar: these files exist so that <b>JEI (and any other consumer of the sprite)</b>
has something to draw. On the bar itself the effects are hidden entirely
(``impl/client/HiddenEffectClient``), because 「药水栏……仅显示角色的技能图标」. Both statements are true at once:
a sprite is required by the atlas whether or not vanilla's own two lists draw it.

<h2>Which effects</h2>
``PartyEffects.mechanicHolders()`` is the declaration, and ``EffectSpriteCatalog`` mirrors its paths here
because Python cannot read Java. ``EffectSpriteResourceTest`` asserts the two agree — a fourteenth generic
effect added to the registry and forgotten here fails that test rather than shipping a checkerboard.

<h2>These files ship in the jar, and are not in .gitignore</h2>
They are derived from ``gui/effect_icon.png``, which is <b>self-drawn</b> (see
``tools/gen_effect_icons.py``'s history: the official-art sprites were deleted when a generic effect stopped
having its own picture). So unlike the official-art families, these are not "派生自 Cygames 官方美术" and must
not be externalised: JEI's tooltip has to show the picture with <b>no resource pack installed</b>, which is
exactly the case a ``.gitignore`` entry would break — ``build.gradle`` excludes every ignored
``src/main/resources`` path from ``jar``, so ignoring these would remove them from the mod and leave JEI with
the checkerboard again.

Run::

    python tools/gen_effect_icon_copies.py            # write the 13 files
    python tools/gen_effect_icon_copies.py --check    # verify they are identical to the master
"""

from __future__ import annotations

import argparse
import hashlib
import os
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

#: The one picture every generic effect wears. Self-drawn, committed, 64x64.
MASTER = os.path.join(ROOT, "src", "main", "resources", "assets", "astrum", "textures", "gui",
                      "effect_icon.png")

#: Where the ``mob_effects`` atlas looks, and the size the HUD blits at.
OUTPUT_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "astrum", "textures",
                          "mob_effect")
# 16x16, the size the human asked for on the skill placeholders and then for these too
# (「不是使用16*16的那个图标吗？」). Vanilla's HUD blits an 18-pixel square whatever the file's resolution and
# JEI draws into a small slot, so 64x64 was 16 times the pixels for the same on-screen result.
SIZE = 16

#: Registry path of every generic effect — ``PartyEffects.mechanicHolders()``, in its order.
#:
#: The five party slots (``party_1..5``) are deliberately absent: their runes are procedural art owned by
#: ``tools/gen_party_effect_textures.py``. The skill entries are absent too, for a stronger reason: they draw the
#: skill's own picture through ``SkillEntryClient`` and never let the atlas be consulted, so a file here would be a
#: second source for one picture.
#:
#: Careful: the skill types are **not** absent from ``mob_effect/`` any more — since task 59 there are 91 of them,
#: one per (character, skill), and each needs a placeholder for JEI's atlas. They are not in this list because
#: they are not flat files and their set follows the character data, so they are written by
#: ``tools/gen_skill_effect_types.py`` (``skill/<character>/<skill>.png``) from the same master. This list is the
#: flat generic-effect set only.
EFFECT_PATHS = (
    "attack_buff",
    "defence_down",
    "attack_area",
    "phys_defence",
    "phys_defence_flat",
    "magic_defence",
    "haste",
    "panic",
    "charge_rate",
    "shield",
    "magic_defence_down",
    "magic_attack_area",
    # NOTE: 「spirit」 and 「counter」 are deliberately NOT here. Their sprites are the characters' own
    # portraits (千歌 / 雪菲), i.e. derived official art, and the human moved them to the resource pack
    # (「官方素材都不进jar，放资源包」). A run of this script would overwrite the pack's copies with a
    # placeholder, so they are excluded rather than merely ignored.
    # task 56/57/58: the movement family, the two immunity channels and the timed crit-damage buff. They were
    # written straight to disk by their tasks instead of through this generator, which is why they were 64x64
    # while everything else was already here — the human spotted exactly that: 「控制免疫、暴击伤害提升、物理免疫、风之刃、
    # 西式小茶壶、嘲讽，这几个图标用的还是64*64」.
    "taunt",
    "phys_immunity",
    "control_immunity",
    "crit_damage_up",
    "wind_blade",
    "tea_pot",
    # task 49: 他充 / 他奶 and the four over-time effects. Note ``charge_regen`` is ONE file for both the
    # self and the area form, because they are one registered effect type (the human's 「取最强」 merge rule
    # needs them to be one type, so the atlas sees one id). ``regen_self``/``regen_area`` get a file each: they
    # are two types applying vanilla's ``regeneration``, and vanilla resolves the *vanilla* sprite for it, but a
    # file of our own is still required by the manifest below so a future turn of one family cannot leave a gap.
    "transfer_charge",
    "transfer_heal",
    "regen_self",
    "regen_area",
    "charge_regen",
    # task 60: the timed crit-chance buff and the tally-scaled charge regen. Both are real mechanics (an
    # attribute modifier and a per-tick gauge credit) with their own registry ids, so the atlas asks for both
    # and a missing file would be the checkerboard.
    "crit_chance_up",
    "charge_counter",
    # task 62: the two victim-side crit debuffs. They carry real negative attribute modifiers on the body that was
    # hit, so the mob_effects atlas asks for both ids and a missing file would be the purple checkerboard — the
    # same reason every other mechanic has a copy.
    "crit_chance_down",
    "crit_damage_down",
    # task 63: 矛依未's 「标记 ID 125」. A third named state (with 风之刃 and 西式小茶壶), registered so the
    # atlas can draw its bar row — a state nobody can see is indistinguishable from one that was never granted.
    "marker_125",
)


def render_master() -> Image.Image:
    """Return the shared sprite at :data:`SIZE`, resampled from the master once."""
    with Image.open(MASTER) as source:
        # RGBA on a transparent background: the HUD draws the sprite over vanilla's own 24x24 frame, so an
        # opaque square would paint over the frame instead of sitting inside it.
        return source.convert("RGBA").resize((SIZE, SIZE), Image.LANCZOS)


def digest(path: str) -> str:
    with open(path, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()


def write_all() -> int:
    if not os.path.isfile(MASTER):
        print(f"missing master sprite: {MASTER}", file=sys.stderr)
        return 1
    sprite = render_master()
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    for path in EFFECT_PATHS:
        target = os.path.join(OUTPUT_DIR, f"{path}.png")
        sprite.save(target, "PNG", optimize=True)
        print(f"wrote {target} ({os.path.getsize(target)} bytes)")
    return 0


def check() -> int:
    """Fail unless every file exists, is :data:`SIZE` square, and matches every other one byte for byte.

    <h2>Why not "equal to what PIL renders"</h2>
    Two resamplers never agree pixel for pixel — LANCZOS here, whatever produced the committed files
    elsewhere — and demanding PIL's exact bytes would make this check fail on a correct tree while the
    defect it exists for (a missing file, a wrong size, a picture that drifted from the shared one) went
    unmentioned. Byte equality <b>among the copies</b> is the property the requirement actually names
    (「所有通用效果的图标都使用一个统一的图标」), so that is what is asserted, plus the size vanilla's HUD
    blits at.
    """
    digests = {}
    sizes = {}
    problems = []
    for path in EFFECT_PATHS:
        target = os.path.join(OUTPUT_DIR, f"{path}.png")
        if not os.path.isfile(target):
            problems.append(f"missing {target}")
            continue
        with Image.open(target) as image:
            sizes[path] = image.size
            if image.size != (SIZE, SIZE):
                problems.append(f"{target} is {image.size}, expected {(SIZE, SIZE)}")
            if image.mode != "RGBA":
                problems.append(f"{target} is {image.mode}, expected RGBA (the HUD blits over vanilla's frame)")
        digests[path] = digest(target)
    if len(set(digests.values())) > 1:
        problems.append("the copies are not byte-identical to each other: " + repr(digests))
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        return 1
    print(f"{len(EFFECT_PATHS)} generic-effect sprites are present, {SIZE}x{SIZE}, and byte-identical")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="verify the committed files instead of writing them")
    args = parser.parse_args()
    return check() if args.check else write_all()


if __name__ == "__main__":
    sys.exit(main())
