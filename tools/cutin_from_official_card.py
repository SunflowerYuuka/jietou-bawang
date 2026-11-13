"""从官方 6★ 卡面派生 mod 内使用的姬塔 UB cut-in 图。

为什么是「裁剪 + 缩放」而不是 AI 重绘：
  `K:\\AI\\Minecraft\\assets\\pcr\\card\\full\\105761.webp` 是官方 6★ 卡面（1408x792，金发姬塔、
  白金配色），是唯一「画对了」的源。把它交给图像模型重绘过一次，结果把头发改成了粉色、并且留住了
  背景——即「人物不对、构图不对」。所以这里只做几何操作：裁一块 4:3，Lanczos 缩放，量化压体积。
  像素内容一个字都不改。

为什么不提交原图：
  原 webp 1.3 MB，进 jar 只为当一张 overlay 是浪费；而且原图是**官方素材**，README 的
  「本仓库不再分发原始素材文件」必须继续成立。所以提交的是派生的小图 + 这个可复现的脚本。

用法（Pillow 已随 DSH runtime 提供）：
  python tools/cutin_from_official_card.py           # 写文件
  python tools/cutin_from_official_card.py --check   # 只报告会写什么
"""

import sys
from pathlib import Path

from PIL import Image

SRC = Path(r"K:\AI\Minecraft\assets\pcr\card\full\105761.webp")
OUT = Path(r"K:\AI\Minecraft\PrincessConnet\src\main\resources\assets\astrum\textures\gui")

# 裁剪窗口（源图像素，左、上、右、下）。选它的理由：
#   · 4:3，与 UbEffectOverlay.CUTIN_ASPECT 一致 —— 否则 overlay 会把它拉变形
#   · 左边界 320 切掉了原图最左侧那道青色主光（那是姬塔的剑光，但作为「人物特写」会把脸挤到角落）
#   · 右边界顶到 1376，留 32px：原图最右侧有横向的金色轨迹，裁到边上会显得断掉
#   · 上下满高：卡面是竖构图的横切，上下再裁就会削掉头顶的花饰或裙摆的层次
CROP = (320, 0, 1376, 792)

# 输出尺寸。512x384 = 4:3，在 GUI scale 2 的 1920x1080 上约等于屏高的 55%（
# UbEffectOverlay.CUTIN_HEIGHT_FRACTION），像素密度够、体积可控。
SIZE = (512, 384)

# 量化色数。FASTOCTREE 在 Pillow 里上限就是 256 色，所以这里写 256——没有「更高质量」的余量可用，
# 想要更好只能不量化（约 700 KB）或换 WEBP（Minecraft 不认）。实测量化后 251 KB，渐变上看不出色带。
COLORS = 256


def main() -> int:
    dry = "--check" in sys.argv
    if not SRC.is_file():
        print(f"源图不存在: {SRC}", file=sys.stderr)
        return 2
    OUT.mkdir(parents=True, exist_ok=True)
    dest = OUT / "ub_cutin_djeeta.png"

    im = Image.open(SRC)
    src_size = im.size
    im = im.convert("RGB").crop(CROP).resize(SIZE, Image.LANCZOS)
    if dry:
        print(f"  would-write {dest.name} from {SRC.name} {src_size} crop {CROP} -> {SIZE}")
        return 0
    # 量化走 RGB：卡面是满幅不透明图（源文件带 alpha 但全是 255），RGBA 量化在 Pillow 里
    # 对 FASTOCTREE 有颜色数限制，而这里没有任何半透明要保留。量化后再补 alpha=255。
    im = im.quantize(colors=COLORS, method=Image.Quantize.FASTOCTREE).convert("RGBA")
    im.putalpha(255)
    im.save(dest, "PNG", optimize=True)
    print(f"  {dest.name:22} {im.size[0]}x{im.size[1]}  {dest.stat().st_size / 1024:6.1f} KB  "
          f"(源 {SRC.stat().st_size / 1024:.0f} KB, 裁剪 {CROP})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
