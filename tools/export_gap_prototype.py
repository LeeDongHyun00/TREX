"""Export the design-canvas artboards (.dc.html) into standalone HTML screens + a gallery for the repo.

Usage: python3 export.py <canvas/project dir> <repo docs/prototype/competitor-gap dir>
The canvas source is copied verbatim under <dest>/canvas/; standalone screens go to <dest>/screens/.
"""
import json, re, shutil, sys, pathlib

src = pathlib.Path(sys.argv[1]); dest = pathlib.Path(sys.argv[2])
canvas_dir = dest / "canvas"; screens = dest / "screens"
for d in (canvas_dir, screens):
    if d.exists(): shutil.rmtree(d)
    d.mkdir(parents=True)

index = json.loads((src / "canvas.json").read_text(encoding="utf-8"))
shutil.copy(src / "canvas.json", canvas_dir / "canvas.json")

def standalone(html: str) -> str:
    html = html.replace('<script src="./support.js"></script>\n', '')
    html = re.sub(r'<script type="text/x-dc"[\s\S]*?</script>\n', '', html)
    for tag in ("x-dc", "helmet"):
        html = html.replace(f"<{tag}>\n", "").replace(f"</{tag}>\n", "").replace(f"<{tag}>", "").replace(f"</{tag}>", "")
    html = re.sub(r'href="([A-Za-z]+)\.dc\.html"', r'href="\1.html"', html)
    # center the phone on a neutral page so the screen file is viewable on its own
    html = html.replace('<body>', '<body style="background:#E9ECE4;display:flex;justify-content:center;padding:24px 0">', 1)
    return html

names = []
for name in index["order"]:
    raw = (src / name).read_text(encoding="utf-8")
    shutil.copy(src / name, canvas_dir / name)
    out = screens / name.replace(".dc.html", ".html")
    out.write_text(standalone(raw), encoding="utf-8")
    names.append((name.replace(".dc.html", ""), index["boards"][name].get("title", name), index["boards"][name]["y"]))

rows = {0: "단계 1 — 보관 분리·세트 입력·기록 (G1·G2·G9·G10)", 1200: "단계 2 — 종목 상세·보상·체중·운동 목록 (G3·G4·G5)", 2400: "단계 2·3 — 추천 루틴·요일·알림·물 · 빈 상태 (G6·G7·G11)"}
sections = []
for y, title in rows.items():
    cards = "".join(
        f'<figure><iframe src="screens/{n}.html" title="{t}" loading="lazy"></iframe><figcaption><b>{t}</b> · <a href="screens/{n}.html">열기</a></figcaption></figure>'
        for n, t, yy in names if yy == y)
    sections.append(f"<h2>{title}</h2><div class=\"grid\">{cards}</div>")

gallery = f"""<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>TREX 격차 프로토타입 v2</title>
<style>
body{{margin:0;padding:24px 16px 48px;background:#E9ECE4;font-family:-apple-system,"Noto Sans KR","Apple SD Gothic Neo","Malgun Gothic",sans-serif;color:#1F2618}}
h1{{font-size:20px;margin:0 0 4px}} .sub{{color:#5E6754;font-size:13px;margin:0 0 20px;max-width:900px;line-height:1.5}}
h2{{font-size:15px;margin:28px 0 10px;color:#4A6329}}
.grid{{display:flex;flex-wrap:wrap;gap:20px}}
figure{{margin:0;width:390px}}
iframe{{width:390px;height:844px;border:6px solid #1F2618;border-radius:40px;background:#F5F7F1;display:block}}
figcaption{{font-size:12px;color:#5E6754;margin-top:8px}} figcaption b{{color:#1F2618;font-weight:600}}
a{{color:#4A6329}}
</style>
</head>
<body>
<h1>TREX 격차 프로토타입 v2 (2026-11-05)</h1>
<p class="sub">`docs/GAP_PAGE_PLAN.md` 의 페이지를 `docs/UX_REVIEW_TOP10.md` 의 기준으로 고친 판. 화면 안 링크를 누르면 화면 사이를 오갈 수 있다. 원본(디자인 캔버스 아트보드)은 <code>canvas/</code>, 이 화면들은 거기서 <code>tools/export_gap_prototype.py</code> 로 내보낸 것이다. 숫자는 예시이고 판정·점수 계산은 들어 있지 않다.</p>
{''.join(sections)}
</body>
</html>
"""
(dest / "index.html").write_text(gallery, encoding="utf-8")
print("exported", len(names), "screens")
