"""루틴 프리셋 정본(research/routines/routine_presets.json) → app/.../routines/RoutinePresets.kt (§131).

자료집(2026-11-07, docs/EXERCISE_EXPANSION_46.md)의 루틴 60개를 JSON 으로 옮긴 것이 정본이고 이 스크립트가 Kotlin 데이터 파일을 만든다.
손으로 Kotlin 을 고치지 않는다. 종목 이름은 WorkoutCatalog.kt 의 앱 이름이어야 한다(아니면 실패).

    python3 tools/gen_routine_presets.py          # 생성
    python3 tools/gen_routine_presets.py --check  # CI: 생성본이 정본과 같은지
"""
import argparse, json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'research/routines/routine_presets.json'
OUT = ROOT / 'app/src/main/java/com/example/trex_kotlin/routines/RoutinePresets.kt'
CATALOG = ROOT / 'app/src/main/java/com/example/trex_kotlin/WorkoutCatalog.kt'


def kstr(s: str) -> str:
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"').replace('$', '\\$') + '"'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    data = json.loads(SRC.read_text(encoding='utf-8'))
    names = set(re.findall(r'WorkoutTemplate\("([^"]+)"', CATALOG.read_text(encoding='utf-8')))
    lines = [
        'package com.example.trex_kotlin.routines',
        '',
        '// tools/gen_routine_presets.py 가 research/routines/routine_presets.json 에서 만든다(§131). 손으로 고치지 않는다.',
        '// 자료: docs/EXERCISE_EXPANSION_46.md 의 자료집 루틴 60개(부위별 기본 R01~R16 · 관심 목표 T01~T10 × 장비 4종). 세트·반복·휴식은 자료의 시작안이다.',
        '',
        'val routineTopics: List<RoutineTopic> = listOf(',
    ]
    for t in data['topics']:
        lines.append(f"    RoutineTopic({kstr(t['id'])}, {kstr(t['title'])}, {t['daysPerWeek']}, {kstr(t['schedule'])}, {kstr(t['evidence'])}),")
    lines += [')', '', 'val routinePresets: List<RoutinePreset> = listOf(']
    for r in data['routines']:
        items = []
        for it in r['items']:
            if it['name'] not in names:
                sys.exit(f"카탈로그에 없는 종목: {it['name']} ({r['id']})")
            items.append(f"        PresetItem({kstr(it['name'])}, {it['sets']}, {it['min']}, {it['max']}, {kstr(it['unit'])}, {'true' if it['perSide'] else 'false'}, {it['rest']}),")
        lines.append(f"    RoutinePreset({kstr(r['id'])}, {kstr(r['title'])}, {kstr(r['topic']) if r.get('topic') else 'null'}, {kstr(r['equipment'])}, {kstr(r['part'])}, {kstr(r['level'])}, {kstr(r['minutes'])}, {kstr(r['required'])}, {kstr(r['note'])}, listOf(")
        lines += items
        lines.append('    )),')
    lines += [')', '']
    text = '\n'.join(lines)
    if args.check:
        if OUT.read_text(encoding='utf-8') != text:
            sys.exit('RoutinePresets.kt 가 정본과 다릅니다 — python3 tools/gen_routine_presets.py')
        print('RoutinePresets.kt 정본 일치')
    else:
        OUT.write_text(text, encoding='utf-8', newline='\n')
        print(f"루틴 {len(data['routines'])}개 · 주제 {len(data['topics'])}개 생성")


if __name__ == '__main__':
    main()
