"""把 AI 生成的原始大图处理成 Minecraft 可用贴图。

原始图（1254x1254 或 1536x1024，RGBA 透明底）放在仓库**外**的
`K:\\AI\\Minecraft\\dsh-image-gen\\`，它们体积大且运行期不需要，所以不进 git。
本脚本负责裁边、缩放、命名，输出到 `src/main/resources/assets/astrum/textures/`。

为什么不能直接把大图丢进游戏：
  · 物品图标按惯例是 16x16；直接缩 1254→16 会糊，必须先按 alpha 裁边再等比缩放
  · 特效/UI 纹理只需 256 或 512，大图平白占体积
  · 大图四周有大量透明留白，直接缩放会让主体变得很小

缩放策略按素材类型分开：
  · 像素画（物品图标）→ NEAREST，保住硬边，绝不插值
  · 柔光特效（弧光/冲击波/边框/圆盘）→ LANCZOS，保住羽化边缘

用法：python prepare_textures.py [--check]
"""

import sys
from pathlib import Path

from PIL import Image

SRC = Path(r"K:\AI\Minecraft\dsh-image-gen")
REPO = Path(r"K:\AI\Minecraft\PrincessConnet")
OUT = REPO / "src" / "main" / "resources" / "assets" / "astrum" / "textures"

# (源文件名, 目标相对路径, 边长或(宽,高), 是否像素画, 边距比例)
JOBS = [
    ("image-e7706a1b.png", "item/character_card.png", (32, 32), True, 0.04),
    ("image-3e1932e7.png", "item/character_book.png", (32, 32), True, 0.04),
    ("image-b75f25ac.png", "effect/ub_slash.png", (256, 256), False, 0.02),
    ("image-4880169f.png", "effect/ub_shockwave.png", (256, 256), False, 0.02),
    ("image-f6a07acf.png", "gui/ub_frame.png", (512, 256), False, 0.0),
    ("image-235ca18d.png", "gui/wheel_plate.png", (512, 512), False, 0.0),
]


def trim(im: Image.Image) -> Image.Image:
    """按 alpha 裁掉四周全透明的边。没有 alpha 就原样返回。"""
    if im.mode != "RGBA":
        im = im.convert("RGBA")
    a = im.getchannel("A")
    box = a.getbbox()
    if box is None:
        return im
    return im.crop(box)


def to_target(im: Image.Image, target: tuple[int, int], pixel_art: bool, margin: float) -> Image.Image:
    """等比缩放并居中贴到目标尺寸的画布上。"""
    im = trim(im)
    tw, th = target
    # 留边：保证金边不被切掉
    avail_w = max(1, int(tw * (1.0 - 2 * margin)))
    avail_h = max(1, int(th * (1.0 - 2 * margin)))
    scale = min(avail_w / im.width, avail_h / im.height)
    new_size = (max(1, round(im.width * scale)), max(1, round(im.height * scale)))
    resample = Image.NEAREST if pixel_art else Image.LANCZOS
    im = im.resize(new_size, resample)

    canvas = Image.new("RGBA", (tw, th), (0, 0, 0, 0))
    canvas.paste(im, ((tw - new_size[0]) // 2, (th - new_size[1]) // 2), im)
    return canvas


def report(path: Path) -> str:
    im = Image.open(path)
    a = im.getchannel("A")
    lo, hi = a.getextrema()
    return (f"{path.relative_to(OUT).as_posix():28} {im.size[0]}x{im.size[1]} "
            f"{path.stat().st_size / 1024:7.1f} KB  alpha {lo}-{hi}")


def main() -> int:
    check_only = "--check" in sys.argv
    missing = [s for s, *_ in JOBS if not (SRC / s).is_file()]
    if missing:
        print("缺少源图：")
        for m in missing:
            print("  ", SRC / m)
        return 2

    print(f"源目录 {SRC}\n输出到 {OUT}\n")
    written = []
    for src_name, rel, target, pixel_art, margin in JOBS:
        dest = OUT / rel
        if not check_only:
            dest.parent.mkdir(parents=True, exist_ok=True)
            im = Image.open(SRC / src_name)
            out = to_target(im, target, pixel_art, margin)
            out.save(dest, "PNG", optimize=True)
        if dest.is_file():
            written.append(report(dest))
        else:
            print(f"缺少输出 {dest}")

    for line in written:
        print(" ", line)
    print(f"\n共 {len(written)} 个贴图")
    return 0


if __name__ == "__main__":
    sys.exit(main())
