"""Draws the <b>记忆结晶 / memory token</b> item texture — the exchange currency for duplicate cards.

<h2>Why this item exists</h2>
The human's design: a character card is a lottery, duplicates are possible (「已经有的角色也能抽到」), and a
duplicate is not trash — 「重复的角色卡可以销毁，或者参与合成得到一个新物品」. A hundred of those make
「任选一个角色卡」. So the token needs to look like something you would keep a hundred of: small, bright,
countable, and obviously <em>not</em> a card.

<h2>Why it is drawn procedurally and not sourced</h2>
Every other texture in this mod is a resample of official PCR art, and this one deliberately is not: there is
no official "duplicate card shard" in the dump, and inventing an official-looking file would be a lie about
where it came from. So it is drawn here, in PIL, from the mod's own palette — the same cyan the UB gauge fills
with and the same gold the tier-3 marker uses ({@code WheelIcon}, {@code BookPalette}) — which is the honest
provenance for a shape this mod invented.

<h2>What it draws, and why each part</h2>
A <b>faceted gem</b> at 32x32:

* a dark rounded body, so the gem reads against both a light and a dark inventory background;
* a <b>cyan</b> upper-left facet and a darker cyan lower-right one, split on a diagonal — the two-tone read is
  what makes a flat 16-pixel icon look like a stone rather than a dot;
* a <b>gold rim</b> one pixel thick, the tier-3 marker's colour, so a token in the inventory belongs to the
  same visual family as the rest of the mod;
* one bright specular pixel at the upper-left, which is what actually makes it read as "crystal" at 16px.

<h2>Why 32x32</h2>
The item model draws it into a 16x16 slot; 32 gives the GPU a 2x2 box to average per output pixel, which is
the same "more detail survives the downscale" argument ``tools/gen_buff_icon.py`` measures for the effect HUD.
The card's own texture is 32 as well, so the two look like siblings in a chest.

Run:

    python tools/gen_token_texture.py [--check]

``--check`` re-derives the image and fails if the committed file differs, so a hand-edit is caught.
"""

from __future__ import annotations

import argparse
import os
import sys

from PIL import Image, ImageDraw

#: Edge of the committed texture. See the module docstring.
SIZE = 32

#: Destination, relative to the repository root.
OUTPUT = os.path.join("src", "main", "resources", "assets", "astrum", "textures", "item",
                      "memory_token.png")

#: The voucher, the other item this loop needs. Drawn here rather than in a second script because the two are
#: one design: a token that buys a voucher, and a voucher that buys a choice. A reader looking at either
#: should be able to find the other.
OUTPUT_VOUCHER = os.path.join("src", "main", "resources", "assets", "astrum", "textures", "item",
                              "character_card_voucher.png")

#: Body behind the gem: near-black blue, the book's own darkest surface, so the token has an outline of its
#: own on any background instead of relying on the inventory's.
BODY = (18, 26, 44, 255)

#: The mod's gold, the same value as {@code WheelIcon}'s tier-3 tint and {@code PartyStatusRow.PIP_FILL}.
GOLD = (255, 201, 60, 255)

#: Cyan facets. The light one is the UB gauge's fill colour family; the dark one is its shadow so the two
#: halves read as one stone lit from the upper left.
CYAN_LIGHT = (150, 238, 255, 255)
CYAN_DARK = (52, 138, 186, 255)

#: The specular highlight: almost white, one pixel, upper left.
GLINT = (240, 253, 255, 255)


def draw() -> Image.Image:
    """Returns the 32x32 RGBA token."""
    image = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    pen = ImageDraw.Draw(image)

    # A hexagonal gem: wider than it is tall, flat top and bottom. Points as fractions of SIZE so the shape
    # is a description rather than 12 magic numbers that would break the moment SIZE changed.
    def px(fraction: float) -> float:
        return fraction * SIZE

    gem = [
        (px(0.50), px(0.10)),   # top
        (px(0.86), px(0.32)),   # upper right
        (px(0.74), px(0.80)),   # lower right
        (px(0.50), px(0.92)),   # bottom
        (px(0.26), px(0.80)),   # lower left
        (px(0.14), px(0.32)),   # upper left
    ]

    # The rim first, a slightly larger copy, so the gold reads as a frame rather than as a fill.
    rim = [(SIZE / 2 + (x - SIZE / 2) * 1.14, SIZE / 2 + (y - SIZE / 2) * 1.14) for x, y in gem]
    pen.polygon(rim, fill=BODY)
    pen.line(rim + [rim[0]], fill=GOLD, width=1)

    # The stone: dark cyan overall, then the lit <b>left</b> half over it, split on the vertical axis. A
    # left/right split rather than a wedge: at 16 pixels a wedge reads as a mistake, while two halves of one
    # stone reads as a cut gem lit from the left — which is the same convention the mod's other art uses
    # (the tier-3 marker's gold sits on the upper-left of the face).
    pen.polygon(gem, fill=CYAN_DARK)
    centre = (px(0.50), px(0.50))
    pen.polygon([gem[0], gem[5], gem[4], gem[3], centre], fill=CYAN_LIGHT)

    # The facet split, so the halves read as a cut rather than two colours meeting.
    pen.line([gem[0], gem[3]], fill=BODY, width=1)

    # A second, shorter facet on the lit half: the crown of the cut. Without it the light half is a flat
    # block and the gem loses its depth at 16px.
    pen.line([gem[5], (px(0.50), px(0.30))], fill=CYAN_DARK, width=1)

    # One specular pixel. Small, and the whole reason it reads as a crystal at 16px.
    pen.point((px(0.38), px(0.28)), fill=GLINT)

    return image


def draw_voucher() -> Image.Image:
    """Returns the 32x32 RGBA 选择券: the card ticket, with a gem seal in its corner.

    Deliberately the card's own shape and colours — a ticket with a gold frame, the same one
    ``tools/gen_card_from_avatar.py`` draws for ``character_card`` — plus the token's cyan in a small seal at
    the lower right. That is the sentence the item needs to say: <em>a card, bought with tokens</em>. A
    different silhouette would have read as an unrelated item, and the two are meant to be recognisable as one
    family.
    """
    image = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    pen = ImageDraw.Draw(image)

    # The ticket: the card body's blue, its gold frame, and one lighter band where the card's title rule sits.
    pen.rounded_rectangle([3, 6, 28, 25], radius=3, fill=BODY, outline=GOLD)
    pen.rectangle([5, 9, 26, 11], fill=CYAN_DARK)

    # The seal: the token's own shape, small, at the lower right — "this card was paid for with crystals".
    seal = [(23, 14), (28, 19), (23, 24), (18, 19)]
    pen.polygon(seal, fill=GOLD)
    inner = [(23, 16), (26, 19), (23, 22), (20, 19)]
    pen.polygon(inner, fill=CYAN_LIGHT)
    pen.line([(23, 16), (23, 22)], fill=CYAN_DARK, width=1)

    return image


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="verify the committed textures instead of writing them")
    args = parser.parse_args(argv)

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    outputs = ((OUTPUT, draw()), (OUTPUT_VOUCHER, draw_voucher()))

    if args.check:
        for relative, wanted in outputs:
            path = os.path.join(root, relative)
            if not os.path.isfile(path):
                raise SystemExit("missing committed texture: " + path)
            with Image.open(path) as image:
                committed = image.convert("RGBA")
            if committed.size != (SIZE, SIZE):
                raise SystemExit(f"{path} is {committed.size}, expected {SIZE}x{SIZE}")
            if committed.tobytes() != wanted.tobytes():
                raise SystemExit(f"{path} is not what gen_token_texture.py draws "
                                 "(run without --check to rewrite it)")
        print(f"ok: {len(outputs)} exchange textures are {SIZE}x{SIZE} RGBA and match the drawn shapes")
        return 0

    for relative, wanted in outputs:
        path = os.path.join(root, relative)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        wanted.save(path, format="PNG", optimize=True)
        print(f"wrote {path} ({SIZE}x{SIZE})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
