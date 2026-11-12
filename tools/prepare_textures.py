"""把 AI 生成的原始大图处理成 Minecraft 可用贴图。

原始图（1254x1254 或 1536x1024，RGBA 透明底）放在仓库**外**的
`K:\\AI\\Minecraft\\dsh-image-gen\\`，它们体积大且运行期不需要，所以不进 git。
本脚本负责裁边、缩放、命名，输出到 `src/main/resources/assets/astrum/textures/`。

为什么不能直接把大图丢进游戏：
  · 物品图标按惯例是 16x16/32x32；直接缩 1254→32 会糊，必须先按 alpha 裁边再等比缩放
  · 特效/UI 纹理只需 256~512，大图平白占体积
  · 大图四周有大量透明留白，直接缩放会让主体变得很小

缩放策略按素材类型分开：
  · 像素画（物品图标）→ NEAREST，保住硬边，绝不插值
  · 柔光特效（弧光/冲击波/边框/圆盘）→ LANCZOS，保住羽化边缘
  · 九宫格面板 → 先裁成 9 片再各自缩放，中间片透明（运行时拉伸不会糊）

用法：
    python prepare_textures.py            # 全部重新生成
    python prepare_textures.py --check    # 只报告体积/透明度，不写文件
"""

import sys
from pathlib import Path

from PIL import Image

SRC = Path(r"K:\AI\Minecraft\dsh-image-gen")
REPO = Path(r"K:\AI\Minecraft\PrincessConnet")
OUT = REPO / "src" / "main" / "resources" / "assets" / "astrum" / "textures"

# (源文件名, 目标相对路径, 尺寸, 是否像素画, 边距比例)
JOBS = [
    ("image-e7706a1b.png", "item/character_card.png", (32, 32), True, 0.04),
    ("image-3e1932e7.png", "item/character_book.png", (32, 32), True, 0.04),
    ("image-2ed66dc1.png", "item/dish_rice_bowl.png", (32, 32), True, 0.04),
    ("image-b75f25ac.png", "effect/ub_slash.png", (256, 256), False, 0.02),
    ("image-4880169f.png", "effect/ub_shockwave.png", (256, 256), False, 0.02),
    ("image-f6a07acf.png", "gui/ub_frame.png", (512, 256), False, 0.0),
    ("image-235ca18d.png", "gui/wheel_plate.png", (512, 512), False, 0.0),
    ("image-94f52940.png", "gui/divider.png", (256, 16), False, 0.0),
]

# 九宫格：源图 -> (目标前缀, 片尺寸, 边框像素数)
# 中间片留空（透明），运行时由代码把四个角固定、四条边与中心拉伸/平铺。
NINESLICE = [
    ("image-fe20f7f2.png", "gui/book_panel", 64, 22),
]


def trim(im: Image.Image) -> Image.Image:
    """按 alpha 裁掉四周全透明的边。"""
    if im.mode != "RGBA":
        im = im.convert("RGBA")
    a = im.getchannel("A")
    box = a.getbbox()
    return im.crop(box) if box else im


def to_target(im: Image.Image, target: tuple[int, int], pixel_art: bool, margin: float) -> Image.Image:
    """等比缩放并居中贴到目标尺寸的画布上。"""
    im = trim(im)
    tw, th = target
    avail_w = max(1, int(tw * (1.0 - 2 * margin)))
    avail_h = max(1, int(th * (1.0 - 2 * margin)))
    scale = min(avail_w / im.width, avail_h / im.height)
    new_size = (max(1, round(im.width * scale)), max(1, round(im.height * scale)))
    im = im.resize(new_size, Image.NEAREST if pixel_art else Image.LANCZOS)
    canvas = Image.new("RGBA", (tw, th), (0, 0, 0, 0))
    canvas.paste(im, ((tw - new_size[0]) // 2, (th - new_size[1]) // 2), im)
    return canvas


def nine_slice(src: Path, prefix: str, size: int, border: int, dry: bool) -> list[Path]:
    """切成 3x3 共 9 片。角与边的片含边框纹理，中间片保持原样（可平铺）。

    命名：<prefix>_tl/_t/_tr/_l/_c/_r/_bl/_b/_br.png
    """
    im = trim(Image.open(src).convert("RGBA"))
    # 把源图缩到 3*size 见方再切，保证每片都是 size×size、缩放一致
    im = im.resize((size * 3, size * 3), Image.LANCZOS)
    names = ["tl", "t", "tr", "l", "c", "r", "bl", "b", "br"]
    out = []
    for idx, name in enumerate(names):
        col, row = idx % 3, idx // 3
        piece = im.crop((col * size, row * size, (col + 1) * size, (row + 1) * size))
        dest = OUT / f"{prefix}_{name}.png"
        if not dry:
            dest.parent.mkdir(parents=True, exist_ok=True)
            piece.save(dest, "PNG", optimize=True)
            shrink(dest)
        out.append(dest)
    return out


SHRINK = {
    # 大面板是平滑渐变，量化会出色带；降尺寸最稳，UI 上拉伸显示看不出
    "gui/wheel_plate.png": (256, 256),
}


def shrink(path: Path) -> None:
    """压体积。

    柔光图量化到 256 色——高对比的羽化边缘看不出损失；**但大面积平滑渐变会出色带**，
    所以这类图改走降尺寸（见 SHRINK）。像素画与九宫格中心片一律不动。
    """
    rel = path.relative_to(OUT).as_posix()
    if rel.startswith("item/") or path.stem.endswith("_c"):
        return
    im = Image.open(path).convert("RGBA")
    if rel in SHRINK:
        im.resize(SHRINK[rel], Image.LANCZOS).save(path, "PNG", optimize=True)
        return
    im.quantize(colors=256, method=Image.Quantize.FASTOCTREE, dither=Image.Dither.NONE).save(
        path, "PNG", optimize=True)


def report(path: Path) -> str:
    im = Image.open(path)
    # 量化后是 P 模式，透明度在 info["transparency"] 里而不是独立通道
    if im.mode == "P":
        t = im.info.get("transparency")
        alpha = "无" if t is None else ("二值" if isinstance(t, int) else f"{min(t)}-{max(t)}")
    else:
        lo, hi = im.convert("RGBA").getchannel("A").getextrema()
        alpha = f"{lo}-{hi}"
    return (f"{path.relative_to(OUT).as_posix():32} {im.size[0]:>3}x{im.size[1]:<3} "
            f"{path.stat().st_size / 1024:7.1f} KB  mode {im.mode}  alpha {alpha}")


def main() -> int:
    dry = "--check" in sys.argv
    missing = [s for s, *_ in JOBS if not (SRC / s).is_file()]
    missing += [s for s, *_ in NINESLICE if not (SRC / s).is_file()]
    if missing:
        print("缺少源图：")
        for m in missing:
            print("  ", SRC / m)
        return 2

    written: list[Path] = []
    for src_name, rel, target, pixel_art, margin in JOBS:
        dest = OUT / rel
        if not dry:
            dest.parent.mkdir(parents=True, exist_ok=True)
            to_target(Image.open(SRC / src_name), target, pixel_art, margin).save(dest, "PNG", optimize=True)
            shrink(dest)
        written.append(dest)

    for src_name, prefix, size, border in NINESLICE:
        written += nine_slice(SRC / src_name, prefix, size, border, dry)

    for p in written:
        print(" ", report(p) if p.is_file() else f"缺少 {p}")
    total = sum(p.stat().st_size for p in written if p.is_file()) / 1024
    print(f"\n共 {len(written)} 个贴图，合计 {total:.1f} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
