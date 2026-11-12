"""Generates the 16x16 character-book item texture.

Same reasoning as tools/gen_character_card_texture.py: this slice has no art pipeline, and a missing
texture renders as the black-and-magenta checker in every inventory, which reads as a broken mod. The
book is drawn here, pixel by pixel, so the file is reproducible — regenerate with

    python tools/gen_character_book_texture.py

and the committed PNG is exactly what this script writes (no image editor state involved).

The design has to be distinguishable from the card at 16px: the card is a dark indigo card with a gold
frame lying flat, so the book is a *closed book seen edge-on* — a brown cover with a lighter page block
on the right, a gold clasp across the middle and a gold emblem. Same palette family (gold), different
silhouette, so a player can tell them apart in a hotbar.
"""

from PIL import Image

SIZE = 16

TRANSPARENT = (0, 0, 0, 0)
GOLD = (232, 194, 90, 255)
GOLD_DARK = (168, 132, 46, 255)
COVER = (108, 62, 42, 255)
COVER_DARK = (76, 42, 28, 255)
COVER_LIGHT = (138, 84, 56, 255)
PAGES = (234, 223, 200, 255)
PAGES_SHADE = (198, 186, 162, 255)


def build() -> Image.Image:
    image = Image.new("RGBA", (SIZE, SIZE), TRANSPARENT)
    pixels = image.load()

    # Closed book: x 3..14, y 2..13. The left three columns are the spine, the right two the page block.
    for y in range(2, 14):
        for x in range(3, 15):
            pixels[x, y] = COVER

    # Page block down the right edge, in two tones so the edges read as stacked paper.
    for y in range(2, 14):
        pixels[12, y] = PAGES
        pixels[13, y] = PAGES
        pixels[14, y] = PAGES_SHADE

    # Spine: a darker band with a lighter highlight, so the book reads as bound rather than as a slab.
    for y in range(2, 14):
        pixels[3, y] = COVER_DARK
        pixels[4, y] = COVER_LIGHT

    # Cover shading: dark bottom edge, light top edge.
    for x in range(3, 15):
        pixels[x, 2] = COVER_LIGHT
        pixels[x, 13] = COVER_DARK

    # Gold clasp across the middle, hooking over the page block: the one feature that says "latch".
    for x in range(6, 14):
        pixels[x, 7] = GOLD
        pixels[x, 8] = GOLD
    pixels[11, 7] = GOLD_DARK
    pixels[11, 8] = GOLD_DARK

    # Emblem on the upper cover: a small diamond, matching the card's rarity diamond.
    for x, y in ((8, 4), (9, 4), (7, 5), (10, 5), (8, 5), (9, 5), (8, 6), (9, 6)):
        pixels[x, y] = GOLD

    # Two gold page-corner studs at the bottom, so the lower half is not an empty brown block.
    for x, y in ((6, 11), (7, 11), (6, 12), (7, 12)):
        pixels[x, y] = GOLD_DARK

    return image


if __name__ == "__main__":
    out = "src/main/resources/assets/astrum/textures/item/character_book.png"
    build().save(out, format="PNG", optimize=True)
    print(f"wrote {out}")
