"""把 AI 生成的特效图转成 Minecraft 可用的「加色叠加」贴图。

为什么需要这一步：Kolors 不输出 alpha 通道（背景是实色渐变），而游戏里的特效必须能叠加
在场景上。直接把实色方块贴上去会遮住整个屏幕。

做法不是「抠图」（把背景变透明），而是**把亮度转成 alpha**：
  · 发光特效的价值全在亮度上——亮的地方应该穿透，暗的地方应该让位
  · 所以 alpha = 亮度（可带 gamma 调整），颜色统一归一化成偏白的热色
  · 结果丢进 addBlend 渲染时就是纯粹的「光」，不会带出任何背景色

另外：
  · 右下角/角落常有比例很小的一行水印，按比例裁掉边缘
  · 输出统一尺寸，量化到 256 色压体积

用法：python fx_from_ai.py [--check]
"""

import sys
from pathlib import Path

from PIL import Image

SRC = Path(os.environ.get("ASTRUM_IMAGE_GEN", os.path.join("..", "..", "dsh-image-gen")))
OUT = Path("src", "main", "resources", "assets", "astrum", "textures", "effect")

# (源文件, 目标名, 边长, 裁掉的边缘比例(左,上,右,下), 热色, gamma)
JOBS = [
    # 金白剑气：姬塔 UB 的主斩击
    ("image-385bd100.png", "ub_strike.png", 512, (0.0, 0.0, 0.0, 0.075), (255, 236, 190), 0.85),
    # 放射爆环：起手/命中瞬间
    ("image-f56d41a7.png", "ub_burst.png", 512, (0.0, 0.0, 0.0, 0.075), (255, 225, 160), 0.80),
    # 斜向能量流：切入背景
    ("image-9e77e2c2.png", "ub_flare.png", 512, (0.0, 0.0, 0.0, 0.075), (255, 240, 205), 0.90),
]


def to_light(im: Image.Image, hot: tuple[int, int, int], gamma: float) -> Image.Image:
    """把「发光的暖色特效」从灰雾背景里分离出来。

    单纯的「亮度 -> alpha」在这里不成立：这几张图的背景是**带灰度的雾气**，亮度并不低，
    结果是整块发灰、看不出是光。真正区分「光」和「雾」的是**色温**：
      · 剑气/爆环是暖金色，红蓝差很大（R 明显高于 B）
      · 雾和天空是中性或偏冷，红蓝差很小
    所以 alpha 取「暖度」与「亮度」的加权，中性灰被压到接近 0。
    """
    rgb = im.convert("RGB")
    src = rgb.load()
    w, h = rgb.size
    out = Image.new("RGBA", (w, h))
    dst = out.load()
    hr, hg, hb = hot
    for y in range(h):
        for x in range(w):
            r, g, b = src[x, y]
            lum = (r * 299 + g * 587 + b * 114) // 1000
            # 暖度：红通道相对蓝通道的优势，0..255
            warmth = max(0, r - b)
            # 只有又亮又暖的像素才是光。二者取几何平均，任一为 0 就归零。
            glow = (max(0, lum - 40) * warmth) ** 0.5
            a = int(min(255.0, (glow / 255.0) ** gamma * 255.0 * 1.6))
            if a < 12:
                dst[x, y] = (0, 0, 0, 0)
                continue
            t = a / 255.0
            dst[x, y] = (
                min(255, int(r * 0.55 + hr * 0.45)),
                min(255, int(g * 0.55 + hg * 0.45)),
                min(255, int(b * 0.40 + hb * 0.40 * t)),
                a,
            )
    return out


def main() -> int:
    dry = "--check" in sys.argv
    if not SRC.is_dir():
        print(f"源目录不存在: {SRC}", file=sys.stderr)
        return 2
    missing = [s for s, *_ in JOBS if not (SRC / s).is_file()]
    if missing:
        print("缺少源图:")
        for m in missing:
            print("  ", SRC / m)
        return 2

    OUT.mkdir(parents=True, exist_ok=True)
    for src_name, dest_name, size, (cl, ct, cr, cb), hot, gamma in JOBS:
        dest = OUT / dest_name
        if dry:
            print(f"  would-write {dest_name}")
            continue
        im = Image.open(SRC / src_name).convert("RGB")
        w, h = im.size
        if any((cl, ct, cr, cb)):
            im = im.crop((int(w * cl), int(h * ct), int(w * (1 - cr)), int(h * (1 - cb))))
        im = im.resize((size, size), Image.LANCZOS)
        out = to_light(im, hot, gamma)
        # 量化压体积；alpha 用二值化会出锯齿，所以保留 RGBA 但降低色数
        out = out.quantize(colors=200, method=Image.Quantize.FASTOCTREE).convert("RGBA")
        out.save(dest, "PNG", optimize=True)
        lo, hi = out.getchannel("A").getextrema()
        print(f"  {dest.name:18} {out.size[0]}x{out.size[1]}  {dest.stat().st_size/1024:6.1f} KB  alpha {lo}-{hi}")
    print(f"\n输出目录 {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
