"""Generates the 16x16 character-card item texture.

The mod ships a hand-made placeholder on purpose: this slice has no art pipeline for item icons, and a
missing texture renders as the black-and-magenta checker in every inventory, which reads as a broken
mod. The card is drawn here, pixel by pixel, so the file is reproducible — regenerate with

    python tools/gen_character_card_texture.py

and the committed PNG is exactly what this script writes (no image editor state involved).
"""

from PIL import Image

SIZE = 16

# Palette: a deep indigo card with a gold frame and a cream name band, so it reads as a card at 16px
# and is distinguishable from vanilla paper, map and book at a glance.
TRANSPARENT = (0, 0, 0, 0)
GOLD = (232, 194, 90, 255)
GOLD_DARK = (168, 132, 46, 255)
CARD = (30, 42, 92, 255)
CARD_DARK = (20, 28, 66, 255)
CREAM = (234, 223, 200, 255)


def build() -> Image.Image:
    image = Image.new("RGBA", (SIZE, SIZE), TRANSPARENT)
    pixels = image.load()

    # Card body: x 2..13, y 3..12 (12x10), one pixel of margin all round so the outline is visible
    # against a dark inventory background.
    for y in range(3, 13):
        for x in range(2, 14):
            pixels[x, y] = CARD

    # Gold 1px frame.
    for x in range(2, 14):
        pixels[x, 3] = GOLD
        pixels[x, 12] = GOLD
    for y in range(3, 13):
        pixels[2, y] = GOLD
        pixels[13, y] = GOLD

    # Inner shadow along the bottom-right inside the frame, so the card does not look flat.
    for x in range(3, 13):
        pixels[x, 11] = CARD_DARK
    for y in range(4, 11):
        pixels[12, y] = CARD_DARK

    # Cream name band across the middle: the part of a PCR card that carries the character's name.
    for x in range(4, 12):
        pixels[x, 8] = CREAM
        pixels[x, 9] = CREAM
    pixels[4, 8] = GOLD_DARK
    pixels[11, 9] = GOLD_DARK

    # A small diamond emblem above the band, standing in for the rarity stars.
    for x, y in ((7, 4), (8, 4), (6, 5), (9, 5), (7, 5), (8, 5), (7, 6), (8, 6)):
        pixels[x, y] = GOLD

    return image


if __name__ == "__main__":
    out = "src/main/resources/assets/astrum/textures/item/character_card.png"
    build().save(out, format="PNG", optimize=True)
    print(f"wrote {out}")
