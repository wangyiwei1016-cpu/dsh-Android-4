"""把一张图做成 Android 启动图标 + 界面 logo。

用法：
    python make_icon2.py 源图.png

输出：
    MyApp/app/src/main/res/mipmap-*/ic_launcher.png   （五档密度）
    MyApp/app/src/main/res/drawable-nodpi/logo.png    （透明底，给界面用）
    icons/icon_tint.png / icon_white.png              （预览图）

要点：
- API 14 只认 PNG mipmap，没有自适应图标
- 抠图按"色度"而非亮度，否则中蓝色的主体会被算成半透明
- 4 倍超采样后下采样，避免小尺寸边缘发毛
"""
import os
import sys

from PIL import Image, ImageDraw

if len(sys.argv) < 2:
    print(__doc__)
    sys.exit(1)

SRC = sys.argv[1]
HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "MyApp", "app", "src", "main", "res")
OUT = os.path.join(HERE, "icons")
os.makedirs(OUT, exist_ok=True)

src = Image.open(SRC).convert("RGB")
w, h = src.size
px = src.load()

chroma = [[max(px[x, y]) - min(px[x, y]) for x in range(w)] for y in range(h)]
flat = sorted(v for row in chroma for v in row)
peak = flat[int(len(flat) * 0.98)] or 1

alpha = Image.new("L", (w, h))
ap = alpha.load()
for y in range(h):
    for x in range(w):
        ap[x, y] = int(max(0, min(255, chroma[y][x] * 255 / peak)))

bbox = alpha.getbbox()
if bbox is None:
    print("没找到主体：图片可能是纯色或纯白")
    sys.exit(1)
alpha = alpha.crop(bbox)
subject = src.crop(bbox).convert("RGBA")
subject.putalpha(alpha)
print("主体尺寸:", subject.size)


def compose(bg, size):
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(canvas)
    if bg is not None:
        d.rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * 0.22), fill=bg + (255,))
    inner = int(size * 0.74)
    scale = min(inner / subject.width, inner / subject.height)
    wm = subject.resize(
        (max(1, int(subject.width * scale)), max(1, int(subject.height * scale))), Image.LANCZOS
    )
    canvas.alpha_composite(wm, ((size - wm.width) // 2, (size - wm.height) // 2))
    return canvas


SS = 2048
white_master = compose((255, 255, 255), SS)
tint_master = compose((238, 242, 255), SS)

white_master.resize((512, 512), Image.LANCZOS).save(os.path.join(OUT, "icon_white.png"))
tint_master.resize((512, 512), Image.LANCZOS).save(os.path.join(OUT, "icon_tint.png"))

logo = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
scale = 960 / max(subject.width, subject.height)
wm = subject.resize(
    (int(subject.width * scale), int(subject.height * scale)), Image.LANCZOS
)
logo.alpha_composite(wm, ((1024 - wm.width) // 2, (1024 - wm.height) // 2))

for name, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    folder = os.path.join(RES, "mipmap-" + name)
    os.makedirs(folder, exist_ok=True)
    tint_master.resize((size, size), Image.LANCZOS).save(os.path.join(folder, "ic_launcher.png"))
    print("写出", folder, size)

nodpi = os.path.join(RES, "drawable-nodpi")
os.makedirs(nodpi, exist_ok=True)
logo.resize((256, 256), Image.LANCZOS).save(os.path.join(nodpi, "logo.png"))
print("done")
