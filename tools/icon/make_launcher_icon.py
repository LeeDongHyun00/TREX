# 앱 실행 아이콘 생성(시안 C — 어두운 배경 + 로그인 화면 로고). 적응형 아이콘(108dp, 보이는 원 지름 72dp) 전경·단색 + 옛 방식 아이콘.
# 실행: python tools/icon/make_launcher_icon.py app/src/main/res   (Pillow 필요, 저장소 루트에서)
import sys, os
from PIL import Image, ImageDraw
out = sys.argv[1]
src = Image.open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "res", "drawable-nodpi", "login_animation_frame_10.png")).convert("RGBA")
logo = src.crop(src.getchannel("A").point(lambda a: 255 if a > 20 else 0).getbbox())
BG = (0x1F, 0x26, 0x18, 255)
dens = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
# 보이는 중심 = 불투명 픽셀 무게중심(종이 조각이 왼쪽 위로 더 삐져나와 그림 사각형 중심과 다르다).
# 크기는 무게중심에서 가장 먼 조각 끝이 반지름 34dp(보이는 원 36dp, 안전 원 33dp)에 오게 한다.
import math
_op = [(x, y) for y in range(logo.height) for x in range(logo.width) if logo.getpixel((x, y))[3] > 20]
CX = sum(p[0] for p in _op) / len(_op); CY = sum(p[1] for p in _op) / len(_op)
RMAX = max(math.hypot(x - CX, y - CY) for x, y in _op)
def fg(px):
    k = (34 / 108 * px) / RMAX
    a = logo.resize((round(logo.width * k), round(logo.height * k)), Image.LANCZOS)
    c = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    c.alpha_composite(a, (round(px / 2 - CX * k), round(px / 2 - CY * k))); return c
def mono(px):
    f = fg(px); alpha = f.getchannel("A")
    m = Image.new("RGBA", (px, px), (255, 255, 255, 0)); m.putalpha(alpha); return m
def legacy(px, round_):
    # 48dp 아이콘: 108dp 중 가운데 72dp 를 보여 준다
    big = int(px * 108 / 72); c = Image.new("RGBA", (big, big), BG); c.alpha_composite(fg(big))
    off = (big - px) // 2; v = c.crop((off, off, off + px, off + px))
    m = Image.new("L", (px, px), 0); d = ImageDraw.Draw(m)
    if round_: d.ellipse((0, 0, px - 1, px - 1), fill=255)
    else: d.rounded_rectangle((0, 0, px - 1, px - 1), radius=int(px * 0.18), fill=255)
    r = Image.new("RGBA", (px, px), (0, 0, 0, 0)); r.paste(v, (0, 0), m); return r
for name, k in dens.items():
    d = os.path.join(out, f"mipmap-{name}"); os.makedirs(d, exist_ok=True)
    fg(int(108 * k)).save(os.path.join(d, "ic_launcher_foreground.png"), optimize=True)
    mono(int(108 * k)).save(os.path.join(d, "ic_launcher_monochrome.png"), optimize=True)
    legacy(int(48 * k), False).save(os.path.join(d, "ic_launcher.webp"), lossless=True)
    legacy(int(48 * k), True).save(os.path.join(d, "ic_launcher_round.webp"), lossless=True)
print("ok")
