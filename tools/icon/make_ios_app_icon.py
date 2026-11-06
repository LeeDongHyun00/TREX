# iPhone 앱 아이콘(1024×1024, 불투명) 생성 — 로그인 화면의 공룡 로고(TrexDinoBadge: 검정 원 + 원 지름의 86% 공룡 얼굴)를 그대로 옮긴다.
# 배경은 로그인 화면 바탕(밝은 테마 bg #EFF1EA). iOS 가 모서리를 둥글게 깎으므로 정사각으로 두고, App Store 아이콘은 투명이 안 되어 불투명으로 저장한다.
# 실행: python3 tools/icon/make_ios_app_icon.py   (Pillow 필요, 저장소 루트에서) — 결과는 iosApp/Trex/Assets.xcassets/AppIcon.appiconset
import os
from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
SRC = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi", "trex_dino_head.png")
OUT = os.path.join(ROOT, "iosApp", "Trex", "Assets.xcassets", "AppIcon.appiconset", "AppIcon-1024.png")
SIZE = 1024
BG = (0xEF, 0xF1, 0xEA)   # TrexLightColors.bg — 로그인 화면 바탕
CIRCLE = 0.84             # 원 지름 / 아이콘 폭 — 둥근 모서리에 닿지 않고 로고가 아이콘을 채운다
DINO_IN_BADGE = 0.86      # SignInUi.kt 의 DINO_IN_BADGE 와 같다(머리 끝이 원 반지름의 88% 안)
SUPER = 4                 # 원 가장자리를 매끈하게 — 4배로 그려 줄인다

big = Image.new("RGB", (SIZE * SUPER, SIZE * SUPER), BG)
d = round(SIZE * SUPER * CIRCLE)
o = (SIZE * SUPER - d) // 2
ImageDraw.Draw(big).ellipse((o, o, o + d - 1, o + d - 1), fill=(0, 0, 0))
icon = big.resize((SIZE, SIZE), Image.LANCZOS).convert("RGBA")

dino_px = round(SIZE * CIRCLE * DINO_IN_BADGE)
dino = Image.open(SRC).convert("RGBA").resize((dino_px, dino_px), Image.LANCZOS)
icon.alpha_composite(dino, ((SIZE - dino_px) // 2, (SIZE - dino_px) // 2))

os.makedirs(os.path.dirname(OUT), exist_ok=True)
icon.convert("RGB").save(OUT, optimize=True)
print(OUT)
