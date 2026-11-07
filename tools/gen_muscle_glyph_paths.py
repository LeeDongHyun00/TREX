"""2D 근육도(atlas-2d.js)에서 Compose 가 그릴 작은 근육 글리프 경로를 Kotlin 상수로 굽는다.

재생성: python3 tools/gen_muscle_glyph_paths.py            (표준 라이브러리만 쓴다)
대조:   python3 tools/gen_muscle_glyph_paths.py --check    (커밋된 파일과 다르면 종료 코드 1)
미리보기: --preview PATH 에 PNG 를 쓴다(PIL 이 있을 때만, 없으면 건너뛴다).

입력은 research/muscle_load/export_atlas_2d.py 가 만든 app/src/main/assets/muscle_load/atlas-2d.js —
뷰(front/back)마다 첫 항목(key "")이 몸 실루엣, 그 뒤가 근육 영역(Muscle.name + "_L"/"_R")이다.
경로는 정수 좌표 폴리라인 "M x,y L x,y … Z"(부분경로 여럿 가능)인데 점이 5,700개가 넘어 목록 행마다 그리기엔 크다.
그래서 부분경로마다 Ramer-Douglas-Peucker 로 점을 줄인다(허용 오차 TOLERANCE viewBox 단위 — 글리프는 수십 dp 라 1.5 단위는 0.1 dp 도 안 된다).
닫힌 부분경로는 닫힌 채 두고, 줄인 뒤 점이 3개 미만이거나 넓이가 0인 부분경로는 버린다(어느 키가 잃었는지 요약에 찍는다).
출력이 TARGET_BYTES 를 넘으면 허용 오차를 FALLBACK_TOLERANCE 로 올려 다시 만들고 그 사실을 말한다.
맵의 항목 순서는 atlas 의 칠하는 순서 그대로다(뒤에 온 영역이 위 — §130 손 그린 근사 영역이 덮는 근육 위에 온다).
"""
import argparse
import json
import math
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/assets/muscle_load/atlas-2d.js'
TARGET = ROOT / 'app/src/main/java/com/example/trex_kotlin/MuscleGlyphPaths.kt'
PREFIX = 'window.TREX_ATLAS_2D='
TOLERANCE = 1.5
FALLBACK_TOLERANCE = 2.0
FINE_TOLERANCE = 0.5  # 영역이 통째로 퇴화할 때 가장 넓은 고리 하나에만 쓰는 고운 허용 오차
TARGET_BYTES = 25 * 1024
MAX_LITERAL = 30_000  # JVM 문자열 상수 65,535바이트 한계 훨씬 아래에서 `+` 로 나눈다
VIEWS = ('front', 'back')
PREVIEW_FILLED = {'QUADS_L', 'QUADS_R', 'GLUTES_L', 'GLUTES_R'}

_TOKEN = re.compile(r'\s*(?:([MLZmlz])|(-?\d+(?:\.\d+)?)\s*[,\s]\s*(-?\d+(?:\.\d+)?))')


def parse_path(path):
    """SVG 경로 → 부분경로 목록 [(점 목록, 닫힘 여부)]. M/L/Z 와 정수(또는 소수) 좌표만 받는다."""
    subpaths, points, closed, pos = [], [], False, 0
    while pos < len(path):
        match = _TOKEN.match(path, pos)
        if not match:
            raise ValueError(f'경로를 읽을 수 없습니다: {path[pos:pos + 20]!r}')
        pos = match.end()
        command = match.group(1)
        if command is None:
            points.append((float(match.group(2)), float(match.group(3))))
        elif command.upper() == 'M':
            if points:
                subpaths.append((points, closed))
            points, closed = [], False
        elif command.upper() == 'Z':
            closed = True
            subpaths.append((points, closed))
            points, closed = [], False
    if points:
        subpaths.append((points, closed))
    return subpaths


def rdp(points, tolerance):
    """열린 폴리라인의 Ramer-Douglas-Peucker. 양 끝점은 항상 남긴다(재귀 대신 스택)."""
    count = len(points)
    if count < 3:
        return list(points)
    keep = [False] * count
    keep[0] = keep[-1] = True
    stack = [(0, count - 1)]
    while stack:
        first, last = stack.pop()
        if last - first < 2:
            continue
        (ax, ay), (bx, by) = points[first], points[last]
        dx, dy = bx - ax, by - ay
        length = math.hypot(dx, dy)
        farthest, index = -1.0, -1
        for i in range(first + 1, last):
            px, py = points[i]
            distance = math.hypot(px - ax, py - ay) if length == 0 else abs(dy * (px - ax) - dx * (py - ay)) / length
            if distance > farthest:
                farthest, index = distance, i
        if farthest > tolerance:
            keep[index] = True
            stack.append((first, index))
            stack.append((index, last))
    return [p for p, k in zip(points, keep) if k]


def area(points):
    return 0.5 * abs(sum(x0 * y1 - x1 * y0 for (x0, y0), (x1, y1) in zip(points, points[1:] + points[:1])))


def simplify_ring(points, tolerance):
    """닫힌 고리는 첫 점에서 가장 먼 점으로 둘로 갈라 각각 줄인 뒤 잇는다(시작점==끝점 퇴화를 피한다)."""
    ring = list(points)
    if len(ring) > 1 and ring[0] == ring[-1]:
        ring.pop()
    if len(ring) < 3:
        return []
    origin = ring[0]
    split = max(range(1, len(ring)), key=lambda i: (ring[i][0] - origin[0]) ** 2 + (ring[i][1] - origin[1]) ** 2)
    head = rdp(ring[:split + 1], tolerance)
    tail = rdp(ring[split:] + [origin], tolerance)
    result = head[:-1] + tail[:-1]
    return result if len(result) >= 3 and area(result) >= 0.5 else []


def simplify_path(path, tolerance):
    """경로 하나를 줄인다 → (부분경로 목록, 원래 점 수, 줄인 점 수, 원래 부분경로 수, 원본 유지 여부).

    영역의 부분경로가 전부 퇴화하면(앞면 광배근은 팔 밑으로 2×21 px 만 보인다) 키가 뷰에서 사라지지 않도록
    가장 넓은 고리 하나를 더 고운 허용 오차로, 그래도 안 되면 원본 그대로 남긴다 — 요약에 '원본 유지' 로 찍는다.
    """
    kept, before, after = [], 0, 0
    subpaths = parse_path(path)
    for points, closed in subpaths:
        before += len(points)
        simplified = simplify_ring(points, tolerance) if closed else (rdp(points, tolerance) if len(points) >= 2 else [])
        if closed and len(simplified) < 3 or not closed and len(simplified) < 2:
            continue
        after += len(simplified)
        kept.append((simplified, closed))
    fallback = False
    rings = [points for points, closed in subpaths if closed and len(points) >= 3]
    if not kept and rings:
        largest = max(rings, key=area)
        ring = simplify_ring(largest, FINE_TOLERANCE) or (largest[:-1] if largest[0] == largest[-1] else largest)
        kept, after, fallback = [(ring, True)], len(ring), True
    return kept, before, after, len(subpaths), fallback


def format_number(value):
    rounded = round(value)
    return str(rounded) if abs(value - rounded) < 1e-9 else f'{value:.1f}'


def format_path(subpaths):
    pieces = []
    for points, closed in subpaths:
        pieces.append('M' + 'L'.join(f'{format_number(x)},{format_number(y)}' for x, y in points) + ('Z' if closed else ''))
    return ''.join(pieces)


def inside(point, ring):
    """레이 캐스팅 점-다각형 포함 판정(구멍 세기용)."""
    x, y = point
    hit = False
    for (x0, y0), (x1, y1) in zip(ring, ring[1:] + ring[:1]):
        if (y0 > y) != (y1 > y) and x < (x1 - x0) * (y - y0) / (y1 - y0) + x0:
            hit = not hit
    return hit


def count_holes(subpaths):
    rings = [points for points, closed in subpaths if closed]
    return sum(1 for i, ring in enumerate(rings) if any(inside(ring[0], other) for j, other in enumerate(rings) if j != i))


def kotlin_literal(text, indent):
    """`"…"` 리터럴. MAX_LITERAL 을 넘으면 `+` 로 줄을 나눈다. 경로 문자에는 이스케이프할 것이 없음을 확인한다."""
    assert not re.search(r'["\\$]', text), '경로 문자열에 이스케이프가 필요한 문자가 있습니다'
    chunks = [text[i:i + MAX_LITERAL] for i in range(0, len(text), MAX_LITERAL)] or ['']
    return (' +\n' + ' ' * indent).join(f'"{chunk}"' for chunk in chunks)


def load_atlas():
    text = SOURCE.read_text(encoding='utf-8').strip()
    assert text.startswith(PREFIX), f'{SOURCE.name} 가 {PREFIX!r} 로 시작하지 않습니다'
    return json.loads(text[len(PREFIX):].rstrip(';'))


def build(atlas, tolerance):
    """atlas → (Kotlin 본문, 통계). 통계는 요약 출력과 미리보기가 쓴다."""
    width, height = (float(v) for v in atlas['viewBox'].split()[2:4])
    stats = {'tolerance': tolerance, 'views': {}, 'shapes': {}}
    lines = [
        'package com.example.trex_kotlin',
        '',
        '/*',
        ' * 생성 파일 — `tools/gen_muscle_glyph_paths.py` 가 `app/src/main/assets/muscle_load/atlas-2d.js` 에서 만든다. 손으로 고치지 않는다.',
        ' * 재생성 `python3 tools/gen_muscle_glyph_paths.py`, 대조 `--check`. atlas-2d.js 의 sourceSHA256(3D 원본 atlas.js 의 SHA-256)은 SOURCE_SHA256.',
        f' * 앞·뒷면 실루엣과 근육 영역을 Ramer-Douglas-Peucker(허용 오차 {tolerance:g} viewBox 단위)로 줄인 SVG 경로 — 목록 행·홈 타일의 작은 근육 글리프용(BodyGlyph 의 막대 사람을 대신한다).',
        f' * 좌표계는 viewBox 0 0 {width:g} {height:g}, 경로는 "M x,y L x,y … Z"(정수)이고 부분경로가 여럿일 수 있다. 실루엣엔 팔·몸통 사이 같은 구멍이 있으니 even-odd 로 채운다.',
        ' * 맵의 순서는 칠하는 순서다(뒤에 온 영역이 위 — §130 손 그린 근사 영역이 덮는 근육 위에 온다). 키는 Muscle.name + "_L"/"_R".',
        ' */',
        'internal object MuscleGlyphPaths {',
        f'    const val VIEW_W = {width:g}f',
        f'    const val VIEW_H = {height:g}f',
        f'    const val SOURCE_SHA256 = "{atlas["sourceSHA256"]}"',
    ]
    bodies, maps = {}, {}
    for view in VIEWS:
        entries = atlas[view]
        assert entries and entries[0]['key'] == '', f'{view}: 첫 항목이 실루엣(key "")이어야 합니다'
        total_before = total_after = subs_before = subs_after = holes = 0
        for entry in entries:
            key = entry['key'] or f'{view}:body'
            kept, before, after, original, fallback = simplify_path(entry['path'], tolerance)
            stats['shapes'][f'{view}/{key}'] = (original, len(kept), before, after, fallback)
            total_before += before
            total_after += after
            subs_before += original
            subs_after += len(kept)
            holes += count_holes(kept)
            if entry['key'] == '':
                bodies[view] = kept
            else:
                assert kept, f'{view}/{key}: 줄였더니 부분경로가 하나도 남지 않았습니다'
                maps.setdefault(view, []).append((entry['key'], kept))
        stats['views'][view] = {'points': (total_before, total_after), 'subpaths': (subs_before, subs_after),
                                'regions': len(maps[view]), 'holes': holes}
    for view in VIEWS:
        lines.append(f'    val {view}Body: String = {kotlin_literal(format_path(bodies[view]), 8)}')
    lines.append('    /** 근육 키(Muscle.name + "_L"/"_R") → SVG 경로. 순서 = 칠하는 순서. */')
    for view in VIEWS:
        lines.append(f'    val {view}: Map<String, String> = mapOf(')
        for key, kept in maps[view]:
            lines.append(f'        "{key}" to {kotlin_literal(format_path(kept), 12)},')
        lines.append('    )')
    lines.append('}')
    stats['bodies'], stats['maps'] = bodies, maps
    return '\n'.join(lines) + '\n', stats


def generate(atlas):
    """기본 허용 오차로 만들고, 너무 크면 FALLBACK_TOLERANCE 로 한 번 더."""
    text, stats = build(atlas, TOLERANCE)
    if len(text.encode('utf-8')) > TARGET_BYTES:
        print(f'{TOLERANCE:g} 로는 {len(text.encode("utf-8")):,} bytes 라 목표 {TARGET_BYTES:,} bytes 를 넘습니다. 허용 오차를 {FALLBACK_TOLERANCE:g} 로 올립니다.')
        text, stats = build(atlas, FALLBACK_TOLERANCE)
    return text, stats


def summarize(text, stats):
    size = len(text.encode('utf-8'))
    print(f'허용 오차 {stats["tolerance"]:g} viewBox 단위, Kotlin {size:,} bytes (목표 {TARGET_BYTES:,}) {"OK" if size <= TARGET_BYTES else "초과"}')
    all_keys = set()
    for view in VIEWS:
        v = stats['views'][view]
        (pb, pa), (sb, sa) = v['points'], v['subpaths']
        print(f'  {view}: 근육 영역 {v["regions"]}개, 점 {pb:,} -> {pa:,} ({pa / pb:.0%}), 부분경로 {sb} -> {sa}, 구멍(중첩 부분경로) {v["holes"]}')
        all_keys.update(key for key, _ in stats['maps'][view])
    print(f'  키 합집합 {len(all_keys)}개')
    lost = [(name, o, k) for name, (o, k, _, _, _) in stats['shapes'].items() if k < o]
    if lost:
        print('  부분경로를 잃은 키: ' + ', '.join(f'{name} {o}->{k}' for name, o, k in lost))
    else:
        print('  부분경로를 잃은 키 없음')
    raw = [name for name, (_, _, _, _, fallback) in stats['shapes'].items() if fallback]
    if raw:
        print(f'  전부 퇴화해 가장 넓은 고리를 {FINE_TOLERANCE:g} 단위(또는 원본)로 유지한 키: ' + ', '.join(raw))


def render_preview(stats, path):
    """앞·뒷면 180x600 씩 나란히 — 실루엣 연회색, 영역 윤곽, PREVIEW_FILLED 는 초록 채움. 사람이 단순화 정도를 볼 용도."""
    try:
        from PIL import Image, ImageChops, ImageDraw
    except ImportError:
        print('PIL 이 없어 미리보기 PNG 는 건너뜁니다.')
        return
    panel_w, panel_h, scale = 180, 600, 0.5
    image = Image.new('RGB', (panel_w * len(VIEWS), panel_h), 'white')
    draw = ImageDraw.Draw(image)

    def scaled(points, offset_x):
        return [(offset_x + x * scale, y * scale) for x, y in points]

    def even_odd_mask(subpaths, offset_x):
        mask = Image.new('1', image.size, 0)
        for points, _ in subpaths:
            ring = Image.new('1', image.size, 0)
            ImageDraw.Draw(ring).polygon(scaled(points, offset_x), fill=1)
            mask = ImageChops.logical_xor(mask, ring)
        return mask

    def outline(subpaths, offset_x, color):
        for points, closed in subpaths:
            pts = scaled(points, offset_x)
            draw.line(pts + pts[:1] if closed else pts, fill=color, width=1)

    for column, view in enumerate(VIEWS):
        offset_x = column * panel_w
        image.paste((221, 221, 221), mask=even_odd_mask(stats['bodies'][view], offset_x))
        outline(stats['bodies'][view], offset_x, (160, 160, 160))
        for key, subpaths in stats['maps'][view]:
            if key in PREVIEW_FILLED:
                image.paste((76, 175, 80), mask=even_odd_mask(subpaths, offset_x))
            outline(subpaths, offset_x, (110, 110, 110))
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path)
    print(f'미리보기: {path}')


def main():
    sys.stdout.reconfigure(errors='replace')
    sys.stderr.reconfigure(errors='replace')
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--check', action='store_true', help='다시 만들어 커밋된 Kotlin 파일과 대조만 한다')
    parser.add_argument('--preview', type=Path, default=Path(tempfile.gettempdir()) / 'muscle_glyph_preview.png',
                        help='미리보기 PNG 경로(기본: 임시 폴더). --check 땐 그리지 않는다')
    args = parser.parse_args()
    text, stats = generate(load_atlas())
    summarize(text, stats)
    if args.check:
        current = TARGET.read_text(encoding='utf-8') if TARGET.exists() else None
        if current != text:
            print(f'{TARGET.relative_to(ROOT)} 가 atlas-2d.js 와 어긋납니다. python3 tools/gen_muscle_glyph_paths.py 로 다시 만드세요.')
            raise SystemExit(1)
        print(f'{TARGET.relative_to(ROOT)} 일치')
        return
    TARGET.write_text(text, encoding='utf-8', newline='\n')
    print(f'썼습니다: {TARGET.relative_to(ROOT)}')
    render_preview(stats, args.preview)


if __name__ == '__main__':
    main()
