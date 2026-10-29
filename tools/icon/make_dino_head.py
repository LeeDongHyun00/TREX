# 로그인·로그인 로딩 화면의 공룡 얼굴(trex_dino_head.png) 생성 — 종이를 찢고 나온 로고(마지막 프레임)에서 공룡만 오린다.
# 실행: python3 tools/icon/make_dino_head.py   (Pillow·numpy·scipy 필요, 저장소 루트에서)
#
# 왜 이렇게 오리나:
# - 종이와 공룡이 둘 다 초록 계열이라 밝기로는 못 가른다. 색상(hue)으로는 갈린다 — 종이 ~70°, 공룡 ~86°.
# - 공룡 이마의 노란 하이라이트도 종이 색상대라, '바깥(투명)과 이어진 종이색 덩어리'만 종이로 본다.
# - 원본 외곽선은 검은 구멍 배경과 같은 색이라 남길 수 없다. 오린 모양 둘레에 같은 색 선을 고르게 다시 그린다.
# - 뒷머리·목은 종이에 가려 잘린 자리라 들쭉날쭉하다. 손으로 정한 곡선(아래 CUT)으로 매끈하게 자른다.
# - 원본 얼굴은 폭 ~215px 뿐이라 3배로 키운다. 모양(마스크)은 흐린 뒤 키워 계단을 없애고, 선은 3배 해상도에서 그려 선명하다.
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFilter
from scipy import ndimage as ndi

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
SRC = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi", "login_animation_frame_16.png")
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi", "trex_dino_head.png")
SCALE = 3
WINDOW = (240, 255, 490, 495)  # 원본 프레임(720×1280)에서 공룡이 있는 창
LINE = (20, 28, 8)             # 원본 외곽선 색
# 종이에 잘린 뒷머리·목을 대신할 곡선(원본 좌표). 왼쪽·위는 공룡보다 바깥이라 자르지 않는다.
CUT = [(300, 240), (440, 262), (458, 278), (468, 300), (473, 330), (474, 362), (471, 395),
       (465, 420), (452, 442), (432, 458), (405, 466), (378, 466), (350, 470), (300, 480), (230, 480), (230, 240)]


def disk(r):
    y, x = np.mgrid[-r:r + 1, -r:r + 1]
    return x * x + y * y <= r * r


def hue_sat_lum(rgba):
    rgb = rgba[..., :3] / 255.0
    mx, mn = rgb.max(-1), rgb.min(-1)
    d = mx - mn
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    hue = np.zeros_like(mx)
    m = d > 1e-6
    gi = m & (mx == g)
    hue[gi] = 60 * ((b[gi] - r[gi]) / d[gi]) + 120
    ri = m & (mx == r) & ~gi
    hue[ri] = (60 * ((g[ri] - b[ri]) / d[ri])) % 360
    bi = m & (mx == b) & ~gi & ~ri
    hue[bi] = 60 * ((r[bi] - g[bi]) / d[bi]) + 240
    sat = np.where(mx > 0, d / np.maximum(mx, 1e-6), 0)
    return hue, sat, rgb.mean(-1) * 255


def dino_mask(rgba):
    """원본 해상도의 공룡 모양(이빨·눈·얼굴 안 선 포함)."""
    hue, sat, lum = hue_sat_lum(rgba)
    visible = rgba[..., 3] > 8
    colored = visible & (lum >= 30) & (sat >= 0.18)
    paperish = colored & (hue < 77)
    outside = ndi.binary_dilation(~visible, structure=disk(2))
    lab, _ = ndi.label(paperish)
    touching = np.unique(lab[outside & paperish])
    paper = ndi.binary_closing(np.isin(lab, touching[touching > 0]), structure=disk(2))
    candidate = colored & ~paper
    lab, n = ndi.label(candidate)
    sizes = ndi.sum(candidate, lab, range(1, n + 1))
    mask = lab == (1 + int(np.argmax(sizes)))
    mask = ndi.binary_fill_holes(ndi.binary_closing(mask, structure=disk(4)))
    white = visible & (lum > 150) & (sat < 0.25)  # 바깥쪽 앞니
    mask |= white & ndi.binary_dilation(mask, structure=disk(8))
    mask = ndi.binary_fill_holes(ndi.binary_closing(mask, structure=disk(3)))
    return mask & ~paper


def catmull_rom(points, steps=24):
    ring = points + points[:3]
    out = []
    for i in range(len(points)):
        p0, p1, p2, p3 = [np.array(ring[i + k], float) for k in range(4)]
        for t in np.linspace(0, 1, steps, endpoint=False):
            out.append(0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t
                              + (-p0 + 3 * p1 - 3 * p2 + p3) * t ** 3))
    return out


def main():
    frame = Image.open(SRC).convert("RGBA")
    mask = dino_mask(np.array(frame).astype(float))
    ox, oy, ex, ey = WINDOW
    crop = frame.crop(WINDOW)
    w, h = crop.size
    soft = ndi.gaussian_filter(mask[oy:ey, ox:ex].astype(float), 1.1)
    big = np.array(Image.fromarray((soft * 255).astype(np.uint8)).resize((w * SCALE, h * SCALE), Image.BICUBIC)) / 255.0
    shape = big > 0.5
    cut = Image.new("L", (w * SCALE, h * SCALE), 0)
    ImageDraw.Draw(cut).polygon([((x - ox) * SCALE, (y - oy) * SCALE) for x, y in catmull_rom(CUT)], fill=255)
    shape &= np.array(cut) > 127
    shape = ndi.binary_fill_holes(ndi.binary_opening(shape, structure=disk(4)))

    art = np.array(crop.resize((w * SCALE, h * SCALE), Image.LANCZOS)
                   .filter(ImageFilter.UnsharpMask(radius=2.2, percent=70, threshold=2))).astype(float)
    inner = ndi.binary_erosion(shape, structure=disk(4))
    outer = ndi.binary_dilation(shape, structure=disk(7))
    mix = ndi.gaussian_filter(inner.astype(float), 1.2)  # 그림 → 선으로 자연스럽게
    out = np.zeros_like(art)
    for k in range(3):
        out[..., k] = art[..., k] * mix + LINE[k] * (1 - mix)
    out[..., 3] = np.clip(ndi.gaussian_filter(outer.astype(float), 1.0) * 255, 0, 255)

    head = Image.fromarray(out.astype(np.uint8), "RGBA")
    left, top, right, bottom = head.getbbox()
    head = head.crop((left - 6, top - 6, right + 6, bottom + 6))
    side = max(head.size)  # 정사각 캔버스 가운데 — 화면이 그림 상자를 가운데 두면 얼굴도 가운데
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.alpha_composite(head, ((side - head.width) // 2, (side - head.height) // 2))
    canvas.save(OUT, optimize=True)
    print(OUT, canvas.size)


if __name__ == "__main__":
    main()
