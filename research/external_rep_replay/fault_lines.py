# -*- coding: utf-8 -*-
"""자세 교정 대사 목록 — 서비스가 보는 모든 틀린 자세 경우와 그때 하는 말을 코드에서 뽑아 `docs/exercises/FAULT_LINES.md` 로 쓴다(spec §90).

손으로 옮기지 않는다: 반복 검사는 재생기 `--checks`(RepFormSpecs 정본), 창 규칙은 규칙 JSON + PostureCoach 의 CoachCues 표에서 읽는다.
대사를 고치면 이 스크립트를 다시 돌려 문서를 새로 만든다.

사용: python fault_lines.py            (문서를 쓴다)
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

import run_replay

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
OUT = REPO / "docs" / "exercises" / "FAULT_LINES.md"
RULES = REPO / "app" / "src" / "main" / "assets" / "posture" / "rules_mp_v0.json"
COACH_KT = REPO / "app" / "src" / "main" / "java" / "com" / "example" / "trex_kotlin" / "posture" / "PostureCoach.kt"
REPFORM_KT = REPO / "app" / "src" / "main" / "java" / "com" / "example" / "trex_kotlin" / "posture" / "RepForm.kt"
EXERCISES = [("바벨 스쿼트", "기본 스쿼트"), ("덤벨 컬", "덤벨 컬 · 바벨 컬(같은 검사)"), ("스텝 포워드 다이나믹 런지", "런지 · 바벨 런지(같은 검사)")]
REL = {"START_DELTA", "START_RATIO", "SET_MIN_DELTA", "FIRST_REPS_DELTA", "SET_LOW_DELTA"}

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


def checks(ex: str) -> list[dict]:
    r = subprocess.run([str(run_replay.REPLAY_BIN), "--checks", ex], capture_output=True, check=True)
    return [json.loads(line) for line in r.stdout.decode("utf-8").splitlines() if line.strip()]


def when_spoken(c: dict) -> str:
    if c["status"] != "SHIP":
        return "화면 '참고'만 — 음성 없음(원칙 #2: 검증 전)"
    if not c["gates"]:
        return "같은 검사 **2회 연속** 위반일 때 문장(횟수는 빼지 않음)"
    two = f" · 코칭 단계(> {c['hi']})는 문장만, 차단 단계(> {c['gateHi']})면 회 제외" if c["gateHi"] is not None else " · 위반 = 회 제외"
    return "COACH: 첫 위반부터 문장 + \"이 회는 세지 않았어요\", 12초 안 재위반은 짧은 단서" + two


def cell(x) -> str:
    return "—" if x in (None, "") else str(x).replace("|", "/")


def sentence(c: dict) -> str:
    parts = []
    if c["hi"] is not None and c["highText"]:
        parts.append(f"(높음) {c['highText']}")
    if c["lo"] is not None and c["lowText"]:
        parts.append(f"(낮음) {c['lowText']}")
    return " / ".join(parts) or "—"


def coach_cues() -> list[tuple[str, str, str, str]]:
    text = COACH_KT.read_text(encoding="utf-8")
    return re.findall(r'e\("([^"]+)", "([^"]+)", "([^"]+)", "([^"]+)"\)', text)


def spine_cues() -> dict[str, tuple[str, str]]:
    text = COACH_KT.read_text(encoding="utf-8")
    return {k: (h, d) for k, _, h, d in re.findall(r'"(\w+)" to CoachCue\("([^"]+)", "([^"]+)", "([^"]+)"\)', text)}


def superseded() -> set[str]:
    src = REPFORM_KT.read_text(encoding="utf-8")
    ids = set(re.findall(r'"((?:바벨 스쿼트|덤벨 컬|바벨 컬|\$LUNGE|\$BARBELL_LUNGE)\|[^"]+)" to "repform', src))
    return {i.replace("$LUNGE", "스텝 포워드 다이나믹 런지").replace("$BARBELL_LUNGE", "바벨 런지") for i in ids}


def window_rules(ex: str) -> list[str]:
    rules = json.loads(RULES.read_text(encoding="utf-8"))["rules"]
    sup = superseded()
    cues = coach_cues()
    spine = spine_cues()
    rows = []
    for r in rules:
        if r["exercise"] != ex or r["status"] == "exclude" or r["id"] in sup:
            continue
        cond = r["id"].split("|", 1)[1]
        sub = re.search(r"\[(\w+)\]", cond)
        if "척추" in cond and sub and sub.group(1) in spine:
            habit, drift = spine[sub.group(1)]
        else:
            m = next(((h, d) for pat, _, h, d in cues if re.search(pat, cond)), ("—", "—"))
            habit, drift = m
        speak = "화면 '참고'만" if r["status"] != "ship" else "COACH 음성(세트 창, 반복과 무관)"
        rows.append(f"| {cell(cond)} | {r['status'].upper()} | {speak} | {cell(habit)} | {cell(drift)} |")
    return rows


def main() -> int:
    L = ["# 자세 교정 대사 목록 (스쿼트 · 컬 · 런지)", "",
         "> 생성 파일 — `research/external_rep_replay/fault_lines.py` 가 코드에서 뽑는다(손으로 고치지 않는다, spec §90).",
         "> 대사를 바꾸려면 `RepFormSpecs`(반복 검사)·`CoachCues`(창 규칙)를 고치고 스크립트를 다시 돌린다.", "",
         "공통 정책:",
         "- **음성은 COACH 모드만** — TRACK 은 본인 기준 기록이라 자세 문장을 말하지 않는다(원칙 #3).",
         "- **beta 는 화면 '참고'만** — 검증 전 검사는 말하지 않는다(원칙 #2). 폰 지정 오류 세트로 검출률을 재면 ship 으로 올린다.",
         "- **한 회의 말은 한 문장**으로 이어 보낸다: 자세 사유 → 쪽·방향 안내 → 처음부터 알림(§63 후속 2).",
         "- **처음부터 알림**: 본인 기준이 모집단 기준 범위 밖에서 출발하면 세트에서 한 번(§90). 첫 반복들은 모집단 사전값으로 넓게 판정한다.", ""]
    for ex, title in EXERCISES:
        cs = checks(ex)
        L += [f"## {title}", "", "### 반복마다 보는 자세", "",
              "| 검사 | 상태 | 말하는 조건 | 틀렸을 때 말 | 고치는 말 | 짧은 단서 | 처음부터 알림 | 멈춘 자세 |",
              "|---|---|---|---|---|---|---|---|"]
        for c in cs:
            notice = c["noticeText"] if (c["noticeText"] and (c["ref"] in REL or c["refNotice"] is not None)) else None
            name = " · ".join(c["id"].split("|")[2:])      # '발 간격|시작' 처럼 하위 이름이 있으면 함께
            L.append(f"| {cell(name)} | {c['status']}{'' if c['gates'] else ' · 코칭만'} | {when_spoken(c)} | {cell(sentence(c))} | "
                     f"{cell(c['fix'])} | {cell(c['cue'])} | {cell(notice)} | {cell(c['liveText'])} |")
        rows = window_rules(ex)
        if rows:
            L += ["", "### 세트 창 규칙(반복과 무관)", "", "| 규칙 | 상태 | 말하는 조건 | 처음부터 | 점점 |", "|---|---|---|---|---|"] + rows
        L.append("")
    L += ["## 그 밖에 말하는 것", "",
          "| 상황 | 말 | 조건 |", "|---|---|---|",
          "| 덤벨 컬 덜 올림(부분) | \"끝까지 올리지 않았어요. 덤벨을 어깨 앞까지 올려 주세요. 이 회는 세지 않았어요.\" | COACH, 세트당 처음 2번은 문장, 그 뒤 \"덜 올려서 세지 않았어요.\" |",
          "| 덤벨 컬 덜 폄(부분) | \"팔을 끝까지 펴지 않았어요. 내릴 때 팔꿈치를 다 펴 주세요. 이 회는 세지 않았어요.\" | 위와 같음, 짧게 \"덜 펴서 세지 않았어요.\" |",
          "| 런지 카운터가 놓친 얕은 걸음 | 앞무릎 깊이 문장 + \"이 걸음은 세지 않았어요.\" | COACH, 깊이 검사와 같은 쿨다운 |",
          "| 런지 한쪽 목표 채움 | \"이제 {쪽} 다리를 앞으로 내디뎌 주세요.\" | 쪽마다 한 번, 쪽을 모르면 화면만 |",
          "| 런지 끝난 쪽으로 더 디딤 | \"{쪽}은 다 했어요. {반대쪽} 다리를 앞으로 해 주세요.\" | 8초에 한 번 |",
          "| 런지 옆으로 돌아섬 | \"옆으로 많이 돌아섰어요. 휴대폰 쪽으로 조금 돌아 45도쯤 비스듬히 서 주세요.\" | 3걸음 이어지면 세트에서 한 번 |",
          "| 런지 정면으로 섬 | \"정면으로 서면 깊이와 좌우를 볼 수 없어요. 휴대폰에서 45도쯤 비스듬히 서 주세요.\" | 3걸음 이어지면 세트에서 한 번 |",
          "| 컬 옆으로 너무 돌아섬 | \"옆으로 너무 돌아서 이 회는 자세를 못 봤어요. 조금 덜 돌아 주세요.\" | 15초에 한 번 |",
          "| 준비 — 안 보이는 부위 | \"발이 화면 아래로 잘렸어요 …\" · \"왼손이 안 보여요 …\" · \"머리가 화면 위로 잘렸어요 …\" 등 | 대기 중 3.5초 뒤, 카운트다운 중 멈추면 0.6초 뒤(§89) |",
          ""]
    OUT.write_bytes("\n".join(L).replace("\n", "\r\n").encode("utf-8"))
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
