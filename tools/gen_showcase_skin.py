"""Generates the 64x64 skin for the 演出 (showcase) placeholder model.

Same rule as tools/gen_character_book_texture.py and tools/gen_character_card_texture.py: this slice
has no art pipeline, so the file is drawn here pixel by pixel and the committed PNG is exactly what
this script writes — regenerate with

    python tools/gen_showcase_skin.py

**What this is:** a deliberately crude, hand-written placeholder. It is a box-person in the colours of
the Djeeta card art (deep blue coat, gold trim, blonde hair, a red headband), laid out for the cube
UVs of ``assets/astrum/geo/djeeta.geo.json`` at that file's declared 64x64. Flat colours only: no
gradient, no shading pass, no anti-aliasing. Its job is to make six bones read as a person against
Minecraft's grass, not to look like the character.

**What this is not:** final art. The repository's real UB assets (``effect/ub_slash.png``,
``effect/ub_shockwave.png``, the 6* card art) are not involved. A human artist replacing this file
changes only this file, as long as the UV rectangles keep their places.

**The box-UV layout, because getting it wrong paints the wrong face.** For a cube of
``size = [w, h, d]`` anchored at ``uv = [u, v]`` the net is ``2w + 2d`` wide and ``d + h`` tall
(the vanilla player skin's head is the 8x8x8 case: 32x16 at [0, 0]). Within it:

    band 1, rows v .. v+d-1        : up   at u+d, width w    | down at u+d+w, width w
    band 2, rows v+d .. v+d+h-1    : east at u,     width d  | south at u+d,   width w
                                     west at u+d+w, width d  | north at u+2d+w, width w

``south`` is the face the entity looks at, so it is the only one a viewer normally sees head-on; the
script paints every face anyway, so the figure does not turn into a paper cut-out from the side.

The six cubes below therefore occupy (and do not overlap):

    head  8x8 x 8  uv [ 0,  0]  ->  0..31 x  0..15
    body  8x12x 4  uv [ 0, 16]  ->  0..23 x 16..31
    larm  4x12x 4  uv [ 0, 32]  ->  0..15 x 32..47
    rarm  4x12x 4  uv [24, 32]  -> 24..39 x 32..47
    lleg  4x12x 4  uv [ 0, 48]  ->  0..15 x 48..63
    rleg  4x12x 4  uv [24, 48]  -> 24..39 x 48..63
"""

from PIL import Image

SIZE = 64

# Djeeta's palette, as flat colours. Kept in one block so a human re-skin starts here.
COAT = (38, 58, 120, 255)          # deep blue coat
COAT_DARK = (24, 36, 78, 255)      # collar / trouser shadow
GOLD = (232, 194, 90, 255)         # trim, belt, headband clasp
GOLD_DARK = (168, 132, 46, 255)    # trim shadow
SKIN = (246, 214, 186, 255)        # face and hands
SKIN_DARK = (208, 168, 138, 255)   # chin / neck shadow
HAIR = (240, 202, 92, 255)         # blonde
HAIR_DARK = (196, 154, 52, 255)    # hair shadow
BOOT = (58, 44, 36, 255)           # boots
EYE = (40, 44, 72, 255)            # eyes
HEADBAND = (196, 60, 60, 255)      # the red headband

EMPTY = (0, 0, 0, 0)


def fill(pixels, x0, y0, w, h, colour):
    """Paints one rectangle, clipped to the texture."""
    for y in range(max(0, y0), min(SIZE, y0 + h)):
        for x in range(max(0, x0), min(SIZE, x0 + w)):
            pixels[x, y] = colour


def box(pixels, u, v, w, h, d, up, down, east, south, west, north):
    """Paints one cube's box-UV net (the layout in the module docstring)."""
    fill(pixels, u + d, v, w, d, up)
    fill(pixels, u + d + w, v, w, d, down)
    fill(pixels, u, v + d, d, h, east)
    fill(pixels, u + d, v + d, w, h, south)
    fill(pixels, u + d + w, v + d, d, h, west)
    fill(pixels, u + 2 * d + w, v + d, w, h, north)


def build() -> Image.Image:
    image = Image.new("RGBA", (SIZE, SIZE), EMPTY)
    pixels = image.load()

    # ------------------------------------------------------------ head, 8x8x8 at uv [0, 0]
    box(pixels, 0, 0, 8, 8, 8, HAIR, SKIN_DARK, HAIR, SKIN, HAIR, HAIR)
    # The front (south) face is at u+d = 8, v+d = 8, 8x8. Paint the fringe, the face and the band.
    fill(pixels, 8, 8, 8, 3, HAIR)          # fringe
    fill(pixels, 8, 11, 8, 5, SKIN)         # face
    fill(pixels, 8, 11, 8, 1, HEADBAND)     # the red headband, right under the fringe
    fill(pixels, 9, 13, 1, 1, EYE)          # left eye
    fill(pixels, 13, 13, 1, 1, EYE)         # right eye
    fill(pixels, 11, 15, 2, 1, SKIN_DARK)   # chin shadow, so the head is not one flat block
    # The back of the head is hair, not skin.
    fill(pixels, 8 + 8 + 8 + 8, 8, 8, 8, HAIR_DARK)   # north face at u+2d+w = 24

    # ------------------------------------------------------------ body, 8x12x4 at uv [0, 16]
    box(pixels, 0, 16, 8, 12, 4, COAT_DARK, COAT_DARK, COAT_DARK, COAT, COAT, COAT_DARK)
    # Front (south) at 0+4 = 4, 16+4 = 20, 8x12.
    fill(pixels, 4, 20, 8, 2, GOLD)         # collar
    fill(pixels, 4, 30, 8, 2, GOLD_DARK)    # belt
    fill(pixels, 7, 22, 2, 8, GOLD)         # gold placket down the middle of the coat

    # ------------------------------------------------------------ left arm, 4x12x4 at uv [0, 32]
    box(pixels, 0, 32, 4, 12, 4, COAT, SKIN, COAT, COAT, COAT, COAT)
    fill(pixels, 4, 40, 4, 3, SKIN)         # the hand, on the front face's bottom rows
    fill(pixels, 4, 32 + 4, 4, 8 - 4, COAT)  # keep the sleeve above the hand

    # ------------------------------------------------------------ right arm, 4x12x4 at uv [24, 32]
    box(pixels, 24, 32, 4, 12, 4, COAT, SKIN, COAT, COAT, COAT, COAT)
    fill(pixels, 28, 40, 4, 3, SKIN)
    fill(pixels, 28, 36, 4, 4, COAT)

    # ------------------------------------------------------------ left leg, 4x12x4 at uv [0, 48]
    box(pixels, 0, 48, 4, 12, 4, COAT_DARK, BOOT, COAT_DARK, COAT_DARK, COAT_DARK, COAT_DARK)
    fill(pixels, 4, 56, 4, 4, BOOT)         # boot, on the front face's bottom rows

    # ------------------------------------------------------------ right leg, 4x12x4 at uv [24, 48]
    box(pixels, 24, 48, 4, 12, 4, COAT_DARK, BOOT, COAT_DARK, COAT_DARK, COAT_DARK, COAT_DARK)
    fill(pixels, 28, 56, 4, 4, BOOT)

    return image


if __name__ == "__main__":
    out = "src/main/resources/assets/astrum/textures/entity/djeeta.png"
    build().save(out)
    print(out)
