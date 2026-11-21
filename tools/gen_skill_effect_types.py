"""Writes the **one mob-effect type per skill** the mod registers, from the character data itself.

<h2>The defect this exists for, in the human's words</h2>
「buff给予了后，给予新buff不是覆盖，而是同时存在，给予同一个buff才是刷新持续cd。我只装上雪菲，他只会显示冻结之湖或者雪鞋，
不会两个同时存在。」

Vanilla keys a player's effects by effect **type** (`LivingEntity.activeEffects` is a map keyed by the
`MobEffect`), and `MobEffectInstance.update` merges a second application of the same type into the first. So
the old family — five inert types, `astrum:skill_1..5`, one per party **slot** — could only ever hold one
entry per character: her second skill's row overwrote her first. The fix is one type per **skill**, which is
what this script enumerates.

<h2>Why the list is generated and not a 91-line literal</h2>
91 types today (22 characters × their skills + their UB), and the human adds characters regularly. Writing
them into Java would be a list that goes stale the day he adds one — silently, and the symptom would be one
character whose second skill never shows. So the list is derived from `data/astrum/astrum/character/*.json`
and written as a **resource** the mod reads at class-init:

    assets/astrum/skill_effect_types.json

`PartyEffects` registers one holder per entry in that file, and `SkillEffectTypesTest` re-derives the same
set from the decoded `CharacterDef`s of **every** shipped JSON and asserts the two agree. Adding a character
therefore costs one `python tools/gen_skill_effect_types.py` run; forgetting it fails the build with the
sentence that says so, rather than shipping a skill whose row never appears.

<h2>Why a resource and not generated Java</h2>
`DeferredRegister` has to hold every entry **before** the `RegisterEvent` fires — i.e. long before any
datapack is read — so the identity set cannot come from the loaded characters at runtime. A generated
resource + a ten-line registrar is the smallest honest shape: the *data* still decides, nothing is
hand-written, and the Java side stays one loop. Generated Java with 91 `register(...)` calls would be the
same list in a different file, and a bigger one.

<h2>Which skills</h2>
For each character, the identity set is the union of every skill tag the pack can fire:

* `effects[].triggerParams.skill` — her small skills, as declared;
* `affection.skills[].skill` — the ladders, which include the reserved `ex` tag (no hook ever carries it, but a
  player is meant to see her EX's row, and one type per ladder keeps the book and the bar keyed the same way);
* `ub.secondRotation.hooks[]` and `ub.secondRotation.upgradedHooks[]`'s `triggerParams.skill` — 矛依未's second
  loop (`sp1`, `sp2`), which exists only in her ultimate's declaration;
* plus the ultimate itself, whose identity is the reserved tag `ub` (`AffectionKernel.ubKey()`).

Deliberately **not** `ub.<rung>.effects[].triggerParams.skill`: a `UbEffect` has no trigger-params component at
all (`UbEffect.CODEC` reads scope / effect / effectParams / …), so a `skill` tag written there is a field the
codec ignores. Deriving a type from it would register an effect nothing can ever grant — the manifest would grow
with the data while the game stayed the same. The count is 91 without it, which is what `--check` prints.

That union is derived from the **same fields the game reads**, so a character with an unusual shape is
handled by the data rather than by an edit here. `--check` prints the count; the tests assert it against the
decoded definitions.

<h2>The sprites</h2>
Every registered effect needs `assets/<ns>/textures/mob_effect/<registry path>.png`, or the `mob_effects`
atlas hands out the magenta missing-texture checkerboard in JEI and anywhere else that samples it. The
official skill art is **not** in this repository (it is derived from Cygames' assets, so `.gitignore` lists
it and `build.gradle` excludes it from the jar). So each of the 91 types gets a **copy of the mod's own
self-drawn 64x64 placeholder** — the same picture `tools/gen_effect_icon_copies.py` gives the generic
effects — and that cp ships in the jar. The *real* icon is drawn at runtime by `SkillEntryClient`, which
reads the skill's own `icon` from the pack. The split, stated once:

    jar: 91 copies of one self-drawn placeholder -> JEI and the atlas always have a picture
    pack: the real skill icons                  -> the HUD/inventory row wears the skill's own art
    missing pack: the extension draws ArtFallback's 「请安装资源包」, never the checkerboard

Usage:

    python tools/gen_skill_effect_types.py            # write the manifest and the 91 placeholders
    python tools/gen_skill_effect_types.py --check    # verify both, without writing
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

#: The shipped character definitions. `data/<namespace>/<registry>/<path>`: the character registry is
#: `astrum:character`, so a file here is `astrum:<file stem>`.
CHARACTER_DIR = os.path.join(ROOT, "src", "main", "resources", "data", "astrum", "astrum", "character")

#: The manifest `SkillEffectTypes` reads at class-init. Under `assets/` (not `data/`) because it is not a
#: datapack registry entry; the mod reads it from its own classpath.
MANIFEST = os.path.join(ROOT, "src", "main", "resources", "assets", "astrum", "skill_effect_types.json")

#: Where the `mob_effects` atlas looks.
TEXTURE_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "astrum", "textures", "mob_effect")

#: The mod's own placeholder — the same master `gen_effect_icon_copies.py` uses. Self-drawn, committed,
#: 64x64, and by construction not derived from official art.
MASTER = os.path.join(ROOT, "src", "main", "resources", "assets", "astrum", "textures", "gui",
                      "effect_icon.png")

# 16x16, not 64. Vanilla's HUD blits an 18-pixel square whatever the file's resolution, and JEI draws the
# sprite into a small slot too, so a 64x64 file is 16 times the pixels for the same on-screen result — and there
# are 91 of these. The human asked for the smaller size after seeing the jar grow from 1.18 MB to 1.77 MB; at
# 16x16 the whole set is about 40 KB instead of 624 KB. The master stays 64x64 (gui/effect_icon.png) so a future
# surface that wants a big version still has one.
SIZE = 16

#: The registry path prefix. `SkillEffectTypes.PATH_PREFIX` must be the same string; the test asserts it by
#: parsing the manifest's own ids, so a drift fails rather than silently renaming 91 effects.
PATH_PREFIX = "skill"

#: A path segment `ResourceLocation` accepts: `[a-z0-9_.-]+`. A pack whose skill tag has an uppercase letter
#: or a space cannot be a registry path, so this script fails with the offending tags rather than writing an
#: id the game would reject at load.
SEGMENT = re.compile(r"^[a-z0-9_.-]+$")

#: The reserved ultimate tag — `AffectionKernel.ubKey()`. Not a hook tag; the double is the identity of the
#: row the cast path writes.
UB_SKILL = "ub"

NOTE = ("Generated by tools/gen_skill_effect_types.py from data/astrum/astrum/character/*.json. "
        "One mob-effect type per (character, skill), because vanilla merges effects by type and two "
        "different skills of one character must be able to sit on the bar at once. Do not edit by hand: "
        "rerun the script after adding a character or a skill.")


def character_files() -> list[str]:
    """Returns every shipped character JSON, in a deterministic order."""
    return sorted(
        os.path.join(CHARACTER_DIR, name)
        for name in os.listdir(CHARACTER_DIR)
        if name.endswith(".json")
    )


def skill_tags(data: dict) -> list[str]:
    """Returns every skill tag one character can fire, in first-appearance order.

    The same four places the game reads: the base effects, the affection ladders, the ultimate's own clauses
    (both rungs), and the second rotation's declaration. The ultimate is appended last under `UB_SKILL`.
    """
    tags: list[str] = []

    def add(tag: object) -> None:
        if isinstance(tag, str) and tag and tag not in tags:
            tags.append(tag)

    for effect in data.get("effects") or []:
        add((effect.get("triggerParams") or {}).get("skill"))

    for ladder in (data.get("affection") or {}).get("skills") or []:
        add(ladder.get("skill"))

    ub = data.get("ub") or {}
    # SecondRotation's field name, as the codec spells it: `ub.secondRotation`, one block for the whole
    # ultimate (not one per rung), with `hooks` and `upgradedHooks`. NOT `ub.<rung>.effects[]`: a UbEffect has
    # no trigger-params component, so a skill tag written there is a field the codec ignores and a type
    # derived from it could never be granted.
    second = ub.get("secondRotation") or {}
    for key in ("hooks", "upgradedHooks"):
        for hook in second.get(key) or []:
            add((hook.get("triggerParams") or {}).get("skill"))

    if ub:
        add(UB_SKILL)
    return tags


def identities() -> list[dict]:
    """Returns the whole declared set: `{character, skill}` per type, sorted by character then skill order."""
    declared: list[dict] = []
    for path in character_files():
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
        character = "astrum:" + os.path.basename(path)[:-len(".json")]
        for tag in skill_tags(data):
            declared.append({"character": character, "skill": tag})
    return declared


def registry_path(identity: dict) -> str:
    """Returns the registry path of one type: `skill/<character namespace>/<character path>/<skill>`.

    The character's **full** id, not just its path: two packs can declare a character with the same stem in
    different namespaces, and one registry needs them distinct. The whole path is what the `mob_effects`
    atlas turns into `assets/astrum/textures/mob_effect/<path>.png`, so it is also the sprite's path.
    """
    namespace, _, path = identity["character"].partition(":")
    return f"{PATH_PREFIX}/{namespace}/{path}/{identity['skill']}"


def problems_with(declared: list[dict]) -> list[str]:
    """Returns every identity that cannot be a registry path, as a sentence naming it."""
    problems: list[str] = []
    seen: set[str] = set()
    for identity in declared:
        namespace, _, path = identity["character"].partition(":")
        if not namespace or not path:
            problems.append(f"{identity['character']!r} is not a namespaced character id")
            continue
        for label, segment in (("character", path), ("skill", identity["skill"])):
            if not SEGMENT.match(segment):
                problems.append(
                    f"{identity['character']} {identity['skill']!r}: the {label} segment {segment!r} is not a "
                    f"valid ResourceLocation path segment ([a-z0-9_.-]+), so this skill cannot have its own "
                    f"effect type until it is renamed")
        rendered = registry_path(identity)
        if rendered in seen:
            problems.append(f"duplicate identity {rendered}")
        seen.add(rendered)
    return problems


def manifest_text(declared: list[dict]) -> str:
    """Returns the exact bytes of the committed manifest: sorted, indented, one trailing newline."""
    document = {
        "schemaVersion": 1,
        "generatedBy": "tools/gen_skill_effect_types.py",
        "_notes": NOTE,
        "types": declared,
    }
    return json.dumps(document, ensure_ascii=False, indent=2) + "\n"


def render_placeholder() -> Image.Image:
    """Returns the placeholder at :data:`SIZE`, from the one self-drawn master."""
    with Image.open(MASTER) as source:
        return source.convert("RGBA").resize((SIZE, SIZE), Image.LANCZOS)


def sprite_path_of(identity: dict) -> str:
    return os.path.join(TEXTURE_DIR, *registry_path(identity).split("/")) + ".png"


def digest(path: str) -> str:
    with open(path, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()


def check(declared: list[dict]) -> int:
    problems = problems_with(declared)
    if not os.path.isfile(MANIFEST):
        problems.append(f"missing manifest {MANIFEST}")
    else:
        with open(MANIFEST, encoding="utf-8") as handle:
            committed = json.load(handle)
        if committed.get("types") != declared:
            problems.append(
                "the committed manifest does not match the character data — run "
                "`python tools/gen_skill_effect_types.py` (a character or a skill was added, renamed or "
                "removed without regenerating it)")
    sprites = [sprite_path_of(identity) for identity in declared]
    for sprite in sprites:
        if not os.path.isfile(sprite):
            problems.append(f"missing placeholder sprite {sprite}")
            continue
        with Image.open(sprite) as image:
            if image.size != (SIZE, SIZE):
                problems.append(f"{sprite} is {image.size}, expected {(SIZE, SIZE)}")
    present = {digest(path): path for path in sprites if os.path.isfile(path)}
    if len(present) > 1:
        problems.append("the placeholders are not byte-identical to each other: one picture is the rule")
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        return 1
    print(f"{len(declared)} skill effect types declared, {len(sprites)} placeholder sprites present")
    return 0


def write_all(declared: list[dict]) -> int:
    problems = problems_with(declared)
    if problems:
        for problem in problems:
            print(problem, file=sys.stderr)
        return 1
    if not os.path.isfile(MASTER):
        print(f"missing master sprite: {MASTER}", file=sys.stderr)
        return 1
    os.makedirs(os.path.dirname(MANIFEST), exist_ok=True)
    with open(MANIFEST, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(manifest_text(declared))
    print(f"wrote {MANIFEST} ({len(declared)} types)")
    sprite = render_placeholder()
    for identity in declared:
        target = sprite_path_of(identity)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        sprite.save(target, "PNG", optimize=True)
    print(f"wrote {len(declared)} placeholder sprites under {TEXTURE_DIR}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="verify the committed manifest and sprites instead of writing them")
    args = parser.parse_args()
    declared = identities()
    return check(declared) if args.check else write_all(declared)


if __name__ == "__main__":
    sys.exit(main())