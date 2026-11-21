"""Generates the one generic character-card item texture.

<h2>Why the card no longer shows anybody</h2>
The card used to carry Djeeta's official 3-star avatar (``icon_unit_105731.png``, resampled to 32x32 by
``tools/gen_card_from_avatar.py``). The human's objection was exactly right:
「角色卡为什么是姬塔的头像？后续如果是别的角色怎么办？」

There is <b>one</b> card item, ``astrum:character_card``, for every character in every datapack — the
character is a data component (``astrum:character_id``) on the stack, not a different item. A texture is
per <em>item</em>, so a texture showing a face says that the item *is* that character, and the next
character's card would either show the wrong girl or need a second item — which is the three-card
design this task deletes. The card is a <em>ticket</em>; a ticket does not wear the passenger's face.

<h2>What it shows instead</h2>
A blank card: the official PCR profile-card frame (``profilecard_common_frame_002.png``, 1024x512 —
rights-clear art from the same official dump as the avatars and skill icons), the theme's own body
colour behind it, and a small gold diamond where the character's face will not be. No character, no
gesture, no silhouette of anybody. Read at 16 pixels it is still a card — a light gold-blue border, a
darker panel, one bright mark — and it is still distinguishable from the book, which is a closed
codex with a page block and a clasp.

Points of the frame are sampled rather than invented: the body colour is the same ``#1B3560`` family
the book's own blue panel uses, so a card and the blue book read as one set.

<h2>The card is one item now, so this writes one file</h2>
``theme_sakura/character_card.png`` and ``theme_forest/character_card.png`` were deleted with the
sakura/forest card <em>items</em>: a theme belongs to the book, not to the ticket.

Regenerate with:

    python tools/gen_card_from_avatar.py            # writes the committed PNG
    python tools/gen_card_from_avatar.py --check    # verifies size, mode and that it holds no face

The ``--source`` default is the official art tree on this machine; the source PNGs are not committed,
only the 32x32 result is.
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


#: The official, unpacked PCR art; not part of the repository.
DEFAULT_SOURCE = os.path.join(_dump_root(), "assets", "pcr", "extracted", "图片")

#: The official card frame this texture is cut from. Squared off, not stretched: see ``build``.
FRAME = "profilecard_common_frame_002.png"

#: Card texture size. 32 is what the item model's ``layer0`` gets drawn from; the game scales it.
SIZE = 32

#: The panel colour painted inside the frame, matching the blue book's own body (``#1D3965``) family.
BODY = (27, 53, 96, 255)

#: The gold of the frame's own ornament, sampled from it.
GOLD = (232, 194, 90, 255)

#: Committed output, relative to the repository root. ONE file: there is one card.
OUTPUT = os.path.join("src", "main", "resources", "assets", "astrum", "textures", "item",
                      "character_card.png")


def load_frame(source_dir: str) -> Image.Image:
    """Loads the official card frame, centre-cropped to a square so the ornament stays uncrushed."""
    path = os.path.join(source_dir, FRAME)
    if not os.path.isfile(path):
        raise SystemExit(
            "official card frame not found: " + path +
            "\nThe official art is not in the repository; pass --source DIR."
        )
    with Image.open(path) as image:
        frame = image.convert("RGBA")
    width, height = frame.size
    if width < height:
        raise SystemExit(f"{FRAME} must be at least as wide as it is tall, got {width}x{height}")
    # Centre square: the frame is 2:1, and a 2:1 picture squeezed into a 1:1 icon is exactly the
    # "squashed" distortion the human complained about in the book panel. Cropping keeps the ornament
    # round and the corners square.
    left = (width - height) // 2
    return frame.crop((left, 0, left + height, height))


def build(source_dir: str) -> Image.Image:
    """Composes the generic card: gold rim, official frame, body fill, one gold mark.

    No character art is opened anywhere in here — that is the point of the file, not a detail of it.
    """
    card = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    card.alpha_composite(Image.new("RGBA", (SIZE, SIZE), BODY))

    # The official frame, one pixel in from the edge so the card keeps a hard silhouette: an item is
    # drawn at 16 px in an inventory slot, and the frame's own left/right ornament is one pixel wide at
    # 32 and vanishes there. The rim below is what survives the downscale.
    inner = SIZE - 2
    frame = load_frame(source_dir).resize((inner, inner), Image.LANCZOS)
    card.alpha_composite(frame, (1, 1))

    pixels = card.load()
    # A 1-pixel gold rim, squared corners: every inventory-scale read of the icon depends on this edge.
    for i in range(SIZE):
        pixels[i, 0] = GOLD
        pixels[i, SIZE - 1] = GOLD
        pixels[0, i] = GOLD
        pixels[SIZE - 1, i] = GOLD

    # One 3x3 gold diamond in the middle: it says "blank slot waiting for a name" the way a form's
    # placeholder does, and it keeps the card from reading as an empty inventory square. Symmetric
    # about the centre pixel so it survives being drawn at 16 px.
    for x, y in ((15, 15), (16, 15), (17, 15), (16, 14), (16, 16), (16, 15)):
        pixels[x, y] = GOLD
    return card


def check(path: str, source_dir: str) -> int:
    """Fails when the committed card is the wrong size/mode, or is not what this script builds.

    <p>The second half is the one that matters: "the card shows no character's face" is not checkable by
    looking at pixel counts (a 32-pixel face and this frame both end up with a few hundred colours after
    a LANCZOS resample), so it is enforced structurally instead — ``build`` never opens an avatar, and
    this comparison proves the committed PNG is exactly the output of that avatar-free build. If somebody
    drops a face in by hand, the file stops matching and this fails.
    """
    with Image.open(path) as image:
        card = image.convert("RGBA")
    if card.size != (SIZE, SIZE):
        raise SystemExit(f"{path} is {card.size}, expected {SIZE}x{SIZE}")
    if image.mode != "RGBA":
        raise SystemExit(f"{path} is {image.mode}, expected RGBA")

    wanted = build(source_dir)
    if card.tobytes() != wanted.tobytes():
        raise SystemExit(
            f"{path} is not what tools/gen_character_card_texture.py builds. If the card was replaced "
            "with character art, that is the defect: one card is shared by every character, so its "
            "texture must be generic."
        )
    print(f"ok: {path} is {SIZE}x{SIZE} RGBA and identical to the avatar-free build")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", default=DEFAULT_SOURCE, help="the official unpacked art directory")
    parser.add_argument("--check", action="store_true",
                        help="verify the committed texture instead of writing it")
    args = parser.parse_args(argv)

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    path = os.path.join(root, OUTPUT)

    if args.check:
        return check(path, args.source)

    os.makedirs(os.path.dirname(path), exist_ok=True)
    card = build(args.source)
    card.save(path, format="PNG", optimize=True)
    print(f"wrote {path} ({SIZE}x{SIZE}, generic card, no character art)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
