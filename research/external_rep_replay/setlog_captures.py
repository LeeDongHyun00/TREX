# -*- coding: utf-8 -*-
"""휴대폰 세트 로그(sets-*.jsonl) → 피처 수준 재생 캡처(*.fcap) + index.json. 표준 라이브러리만 쓴다.

왜 있나
-------
세트 로그(`posture/PostureSetLog.kt`, 스키마 `trex.posture.setlog/1`)의 `frames[].features` 는 앱이 그 프레임에서
카운터에 넣은 피처 사전 **그 자체**다(PostureLive: 기록한 프레임 = 검출·비일시정지 프레임 = rc.onFrame 을 부른 프레임,
값은 features[rc.signal.feature]). 그래서 추론을 다시 하지 않고 이 피처를 JVM 재생기(replay-jvm, Replay.kt 의
readFeatureCapture)에 그대로 넣으면 (1) 로그를 쓴 앱의 카운트와 파리티를 확인하고 (2) 같은 세트를 새 코어·다른 신호로
다시 셀 수 있다 — 설계 §4.9 "하네스가 이 로그를 그대로 재생해 회귀를 잡는다", §6 단계 0.

로그 → 캡처 규약 (SetLogJson.encode 를 읽고 맞췄다)
    frames[].t_ms          세트 상대시각(첫 기록 프레임 = 0). 카운터는 시각 차이만 쓰므로 그대로 쓴다
    frames[].features      X 줄의 <피처>=<값>. JSON 의 숫자 문자열을 그대로 옮긴다(앱이 소수 5자리로 반올림해 적은 값)
                           빈 사전({}) = 검출됐지만 피처 계산 불가 → 재생기가 값 null 로 onFrame 을 부른다(앱과 같다)
    reps.count / t_ms / signal / valid   → 캡처 메타 loggedReps / loggedRepTimesMs / loggedSignal / loggedValid (파리티용)
    reps 블록이 없으면 카운터 미적용 세트(또는 그 필드 이전 로그) → 파리티 없음
    spec §58 단계 0 필드(있을 때만): reps.engine(return_v1 | hysteresis_v1) · reps.config(신호·게이트·ROM) → 어느 구성으로 파리티를 볼지,
    reps.resets[{t_ms,after_t_ms,reason}] → loggedResetsMs(누른 시각)·loggedResetsAfterMs(리셋 직전에 앱 카운터가 처리한 마지막 프레임,
    none = 아직 없음). 재생기는 after 가 있으면 t > after 인 첫 프레임 앞에서 resetCycle() 을 부른다 — 앱의 락 순서 그대로
    (프레임 t_ms 는 추론 전 시각이라 누른 시각으로 자르면 추론 중에 누른 전환이 한 프레임 어긋난다). reps.pending → loggedPendingMs·loggedDroppedMs,
    thermal{start,changes} · app_version → index 에 그대로
    reps.t_ms 가 절대 epoch(첫 로그의 결함, PostureLive 주석)면 loggedTimesRelative=false — 시각 파리티는 보지 않는다
    note 가 "session:<이름> floor ..." 이면 바닥 종목 경로(forSession floor=true). 세션 로그가 아니면(랩 화면) 규칙 JSON 의
    바닥 종목 목록으로 추정한다. 규칙 JSON(assets/posture/rules_*.json)의 ship/beta kind=rep 규칙이 있으면 그 ROM 방향·임계값을
    넘긴다(PostureLive 와 같은 우선순위).
없는 필드는 전부 null — 옛 로그다. 추측으로 채우지 않는다.

렙 검증 모드 로그(spec §61 — `"validation":true`, 프레임마다 `xy`·`w`·`up`) → 랜드마크 캡처 `<set_id>.cap` 도 함께 쓴다(capture_format)
    H source=phone-setlog setId exercise imageW imageH validation=1
    검출 프레임: U <t_ms> <up 그대로> 다음 F <t_ms> 1 <i>:<x>,<y>,<vis>,nan,<wx>,<wy>,<wz>
        vis = 로그 vis(= 앱의 min(visibility, presence), 소수 3자리), 없으면 nan(재생기는 1 로 본다). pres = nan — min 은 이미 vis 에 있다.
        wx·wy·wz = 로그 `w`(MediaPipe 월드 좌표 원본 부호, m) — 재생기가 앱처럼 cm·부호 반전을 한다
    검출 안 된 프레임(좌표 키 없음): F <t_ms> 0
    index 에 landmarkCapture(상대 경로)·validation·imageW·imageH. 이 캡처로 (1) 화면 잘림(프레이밍)을 재고 (2) Replay --dump-features 로
    피처를 다시 계산해 로그 피처와 견준다(gate_a.py) — 로그의 좌표 4자리·가시성 3자리 반올림 때문에 비트 단위 일치는 아니다.

표시 단위 (사용자 결정 2026-09-24 — 런지류는 "왼쪽과 오른쪽을 한 번씩 = 1회", 앱 `RepUnit`)
    reps.count · t_ms · min/max/valid · invalid 는 늘 **카운터 사이클**(런지 = 한 걸음)이다 — 재생 파리티는 이 단위로만 본다.
    reps.unit("cycle"|"side_pair") · cycles_per_rep · completed(화면에 보인 수 = 진행·자동 넘김에 쓴 수) · half_pending(세트 끝에
    짝 없이 남은 한쪽, true 일 때만 키) → index repUnit · cyclesPerRep · loggedCompleted · loggedHalfPending.
    키가 없으면 그 필드 이전 로그다 — 그때 화면은 늘 사이클 = 1회였으므로 repUnit="cycle"(repUnitFrom 에 출처).
    loggedDisplayedReps = 화면에 보인 수(completed, 없으면 count // cycles_per_rep). loggedUnitConsistent = completed·half_pending 이
    count 와 맞는가(앱 누적기는 세트 안에서 반쪽을 버리지 않으므로 completed = count // 2, half_pending = count 가 홀수).
    currentUnit = **지금 앱**이 그 종목을 보이는 단위(SIDE_PAIR_AIHUB) — 재생 카운트를 지금 화면 수로 바꿀 때 쓴다.

자가 라벨(spec §30) — 정답
    labels: rep_truth.csv(set_id,reps_min,reps_max,exercise,form,source,created_at) 와 set_labels.jsonl(또는 labels/set_labels.jsonl)
    source=edited    사용자가 스테퍼로 고친 값 → index truthReps (재생 채점의 정답)
    source=confirmed 앱 카운트를 보고 "맞아요" 한 값 → confirmedReps 에만. **순환**: 앱 카운트와 같아서, 이것을 정답으로 쓰면
                     재생 검증이 자기 답을 채점한다. --truth-confirmed 를 주면 정답에 넣되 truthSource 에 표시한다.
    라벨은 **그 세트의 화면 단위**로 적힌다(완료 화면 스테퍼는 화면에 보인 수로 시작한다). 그래서 둘 다 적는다:
        truthDisplayedReps / confirmedDisplayedReps   라벨 그대로(화면 단위, 단위는 truthUnit)
        truthCyclesMin~Max / confirmedCyclesMin~Max   카운터 사이클 범위 = 라벨 × cycles_per_rep ~ + (cycles_per_rep − 1). 좌우 짝이면
                                                      화면이 짝 없이 끝난 한쪽을 세지 않으므로 라벨 P 쌍은 2P 또는 2P+1 걸음이다.
        truthReps / confirmedReps                     **사이클 수가 하나로 정해질 때만** 그 값(재생·run_replay·parity_core 가 정답으로 쓰는
                                                      단위), 범위면 None(truthCyclesExact=false) — 한 걸음 모르는 값을 정답처럼 넣으면
                                                      짝 없는 한쪽으로 끝난 정직한 세트가 과다 카운트로 채점된다. 쌍 단위 채점은
                                                      score_phone_reps.py 가 truthDisplayedReps 로 한다.
    set_labels.jsonl 의 rep_unit(앱이 라벨을 적을 때 화면 단위, 없으면 모름) → labelUnit · labelUnitMatchesLog(로그 reps.unit 과 같은가).
    독립 정답(종이 집계표)은 score_phone_reps.py 가 계획표와 함께 붙인다(런지는 왼·오른 걸음을 따로 센다).

알려진 파리티 깨짐 경로 (재생기 머리 주석과 같다): spec §58 **이전** 로그에는 일시정지·카메라 전환의 resetCycle() 이 없다(1.5 s 넘는
틈으로만 드러난다), after_t_ms 가 없는 리셋은 누른 시각으로 자르므로 한 프레임 어긋날 수 있다, 로그 값의 소수 5자리 반올림,
로그를 쓴 빌드와 지금 빌드의 카운터·신호 차이(signalMatchesLog, live@log 행).

사용법
    python setlog_captures.py <sets-*.jsonl 또는 폴더 ...> --out <캡처 폴더> [--labels <폴더|파일 ...>] [--since 2026-09-24]
                              [--exercise 바벨 스쿼트] [--session-only] [--truth-confirmed]
    python run_replay.py <캡처 폴더> --out <결과 폴더>          # 재생 + 파리티 + 채점
    python setlog_captures.py --self-test [--out <임시 폴더>]    # 손으로 쓴 합성 로그로 왕복 검증
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import random
import re
import sys
import tempfile
from pathlib import Path

import capture_format

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
RULES = [REPO / "app" / "src" / "main" / "assets" / "posture" / "rules_mp_v0.json",
         REPO / "app" / "src" / "main" / "assets" / "posture" / "rules_floor_v0.json"]
FIXTURE_BASELINE1 = REPO / "app" / "src" / "test" / "resources" / "rep_fixture_baseline1.txt"
# 코틀린 SetLogJson 이 쓴 §58 골든 줄 — PostureSetLogTest 가 같은 줄을 요구하고, 여기 자가 검증이 그대로 읽는다(형식 어긋남 방지)
GOLDEN_FIXTURE = REPO / "app" / "src" / "test" / "resources" / "setlog_s58_fixture.txt"
SCHEMA = "trex.posture.setlog/1"
# 표시 단위(사용자 결정 2026-09-24) — ExerciseProfiles.kt 의 `lunges`(앱 이름, repUnit = SIDE_PAIR)를 PostureLive.postureExerciseMap 으로
# 옮긴 AIHub 이름(= 로그의 exercise). 자가 검증이 두 코틀린 파일을 읽어 이 표와 맞는지 본다(어긋나면 실패).
SIDE_PAIR_APP = ("런지", "바벨 런지", "사이드 런지", "크로스 런지")
SIDE_PAIR_AIHUB = frozenset({"스텝 포워드 다이나믹 런지", "바벨 런지", "사이드 런지", "크로스 런지"})
# 런지(앱 이름 "런지")는 쪽별 카운트(spec §63, 사용자 결정 2026-09-26) — 화면 수 = min(왼, 오른)(TRACK 풀). 사이클 두 개가 한 쌍인 것은 같다
SIDE_EACH_AIHUB = frozenset({"스텝 포워드 다이나믹 런지"})
UNIT_CYCLES = {"cycle": 1, "side_pair": 2, "side_each": 2}     # RepUnit.key → cyclesPerRep
PAIR_UNITS = ("side_pair", "side_each")    # 두 걸음(왼 + 오른) = 1회로 보이는 단위
PROFILES_KT = REPO / "app" / "src" / "main" / "java" / "com" / "example" / "trex_kotlin" / "posture" / "ExerciseProfiles.kt"
POSTURE_LIVE_KT = REPO / "app" / "src" / "main" / "java" / "com" / "example" / "trex_kotlin" / "PostureLive.kt"


def current_unit(exercise: str | None, floor: bool) -> str:
    """지금 앱이 이 종목(AIHub 이름)의 자동 횟수를 보이는 단위. 바닥 경로는 늘 사이클(PostureLive: isFloorExercise → CYCLE)."""
    if floor or exercise not in SIDE_PAIR_AIHUB:
        return "cycle"
    return "side_each" if exercise in SIDE_EACH_AIHUB else "side_pair"


def kotlin_side_pair_exercises() -> tuple[set[str] | None, set[str] | None]:
    """(ExerciseProfiles.kt 의 lunges 앱 이름, 그것을 postureExerciseMap 으로 옮긴 AIHub 이름). 파일이 없거나 못 읽으면 None."""
    try:
        prof = PROFILES_KT.read_text(encoding="utf-8")
        live = POSTURE_LIVE_KT.read_text(encoding="utf-8")
    except OSError:
        return None, None
    m = re.search(r"val lunges\s*=\s*setOf\(([^)]*)\)", prof)
    if not m or "SIDE_PAIR" not in prof[m.end():m.end() + 600]:
        return None, None
    apps = set(re.findall(r'"([^"]+)"', m.group(1)))
    mapping = dict(re.findall(r'^\s*"([^"]+)"\s+to\s+"([^"]+)"', live, flags=re.M))
    return apps, {mapping.get(a, f"?{a}") for a in apps}


class _Num(float):
    """JSON 숫자의 원래 문자열을 기억하는 float — 캡처에 앱이 적은 문자열을 그대로 옮기기 위해."""

    def __new__(cls, text: str):
        obj = float.__new__(cls, text)
        obj.text = text
        return obj


def _num_text(v) -> str | None:
    if v is None or isinstance(v, bool):
        return None
    if isinstance(v, _Num):
        return v.text
    if isinstance(v, int):
        return str(v)
    if isinstance(v, float):
        return repr(v) if math.isfinite(v) else None
    return None


def parse_line(line: str) -> dict:
    return json.loads(line, parse_float=_Num)


# ---------------------------------------------------------------- 입력

def log_files(paths: list[Path]) -> list[Path]:
    out = []
    for p in paths:
        if p.is_dir():
            out += sorted(p.glob("sets-*.jsonl"))
        elif p.is_file():
            out.append(p)
    return out


def iter_logs(paths: list[Path]):
    """(파일, 줄 번호, dict). 깨진 줄은 건너뛰고 stderr 에 적는다 — 한 줄이 나머지를 막지 않게."""
    for f in log_files(paths):
        for i, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            try:
                d = parse_line(line)
            except json.JSONDecodeError as e:
                print(f"skip {f.name}:{i}: {e}", file=sys.stderr)
                continue
            if d.get("schema") not in (None, SCHEMA) or "frames" not in d:
                print(f"skip {f.name}:{i}: not a set log", file=sys.stderr)
                continue
            yield f, i, d


def load_labels(paths: list[Path]) -> dict[str, dict]:
    """set_id → {reps, source, form, createdAt}. 같은 세트의 라벨이 여럿이면 created_at 이 늦은 것."""
    files = []
    for p in paths:
        if p.is_dir():
            files += [q for q in (p / "rep_truth.csv", p / "set_labels.jsonl", p / "labels" / "set_labels.jsonl") if q.is_file()]
        elif p.is_file():
            files.append(p)
    labels: dict[str, dict] = {}

    def put(set_id, reps, source, form, created, unit=None):
        if not set_id:
            return
        prev = labels.get(set_id)
        if prev is not None and (prev["createdAt"] or "") > (created or ""):
            return
        # 렙 없는 라벨(폼만)은 렙이 있는 라벨을 덮지 않는다
        if prev is not None and reps is None and prev["reps"] is not None:
            return
        # 화면 단위(set_labels.jsonl 의 rep_unit, spec §59)는 같은 라벨의 csv 줄(단위 열 없음)이 덮어 지우지 않는다
        if unit is None and prev is not None and prev.get("createdAt") == created:
            unit = prev.get("unit")
        labels[set_id] = {"reps": reps, "source": source or None, "form": form or None, "createdAt": created, "unit": unit}

    for f in files:
        if f.suffix == ".csv":
            with f.open(newline="", encoding="utf-8") as h:
                for row in csv.DictReader(h):
                    try:
                        reps = int(row.get("reps_max") or row.get("reps_min"))
                    except (TypeError, ValueError):
                        continue
                    put(row.get("set_id"), reps, row.get("source"), row.get("form"), row.get("created_at"))
        else:
            for line in f.read_text(encoding="utf-8").splitlines():
                if line.strip():
                    d = json.loads(line)
                    put(d.get("set_id"), d.get("actual_reps"), d.get("reps_source"), d.get("form"), d.get("created_at"), d.get("rep_unit"))
    return labels


def rule_tables() -> tuple[set[str], dict[str, tuple[str, float]]]:
    """(바닥 규칙 종목, 종목 → 첫 ship/beta kind=rep 규칙의 (방향, 임계값)). PostureLive 의 rulesFor(...).firstOrNull{kind==rep} 순서."""
    floor, rep = set(), {}
    for path in RULES:
        if not path.is_file():
            continue
        d = json.loads(path.read_text(encoding="utf-8"))
        for r in d.get("rules", []):
            if "floor" in path.name:
                floor.add(r.get("exercise"))
            if r.get("status") not in ("ship", "beta") or r.get("kind") != "rep" or not isinstance(r.get("rep"), dict):
                continue
            rep.setdefault(r["exercise"], (r["rep"]["direction"], float(r["rep"]["threshold"])))
    return floor, rep


# ---------------------------------------------------------------- 변환

def is_floor(log: dict, floor_exercises: set[str]) -> tuple[bool, str]:
    note = str(log.get("note") or "")
    if note.startswith("session:"):
        head = note.split(" assessment_end_ms=", 1)[0]
        return head.endswith(" floor"), "note"
    return log.get("exercise") in floor_exercises, "rules"


def convert(log: dict, source: str, floor_exercises: set[str], rep_rules: dict) -> tuple[str, dict]:
    set_id = str(log.get("set_id") or f"noid-{abs(hash(source)) % 10**8}")
    exercise = log.get("exercise") or ""
    floor, floor_src = is_floor(log, floor_exercises)
    reps = log.get("reps") if isinstance(log.get("reps"), dict) else None
    frames = log.get("frames") or []
    frame_times = [int(f.get("t_ms", 0)) for f in frames]

    meta = {"source": "phone_setlog", "setId": set_id, "exercise": exercise, "floor": "1" if floor else "0"}
    entry = {
        "capture": f"{set_id}.fcap", "setId": set_id, "exercise": exercise, "createdAt": log.get("created_at"),
        "subject": log.get("subject_id"), "mode": log.get("mode"),
        "view": (log.get("view") or {}).get("class") if isinstance(log.get("view"), dict) else None,
        "origin": "session" if str(log.get("note") or "").startswith("session:") else "lab",
        "floor": floor, "floorFrom": floor_src, "romDirection": None, "romThreshold": None,
        "frames": len(frames), "framesWithFeatures": sum(bool(f.get("features")) for f in frames),
        "firstMs": frame_times[0] if frame_times else None, "lastMs": frame_times[-1] if frame_times else None,
        "sampleIntervalMs": log.get("sample_interval_ms"), "delegate": log.get("delegate"), "model": log.get("model"),
        "frontCamera": log.get("front_camera"), "upFromGravity": log.get("up_from_gravity"),
        "anchorTMs": log.get("anchor_t_ms"), "assessmentEndTMs": log.get("assessment_end_t_ms"),
        "loggedReps": None, "loggedRepTimesMs": None, "loggedSignal": None, "loggedInvalid": None, "loggedValid": None,
        "loggedTimesRelative": None, "loggedEngine": None, "loggedConfig": None, "loggedMinAmp": None, "loggedResets": None,
        "loggedResetsAfterMs": None,
        "loggedPendingMs": None, "loggedDroppedMs": None, "loggedInProgress": None,
        "thermalStart": (log.get("thermal") or {}).get("start") if isinstance(log.get("thermal"), dict) else None,
        "thermalChanges": [[int(c.get("t_ms", 0)), c.get("status")] for c in (log.get("thermal") or {}).get("changes", [])]
        if isinstance(log.get("thermal"), dict) else None,
        "appVersion": log.get("app_version"), "truthReps": None, "truthSource": None, "confirmedReps": None, "labelForm": None,
        # 표시 단위 — 로그에 없으면 옛 로그(사이클 = 1회). 라벨은 이 단위로 적혀 있다(attach_labels)
        "repUnit": "cycle", "cyclesPerRep": 1, "repUnitFrom": "default(카운터 없음)",
        "currentUnit": current_unit(exercise, floor), "loggedCompleted": None, "loggedHalfPending": None,
        "loggedDisplayedReps": None, "loggedUnitConsistent": None,
        "truthDisplayedReps": None, "truthCyclesMin": None, "truthCyclesMax": None, "truthCyclesExact": None, "truthUnit": None,
        "confirmedDisplayedReps": None, "confirmedCyclesMin": None, "confirmedCyclesMax": None, "confirmedMatchesLog": None,
        "labelUnit": None, "labelUnitMatchesLog": None,
        # 렙 검증 모드(spec §61) — 좌표가 있으면 build 가 landmarkCapture 를 채운다
        "validation": bool(log.get("validation")), "imageW": (log.get("image") or {}).get("w") if isinstance(log.get("image"), dict) else None,
        "imageH": (log.get("image") or {}).get("h") if isinstance(log.get("image"), dict) else None, "landmarkCapture": None,
        "logFile": source,
    }
    if exercise in rep_rules:
        entry["romDirection"], entry["romThreshold"] = rep_rules[exercise]
    if reps is not None and reps.get("count") is not None:
        times = [int(t) for t in reps.get("t_ms") or []]
        # 첫 로그는 렙 시각을 절대 epoch 로 적었다 — 세트 상대시각(프레임과 같은 기준)이 아니면 시각 파리티를 보지 않는다
        last_frame = frame_times[-1] if frame_times else 0
        relative = all(t <= last_frame + 60_000 for t in times) if times else None
        valid = reps.get("valid")
        config = reps.get("config") if isinstance(reps.get("config"), dict) else None
        signal = reps.get("signal") or (config or {}).get("feature")
        entry.update({"loggedReps": int(reps["count"]), "loggedRepTimesMs": times, "loggedSignal": signal,
                      "loggedInvalid": reps.get("invalid"), "loggedValid": valid, "loggedTimesRelative": relative,
                      "loggedEngine": reps.get("engine"), "loggedConfig": config,
                      "loggedMinAmp": float(config["min_amp"]) if config and config.get("min_amp") is not None else None})
        if isinstance(reps.get("resets"), list):
            entry["loggedResets"] = [[int(r.get("t_ms", 0)), r.get("reason")] for r in reps["resets"]]
            meta["loggedResetsMs"] = ",".join(str(int(r.get("t_ms", 0))) for r in reps["resets"])
            # 리셋 직전에 카운터가 처리한 마지막 프레임 — 모든 리셋에 키가 있을 때만(섞인 로그는 누른 시각 기준으로 되돌아간다)
            if reps["resets"] and all("after_t_ms" in r for r in reps["resets"]):
                after = [None if r.get("after_t_ms") is None else int(r["after_t_ms"]) for r in reps["resets"]]
                entry["loggedResetsAfterMs"] = after
                meta["loggedResetsAfterMs"] = ",".join("none" if a is None else str(a) for a in after)
        if reps.get("engine"):
            meta["loggedEngine"] = str(reps["engine"])
        pend = reps.get("pending") if isinstance(reps.get("pending"), dict) else None
        if pend is not None:
            unc = pend.get("unconfirmed")
            entry["loggedPendingMs"] = int(unc["t_ms"]) if isinstance(unc, dict) else None
            entry["loggedDroppedMs"] = [int(d.get("t_ms", 0)) for d in pend.get("dropped") or []]
            entry["loggedInProgress"] = pend.get("in_progress") is not None
            meta["loggedPendingMs"] = str(entry["loggedPendingMs"]) if entry["loggedPendingMs"] is not None else "none"
            meta["loggedDroppedMs"] = ",".join(str(t) for t in entry["loggedDroppedMs"])
        meta["loggedReps"] = str(int(reps["count"]))
        meta["loggedRepTimesMs"] = ",".join(str(t) for t in times)
        meta["loggedTimesRelative"] = {True: "true", False: "false", None: "unknown"}[relative]
        if signal:
            meta["loggedSignal"] = str(signal)
        if isinstance(valid, list):
            meta["loggedValid"] = ",".join("null" if v is None else ("true" if v else "false") for v in valid)
        # 표시 단위 — count 는 사이클, completed 는 화면 수. 모르는 단위 이름은 추측하지 않고 그대로 두되 사이클 수는 로그 값만 믿는다
        unit = reps.get("unit")
        count = int(reps["count"])
        if unit is not None:
            cpr = int(reps.get("cycles_per_rep") or UNIT_CYCLES.get(str(unit), 1))
            completed = reps.get("completed")
            half = bool(reps.get("half_pending", False))
            sides = reps.get("sides") if isinstance(reps.get("sides"), dict) else None
            if str(unit) == "side_each":
                # 쪽별 카운트: completed = TRACK 풀의 min(왼, 오른) — 걸음 아닌 사이클·목표 넘은 걸음은 빠져 count // 2 와 다르다. 반쪽 대기는 없다
                track = (sides or {}).get("track") if isinstance((sides or {}).get("track"), dict) else None
                consistent = (track is not None and (completed is None or int(completed) == int(track.get("pairs", -1)))
                              and not half)
            else:
                consistent = (completed is None or int(completed) == count // cpr) and half == (count % cpr != 0)
            entry.update({"repUnit": str(unit), "cyclesPerRep": cpr, "repUnitFrom": "log",
                          "loggedCompleted": None if completed is None else int(completed), "loggedHalfPending": half,
                          "loggedDisplayedReps": int(completed) if completed is not None else count // cpr,
                          "loggedUnitConsistent": consistent, "loggedSides": sides})
            if sides is not None:
                # 재생기가 앱처럼 목표로 쪽별 상한을 두고, 쪽별 수를 로그와 견준다(paritySides)
                if sides.get("target") is not None:
                    meta["loggedSidesTarget"] = str(int(sides["target"]))
                t = sides.get("track") if isinstance(sides.get("track"), dict) else None
                if t is not None:
                    meta["loggedSidesTrack"] = ",".join(str(int(t.get(k, 0))) for k in ("L", "R", "U", "extra", "pairs"))
        else:
            entry.update({"repUnitFrom": "default(옛 로그 = 사이클)", "loggedDisplayedReps": count})

    lines = [f"# setlog_captures.py — {set_id} ({source})",
             "H\t" + "\t".join(f"{k}={str(v).replace(chr(9), ' ')}" for k, v in meta.items())]
    for f in frames:
        cells = ["X", str(int(f.get("t_ms", 0)))]
        feats = f.get("features") or {}
        for k, v in feats.items():
            text = _num_text(v)
            if text is not None and "\t" not in k and "=" not in k:
                cells.append(f"{k}={text}")
        lines.append("\t".join(cells))
    return "\n".join(lines) + "\n", entry


def landmark_capture(log: dict, entry: dict) -> str | None:
    """검증 모드 로그의 프레임 좌표 → capture_format 캡처 텍스트. 좌표(`xy`·`w`)가 있는 프레임이 하나도 없으면 None."""
    frames = log.get("frames") or []
    if not any(f.get("xy") is not None and f.get("w") is not None for f in frames):
        return None
    meta = {"source": "phone-setlog", "setId": entry["setId"], "exercise": entry["exercise"],
            "imageW": "" if entry["imageW"] is None else entry["imageW"], "imageH": "" if entry["imageH"] is None else entry["imageH"],
            "validation": "1" if entry["validation"] else "0"}
    lines = [f"# setlog_captures.py — {entry['setId']} 랜드마크 ({entry['logFile']})",
             "H\t" + "\t".join(f"{k}={str(v).replace(chr(9), ' ')}" for k, v in meta.items())]
    for f in frames:
        t = int(f.get("t_ms", 0))
        xy, w = f.get("xy"), f.get("w")
        if xy is None or w is None or len(xy) != 2 * capture_format.LANDMARKS or len(w) != 3 * capture_format.LANDMARKS:
            lines.append(capture_format.frame_line(t, None))
            continue
        if f.get("up") is not None:
            lines.append(capture_format.up_line(t, [_num_text(v) or "nan" for v in f["up"]]))
        vis = f.get("vis")
        lms = []
        for i in range(capture_format.LANDMARKS):
            v = vis[i] if isinstance(vis, list) and i < len(vis) and vis[i] is not None else None
            # None(null — NaN 이던 값)은 nan 으로: 재생기는 없는 가시성을 1 로 본다(앱의 orElse(1f))
            lms.append((xy[2 * i], xy[2 * i + 1], v, None, w[3 * i], w[3 * i + 1], w[3 * i + 2]))
        if any(x is None for lm in lms for k, x in enumerate(lm) if k not in (2, 3)):
            lines.append(capture_format.frame_line(t, None))   # 좌표가 null(NaN) 인 프레임은 재생기가 어차피 사람 없음으로 본다
            continue
        lines.append(capture_format.frame_line(t, lms))
    return "\n".join(lines) + "\n"


def attach_labels(entry: dict, labels: dict[str, dict], truth_confirmed: bool) -> None:
    """라벨은 그 세트의 화면 단위(entry repUnit)다. *DisplayedReps = 라벨 그대로, *CyclesMin~Max = 사이클 범위(라벨 × cyclesPerRep ~
    짝 없이 끝난 한쪽까지), *Reps = 범위가 한 값일 때만 그 사이클 수(아니면 None — run_replay 가 정확한 정답으로 쓴다). 사이클 단위면 넷이 같다."""
    lab = labels.get(entry["setId"])
    if not lab:
        return
    entry["labelForm"] = lab["form"]
    if lab.get("unit") is not None:
        entry["labelUnit"] = lab["unit"]
        entry["labelUnitMatchesLog"] = lab["unit"] == (entry.get("repUnit") or "cycle")
    if lab["reps"] is None:
        return
    reps, cpr = int(lab["reps"]), int(entry.get("cyclesPerRep") or 1)
    lo, hi = reps * cpr, reps * cpr + (cpr - 1)
    exact = lo if lo == hi else None
    if lab["source"] == "confirmed":
        entry.update({"confirmedReps": exact, "confirmedDisplayedReps": reps, "confirmedCyclesMin": lo, "confirmedCyclesMax": hi,
                      # 순환 라벨은 화면 수와 같아야 한다 — 같은 단위(화면 단위)로 비교한다
                      "confirmedMatchesLog": None if entry.get("loggedDisplayedReps") is None else reps == entry["loggedDisplayedReps"]})
        if not truth_confirmed:
            return
        source = "confirmed(순환)"
    else:
        source = lab["source"] or "label"
    entry.update({"truthReps": exact, "truthSource": source, "truthDisplayedReps": reps, "truthCyclesMin": lo, "truthCyclesMax": hi,
                  "truthCyclesExact": lo == hi, "truthUnit": entry.get("repUnit") or "cycle"})


def build(inputs: list[Path], out: Path, label_paths: list[Path], since: str | None = None, until: str | None = None,
          exercises: set[str] | None = None, session_only: bool = False, truth_confirmed: bool = False) -> dict:
    floor_exercises, rep_rules = rule_tables()
    labels = load_labels(label_paths)
    out.mkdir(parents=True, exist_ok=True)
    index = {"source": "phone set log (trex.posture.setlog/1) → feature capture", "kind": "features",
             "truthPolicy": "edited 라벨만 정답" + (" + confirmed(순환)" if truth_confirmed else "; confirmed 는 confirmedReps 에만"),
             "labelsLoaded": len(labels), "sets": []}
    seen = set()
    for f, i, log in iter_logs(inputs):
        created = str(log.get("created_at") or "")
        if since and created and created < since:
            continue
        if until and created and created >= until:
            continue
        if exercises and log.get("exercise") not in exercises:
            continue
        if session_only and not str(log.get("note") or "").startswith("session:"):
            continue
        text, entry = convert(log, f"{f.name}:{i}", floor_exercises, rep_rules)
        if entry["setId"] in seen:
            print(f"skip duplicate {entry['setId']} ({f.name}:{i})", file=sys.stderr)
            continue
        seen.add(entry["setId"])
        attach_labels(entry, labels, truth_confirmed)
        (out / entry["capture"]).write_text(text, encoding="utf-8")
        lm_text = landmark_capture(log, entry)
        if lm_text is not None:
            entry["landmarkCapture"] = f"{entry['setId']}.cap"   # 피처 캡처와 같은 이름 규약
            (out / entry["landmarkCapture"]).write_text(lm_text, encoding="utf-8")
        index["sets"].append(entry)
    (out / "index.json").write_text(json.dumps(index, ensure_ascii=False, indent=1), encoding="utf-8")
    return index


def read_feature_capture(path: Path) -> tuple[dict, list[tuple[int, dict]]]:
    """*.fcap 읽기 (phone_noise.py 등). 반환: (메타, [(tMs, {피처: float})])."""
    meta, frames = {}, []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if parts[0] == "H":
            for kv in parts[1:]:
                k, _, v = kv.partition("=")
                meta[k] = v
        elif parts[0] == "X":
            feats = {}
            for cell in parts[2:]:
                k, _, v = cell.rpartition("=")
                try:
                    x = float(v)
                except ValueError:
                    continue
                if math.isfinite(x):
                    feats[k] = x
            frames.append((int(parts[1]), feats))
    return meta, frames


# ---------------------------------------------------------------- 자가 검증 — SetLogJson.encode 를 따라 손으로 쓴 로그

def _kt_num(v: float | None, decimals: int = 5) -> str:
    """SetLogJson.num 과 같은 문자열 (고정 소수 → 끝 0·점 제거, NaN → null, -0 → 0)."""
    if v is None or not math.isfinite(v):
        return "null"
    s = f"{v:.{decimals}f}".rstrip("0").rstrip(".") or "0"
    return "0" if s == "-0" else s


def _kt_str(s: str | None) -> str:
    return "null" if s is None else json.dumps(s, ensure_ascii=False)


def encode_setlog(log: dict, legacy: bool = False) -> str:
    """PostureSetLog.kt SetLogJson.encode 의 필드 순서·숫자 형식을 그대로 따른 한 줄. legacy=True 는 옛 로그
    (mode·measurements·view·anchor·up_* 필드와 렙별 극값이 없던 때)."""
    p = ["{", f"\"schema\":{_kt_str(SCHEMA)},", f"\"set_id\":{_kt_str(log['set_id'])},",
         f"\"created_at\":{_kt_str(log['created_at'])},", f"\"subject_id\":{_kt_str(log.get('subject_id'))},",
         f"\"exercise\":{_kt_str(log['exercise'])},", f"\"rules_version\":{_kt_str('mp_v0.1')},",
         f"\"model\":{_kt_str('full')},", f"\"delegate\":{_kt_str('GPU')},", "\"front_camera\":false,",
         "\"up_from_gravity\":true,", f"\"tilt_deg\":{_kt_num(2.4)},", "\"sample_interval_ms\":300,"]
    if not legacy:
        p += ["\"up_flipped_frames\":0,", f"\"up_verified_frames\":{len(log['frames'])},"]
    p.append(f"\"note\":{_kt_str(log.get('note'))},")
    if not legacy:
        if log.get("mode") is not None:
            p.append(f"\"mode\":{_kt_str(log['mode'])},")
        if log.get("app_version") is not None:
            p.append(f"\"app_version\":{_kt_str(log['app_version'])},")
        if log.get("validation"):   # 렙 검증 모드(spec §61) — 제품 로그에는 키가 없다
            p.append("\"validation\":true,")
            if log.get("image") is not None:
                p.append("\"image\":{" + f"\"w\":{log['image']['w']},\"h\":{log['image']['h']}" + "},")
        p.append("\"measurements\":[" + ",".join(_kt_str(m) for m in log.get("measurements", [])) + "],")
        if log.get("assessment_end_t_ms") is not None:
            p.append(f"\"assessment_end_t_ms\":{log['assessment_end_t_ms']},")
        if log.get("anchor_t_ms") is not None:
            p.append(f"\"anchor_t_ms\":{log['anchor_t_ms']},")
        if log.get("view") is not None:
            v = log["view"]
            p.append("\"view\":{" + f"\"yaw_deg\":{_kt_num(v['yaw_deg'], 2)},\"r\":{_kt_num(v['r'], 3)},"
                     f"\"class\":{_kt_str(v['class'])},\"frames\":{v['frames']}" + "},")
        if log.get("thermal") is not None:
            th = log["thermal"]
            p.append("\"thermal\":{" + f"\"start\":{th['start']},\"changes\":["
                     + ",".join("{" + f"\"t_ms\":{t},\"status\":{st}" + "}" for t, st in th["changes"]) + "]},")
    reps = log.get("reps")
    if reps is not None:
        s = "\"reps\":{" + f"\"count\":{reps['count']},\"invalid\":{reps.get('invalid', 0)},\"signal\":{_kt_str(reps.get('signal'))},"
        s += "\"t_ms\":[" + ",".join(str(t) for t in reps["t_ms"]) + "]"
        if not legacy and reps.get("min") is not None:
            s += ",\"min\":[" + ",".join(_kt_num(x) for x in reps["min"]) + "]"
            s += ",\"max\":[" + ",".join(_kt_num(x) for x in reps["max"]) + "]"
            s += ",\"valid\":[" + ",".join("null" if x is None else ("true" if x else "false") for x in reps["valid"]) + "]"
        if not legacy and reps.get("unit") is not None:   # 표시 단위(사용자 결정 2026-09-24) — SetLogJson 과 같이 valid 뒤, engine 앞
            s += f",\"unit\":{_kt_str(reps['unit'])},\"cycles_per_rep\":{reps['cycles_per_rep']}"
            if reps.get("completed") is not None:
                s += f",\"completed\":{reps['completed']}"
            if reps.get("half_pending"):   # true 일 때만 키가 있다
                s += ",\"half_pending\":true"
        if not legacy and reps.get("engine") is not None:   # spec §58 단계 0 — SetLogJson 의 필드 순서
            c = reps["config"]
            s += f",\"engine\":{_kt_str(reps['engine'])},\"config\":{{\"feature\":{_kt_str(c['feature'])},\"min_amp\":{_kt_num(c['min_amp'])}"
            s += f",\"refractory_ms\":{c['refractory_ms']},\"max_gap_ms\":{c['max_gap_ms']}"
            s += f",\"complete_on_return\":{'true' if c.get('complete_on_return', True) else 'false'}"
            if c.get("polarity"):
                s += f",\"polarity\":{_kt_str(c['polarity'])},\"return_fraction\":{_kt_num(c['return_fraction'])}"
                later = c.get("later_window_ms")
                s += f",\"first_pair_window_ms\":{c['first_pair_window_ms']},\"later_window_ms\":{'null' if later is None else later}"
                s += f",\"min_ratio\":{_kt_num(c['min_ratio'])},\"max_ratio\":{_kt_num(c['max_ratio'])}"
            if c.get("rom_direction"):
                s += f",\"rom_direction\":{_kt_str(c['rom_direction'])},\"rom_threshold\":{_kt_num(c['rom_threshold'])}"
            s += f",\"rom_tier\":{_kt_str(c.get('rom_tier', 'reference'))}"
            if c.get("identity"):   # 반복 판별 게이트(spec §62) — 판별 신호가 있는 종목만 키가 있다
                s += f",\"identity\":{{\"feature\":{_kt_str(c['identity']['feature'])},\"min_amp\":{_kt_num(c['identity']['min_amp'])}}}"
            s += "}"
            if reps.get("rejected") is not None:   # 세지 않은 사이클 = (t_ms, min, max, swing) — 판별 신호가 있는 종목은 비어 있어도 키가 있다
                s += ",\"rejected\":[" + ",".join("{" + f"\"t_ms\":{q[0]},\"min\":{_kt_num(q[1])},\"max\":{_kt_num(q[2])},\"swing\":{_kt_num(q[3])}" + "}"
                                                for q in reps["rejected"]) + "]"
            if reps.get("identity_swing") is not None:
                s += ",\"identity_swing\":[" + ",".join(_kt_num(x) for x in reps["identity_swing"]) + "]"
            # 리셋 = (누른 시각, 사유, 리셋 직전에 카운터가 처리한 마지막 프레임 | None) — SetLogJson 과 같이 after_t_ms 를 늘 적는다
            s += ",\"resets\":[" + ",".join("{" + f"\"t_ms\":{r[0]},\"after_t_ms\":{'null' if r[2] is None else r[2]},\"reason\":{_kt_str(r[1])}" + "}"
                                            for r in reps.get("resets", [])) + "]"
            if reps.get("pending") is not None:
                pd = reps["pending"]
                cyc = lambda q: "{" + f"\"t_ms\":{q[0]},\"start_t_ms\":{q[1]},\"min\":{_kt_num(q[2])},\"max\":{_kt_num(q[3])},\"by_redescent\":{'true' if q[4] else 'false'}" + "}"
                s += ",\"pending\":{\"unconfirmed\":" + (cyc(pd["unconfirmed"]) if pd.get("unconfirmed") else "null")
                ip = pd.get("in_progress")
                s += ",\"in_progress\":" + ("null" if ip is None else "{" + f"\"start_t_ms\":{ip[0]},\"min\":{_kt_num(ip[1])},\"max\":{_kt_num(ip[2])}" + "}")
                s += ",\"dropped\":[" + ",".join(cyc(q) for q in pd.get("dropped", [])) + "]}"
        p.append(s + "},")
    fr = []
    for f in log["frames"]:
        vis = "null" if f.get("vis") is None else "[" + ",".join(_kt_num(x, 3) for x in f["vis"]) + "]"
        feats = ",".join(f"{_kt_str(k)}:{_kt_num(v)}" for k, v in f["features"].items())
        lm = "".join(f"\"{k}\":[" + ",".join(_kt_num(x, 4) for x in f[k]) + "],"
                     for k in ("xy", "w", "up") if f.get(k) is not None)   # 검증 모드 좌표 — 없으면 키도 없다
        fr.append("{" + f"\"t_ms\":{f['t_ms']},\"infer_ms\":{f['infer_ms']},\"visible\":{f['visible']},\"vis\":{vis},"
                  + lm + "\"features\":" + "{" + feats + "}}")
    p.append("\"frames\":[" + ",".join(fr) + "],")
    p.append("\"results\":[{\"rule_id\":\"x|y\",\"verdict\":\"ABSTAIN\",\"value\":null,\"n\":0,\"baseline_applied\":false,\"value_rel\":null}]}")
    return "".join(p)


def _cadence(n: int, rng: random.Random) -> list[int]:
    """앱 추론 간격 흉내(300~345 ms, 가끔 발열 감속 ~440 ms)."""
    t, out = 0, []
    for _ in range(n):
        out.append(t)
        t += 440 if rng.random() < 0.03 else rng.choice([300, 309, 317, 325, 333, 341])
    return out


def _squat_frames(rng: random.Random, reps: int, period_s: float, still_s: float, bottom: float, rest: float,
                  hold_s: float = 0.0, stride: float = 0.0) -> list[dict]:
    """무릎각 코사인 반복(휴식 rest → 바닥 bottom), 반복 사이 상단 hold_s 초 멈춤. stride > 0 이면 처음 두 반복에만
    knee_out_mean 이 stride 만큼 벌어진다(옛 런지 신호가 일부 반복만 세던 모양 — MM-Fit 재현율 0.10, README §4)."""
    frames = []
    cycle = period_s + hold_s
    total_s = still_s + reps * cycle + 2.0
    for t in _cadence(int(total_s / 0.31) + 2, rng):
        s = t / 1000.0
        if s > total_s:
            break
        u = s - still_s
        k = int(u // cycle) if u >= 0 else -1
        w = u - k * cycle
        moving = 0 <= k < reps and w < period_s
        v = (rest + bottom) / 2 + (rest - bottom) / 2 * math.cos(2 * math.pi * w / period_s) if moving else rest
        v += rng.gauss(0, 0.4)
        out = 0.06 + stride * math.sin(math.pi * w / period_s) if (stride and moving and k < 2) else 0.06 + 0.01 * math.sin(s)
        feats = {"knee_L": v + 1.2, "knee_R": v - 1.2, "knee_mean": v, "knee_minside": v - 1.2, "knee_maxside": v + 1.2,
                 "knee_asym": 2.4, "elbow_L": 165.0 + rng.gauss(0, 1), "elbow_R": 163.0 + rng.gauss(0, 1),
                 "torso_incl": 8.0 + (rest - v) * 0.3, "knee_out_mean": out, "view_cos": 0.99, "view_sin": 0.05}
        feats["elbow_mean"] = (feats["elbow_L"] + feats["elbow_R"]) / 2
        feats["elbow_minside"] = min(feats["elbow_L"], feats["elbow_R"])
        frames.append({"t_ms": t, "infer_ms": rng.randint(40, 90), "visible": 33,
                       "vis": [round(0.6 + 0.39 * rng.random(), 3) for _ in range(33)], "features": feats})
    return frames


def _config(signal: str, engine: str) -> dict:
    """RepEngineLog.of 가 적는 구성(지금 등록부의 바벨 스쿼트 신호 · forSession 구성 / 새 코어는 설계 v2 결정값)."""
    c = {"feature": signal, "min_amp": 35.0, "refractory_ms": 1200, "max_gap_ms": 1500, "complete_on_return": True,
         "rom_direction": "min", "rom_threshold": 97.8905, "rom_tier": "reference",
         # 반복 판별 게이트(spec §62) — 바벨 스쿼트 등록부: 더 편 쪽 무릎도 35° 굽어야 스쿼트
         "identity": {"feature": "knee_maxside", "min_amp": 35.0}}
    if engine == "hysteresis_v1":
        c.update({"refractory_ms": 800, "polarity": "down", "return_fraction": 0.25, "first_pair_window_ms": 8000,
                  "later_window_ms": None, "min_ratio": 0.5, "max_ratio": 2.0})
    return c


def _signal_frames(rng: random.Random, total_s: float, value) -> list[dict]:
    """knee_mean = value(s) 인 프레임(좌우 ±1.2°, 잡음 0.4°)."""
    frames = []
    for t in _cadence(int(total_s / 0.31) + 2, rng):
        if t / 1000.0 > total_s:
            break
        v = value(t / 1000.0) + rng.gauss(0, 0.4)
        frames.append({"t_ms": t, "infer_ms": rng.randint(40, 90), "visible": 33, "vis": None,
                       "features": {"knee_L": v + 1.2, "knee_R": v - 1.2, "knee_mean": v, "knee_minside": v - 1.2}})
    return frames


def _fixture_frames() -> list[dict]:
    frames = []
    for line in FIXTURE_BASELINE1.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        t, _, v = line.partition(",")
        feats = {"wrist_shoulder_d": float(v)} if v.strip() else {}
        frames.append({"t_ms": int(t), "infer_ms": 55, "visible": 25 if feats else 12,
                       "vis": None if not feats else [0.9] * 33, "features": feats})
    return frames


def _golden_logs() -> list[dict]:
    """PostureSetLogTest.goldenLogs() 와 같은 입력 — encode_setlog 가 골든 파일과 바이트까지 같은 줄을 내야 한다."""
    frames = []
    for t, knee in ((0, 170.0), (300, 131.5), (600, 92.25), (900, 168.0)):
        frames.append({"t_ms": t, "infer_ms": 55, "visible": 33, "vis": None,
                       "features": {"knee_L": knee + 1.2, "knee_R": knee - 1.2, "knee_mean": knee, "knee_minside": knee - 1.2}})
    base = {"set_id": "20260924T020000-gold0001", "created_at": "2026-09-24T02:00:30Z", "subject_id": "s-test0001",
            "exercise": "바벨 스쿼트", "note": "session:기본 스쿼트 assessment_end_ms=900 ", "mode": "coach",
            "measurements": ["참고 · 골든"], "assessment_end_t_ms": 900, "anchor_t_ms": 900,
            "view": {"yaw_deg": 3.1, "r": 0.97, "class": "C", "frames": 4}, "app_version": "0.9.1-debug",
            "thermal": {"start": 0, "changes": [(600, 1)]}, "frames": frames,
            "reps": {"count": 1, "invalid": 0, "signal": "knee_mean", "t_ms": [900], "min": [92.25], "max": [170.0],
                     "valid": [True], "engine": "return_v1", "config": _config("knee_mean", "return_v1"),
                     # 반복 판별 게이트(spec §62): 센 사이클의 판별 스윙, 세지 않은 사이클 하나(무릎 들기)
                     "rejected": [(1200, 118.0, 170.0, 12.5)], "identity_swing": [71.5],
                     "resets": [(-120, "camera_switch", None), (410, "pause", 300)]}}
    core = {**base, "set_id": "20260924T020500-gold0002", "created_at": "2026-09-24T02:05:30Z", "mode": "track",
            "reps": {"count": 0, "invalid": 0, "signal": "knee_mean", "t_ms": [], "min": [], "max": [], "valid": [],
                     "engine": "hysteresis_v1", "config": _config("knee_mean", "hysteresis_v1"), "resets": [],
                     "rejected": [], "identity_swing": [],
                     "pending": {"unconfirmed": (900, 300, 92.25, 170.0, False), "in_progress": (600, 90.0, 168.5),
                                 "dropped": [(250, 0, 120.0, 170.0, True)]}}}
    # 표시 단위 좌우 짝: 바벨 런지 3걸음 [충족, 미달, 충족] → 1회(미달 쌍) + 세트 끝에 남은 한쪽. 수·배열은 사이클 그대로
    lunge_config = {"feature": "knee_minside", "min_amp": 35.0, "refractory_ms": 1200, "max_gap_ms": 1500, "complete_on_return": True,
                    "rom_direction": "min", "rom_threshold": 112.0852, "rom_tier": "validated"}
    pair = {**base, "set_id": "20260924T021000-gold0003", "created_at": "2026-09-24T02:10:30Z", "exercise": "바벨 런지",
            "reps": {"count": 3, "invalid": 1, "signal": "knee_minside", "t_ms": [300, 600, 900], "min": [100.5, 118.75, 95.5],
                     "max": [170.0, 168.0, 169.5], "valid": [True, False, True],
                     "unit": "side_pair", "cycles_per_rep": 2, "completed": 1, "half_pending": True,
                     "engine": "return_v1", "config": lunge_config, "resets": [(450, "pause", 300)]}}
    # 렙 검증 모드(spec §61): validation·image, 검출 프레임마다 xy·w·up(이진 소수라 코틀린 float 과 반올림이 갈리지 않는다), 넷째 프레임 미검출
    lm = {"xy": [(k % 16) / 16 for k in range(66)], "w": [((k % 8) - 4) / 8 for k in range(99)], "up": [0.0, 1.0, 0.125]}
    valid_frames = [{**f, **lm} for f in frames[:3]] + [{"t_ms": 900, "infer_ms": 55, "visible": 0, "vis": None, "features": {}}]
    validation = {**base, "set_id": "20260924T021500-gold0004", "created_at": "2026-09-24T02:15:30Z", "frames": valid_frames,
                  "validation": True, "image": {"w": 360, "h": 640}}
    return [base, core, pair, validation]


def _synthetic_logs(pass2: dict | None) -> tuple[list[str], list[str], list[str]]:
    """(sets-*.jsonl 줄들, rep_truth.csv 줄들, set_labels.jsonl 줄들). pass2 = 1차 재생 결과(앱이 적었을 reps 블록)."""
    rng = random.Random(20260924)
    # 반복 사이 1 s 상단 멈춤 — 레거시 복귀형도 상단 체류를 얻어 6회를 다 세야 하는 세트(독립 기대값)
    a_frames = _squat_frames(rng, reps=6, period_s=3.0, still_s=2.5, bottom=90.0, rest=170.0, hold_s=1.0)
    # 사람은 검출됐지만 피처를 못 만든 프레임(관절 부족) — 앱은 값 null 로 onFrame 을 부른다
    a_frames[3] = {**a_frames[3], "features": {}, "vis": None, "visible": 9}
    b_frames = _squat_frames(rng, reps=5, period_s=3.5, still_s=2.0, bottom=100.0, rest=168.0, hold_s=0.8, stride=0.15)
    c_frames = _fixture_frames()
    d_frames = _squat_frames(rng, reps=3, period_s=3.0, still_s=1.0, bottom=95.0, rest=170.0)

    # G: 새 코어로 센 로그 — 준비 굽힘 1회(짝 없이 8 s 지나 버려짐) → 10 s 정지 → 6회 → 작은 굽힘 1회(진폭 비 < 0.5, 세트 끝 확정 대기)
    def g_value(s: float) -> float:
        if 1.0 <= s < 3.0:
            return 140.0 + 30.0 * math.cos(2 * math.pi * (s - 1.0) / 2.0)
        u = s - 13.0
        if 0 <= u < 24.0 and (u % 4.0) < 3.0:
            return 130.0 + 40.0 * math.cos(2 * math.pi * (u % 4.0) / 3.0)
        if 39.0 <= s < 41.0:
            return 151.0 + 19.0 * math.cos(2 * math.pi * (s - 39.0) / 2.0)
        return 170.0
    g_frames = _signal_frames(rng, 43.0, g_value)

    def reps_block(key: str, signal: str, bump: int = 0, engine: str = "return_v1", resets: list | None = None) -> dict | None:
        if pass2 is None or key not in pass2:
            # 1차 재생에도 리셋은 넣는다(앱이 그 리셋을 겪으며 셌다) — 카운트 자리는 0
            return None if not resets else {"count": 0, "invalid": 0, "signal": signal, "t_ms": [], "min": [], "max": [],
                                            "valid": [], "engine": engine, "config": _config(signal, engine), "resets": resets}
        r = pass2[key]
        cyc = r["cycles"]
        times = list(r["publishedMs"])
        if bump:
            times = times + [times[-1] + 300] if times else [0]
        block = {"count": r["reps"] + bump, "invalid": sum(c[2] is False for c in cyc), "signal": signal, "t_ms": times,
                 "min": [c[0] for c in cyc] + [0.0] * bump, "max": [c[1] for c in cyc] + [0.0] * bump,
                 "valid": [c[2] for c in cyc] + [None] * bump, "engine": engine, "config": _config(signal, engine),
                 "resets": resets or []}
        if engine == "hysteresis_v1":
            fake = lambda t: (t, t - 1500, 100.0, 170.0, False)
            block["pending"] = {"unconfirmed": fake(r["pendingMs"]) if r.get("pendingMs") is not None else None,
                                "dropped": [fake(t) for t in r["droppedMs"]]}
        return block

    logs = []
    a = {"set_id": "20260924T010000-aaaa0001", "created_at": "2026-09-24T01:00:30Z", "subject_id": "s-test0001",
         "exercise": "바벨 스쿼트", "note": "session:기본 스쿼트 assessment_end_ms=20000 ", "mode": "coach",
         "measurements": ["참고 · 합성"], "assessment_end_t_ms": 20000, "anchor_t_ms": 5500,
         "view": {"yaw_deg": 3.1, "r": 0.97, "class": "C", "frames": len(a_frames)}, "app_version": "0.9.1-debug",
         "thermal": {"start": 0, "changes": [[9000, 1]]}, "reps": reps_block("A", "knee_mean"), "frames": a_frames}
    logs.append(encode_setlog(a))
    # 옛 빌드의 런지 로그: 신호 knee_out_mean, 렙 시각 절대 epoch, mode·view·극값 없음
    b_reps = None
    if pass2 is not None and "B" in pass2:
        r = pass2["B"]
        b_reps = {"count": r["reps"], "invalid": 0, "signal": "knee_out_mean", "t_ms": [1_758_675_600_000 + t for t in r["publishedMs"]]}
    b = {"set_id": "20260901T090000-bbbb0002", "created_at": "2026-09-01T09:00:40Z", "subject_id": "s-test0001",
         "exercise": "스텝 포워드 다이나믹 런지", "note": "session:런지 assessment_end_ms=19000 ", "reps": b_reps, "frames": b_frames}
    logs.append(encode_setlog(b, legacy=True))
    c = {"set_id": "20260924T010500-cccc0003", "created_at": "2026-09-24T01:05:30Z", "subject_id": "s-test0001",
         "exercise": "푸시업", "note": "session:푸시업 floor assessment_end_ms=26000 ", "mode": "track",
         "reps": reps_block("C", "wrist_shoulder_d"), "frames": c_frames}
    logs.append(encode_setlog(c))
    d = {"set_id": "20260924T011000-dddd0004", "created_at": "2026-09-24T01:10:30Z", "subject_id": "s-test0001",
         "exercise": "바벨 스쿼트", "note": "lab", "frames": d_frames}
    logs.append(encode_setlog(d))
    # 음성 대조: A 와 같은 프레임인데 로그 카운트가 하나 많다 → 파리티가 깨져야 한다
    e = {**a, "set_id": "20260924T011500-eeee0005", "created_at": "2026-09-24T01:15:30Z", "reps": reps_block("A", "knee_mean", bump=1)}
    logs.append(encode_setlog(e))
    # F: A 와 같은 프레임, 세 번째 반복의 하강 중(11.0 s)에 카메라 전환 리셋 — 앱은 그 반복을 잃는다
    f = {**a, "set_id": "20260924T012000-ffff0006", "created_at": "2026-09-24T01:20:30Z",
         "reps": reps_block("F", "knee_mean", resets=[(11000, "camera_switch", max(fr["t_ms"] for fr in a_frames if fr["t_ms"] <= 11000))])}
    logs.append(encode_setlog(f))
    # G: 새 코어(hysteresis_v1)로 센 세트 — 파리티는 새 코어 구성으로 봐야 한다
    g = {**a, "set_id": "20260924T012500-gggg0007", "created_at": "2026-09-24T01:25:30Z", "frames": g_frames,
         "view": {"yaw_deg": 3.1, "r": 0.97, "class": "C", "frames": len(g_frames)},
         "reps": reps_block("G", "knee_mean", engine="hysteresis_v1")}
    logs.append(encode_setlog(g))
    # H: 추론 중에 누른 카메라 전환 — A 의 세 번째 반복이 완료되는 프레임(t3)을 추론하는 동안 눌렀다(누른 시각 = t3 + 40 ms).
    # 앱은 같은 락 안에서 리셋을 먼저 하고 그 프레임을 처리하므로 세 번째 반복은 완료되지 못한다 → after_t_ms = t3 직전 프레임.
    # H2: 같은 로그에서 after_t_ms 만 뺀 것(누른 시각으로만 자르던 형식) — 재생이 리셋을 한 프레임 늦게 놓아 앱과 어긋나야 한다.
    if pass2 is not None and "H_resets" in pass2:
        h = {**a, "set_id": "20260924T013000-hhhh0008", "created_at": "2026-09-24T01:30:30Z",
             "reps": reps_block("H", "knee_mean", resets=pass2["H_resets"])}
        logs.append(encode_setlog(h))
        h2 = {**h, "set_id": "20260924T013500-hhhh0009", "created_at": "2026-09-24T01:35:30Z"}
        logs.append(re.sub(r',"after_t_ms":(null|-?\d+)', "", encode_setlog(h2)))
    truth = ["set_id,reps_min,reps_max,exercise,form,source,created_at",
             "20260924T010000-aaaa0001,6,6,바벨 스쿼트,good,edited,2026-09-24T01:20:00Z",
             "20260901T090000-bbbb0002,0,0,스텝 포워드 다이나믹 런지,,confirmed,2026-09-01T09:05:00Z",
             "20260924T010500-cccc0003,4,4,푸시업,,edited,2026-09-24T01:20:10Z"]
    labels = [json.dumps({"set_id": "20260924T011000-dddd0004", "exercise": "바벨 스쿼트", "actual_reps": None,
                          "reps_source": None, "form": "good", "created_at": "2026-09-24T01:20:20Z"}, ensure_ascii=False)]
    return logs, truth, labels


def _side_pair_self_test(work: Path, check) -> None:
    """좌우 짝 표시 단위(사용자 결정 2026-09-24): 홀수 걸음 로그(짝 없이 남은 한쪽) · 라벨 단위 변환 · 같은 단위의 파리티 · 옛 로그 기본값."""
    import run_replay  # noqa: PLC0415

    rng = random.Random(20260925)
    # 런지 5걸음(상단 1 s 멈춤 — 레거시도 다 센다): 사이클 5 = 화면 2회 + 짝 없는 한쪽
    frames = _squat_frames(rng, reps=5, period_s=3.0, still_s=2.5, bottom=95.0, rest=170.0, hold_s=1.0)
    base = {"subject_id": "s-test0001", "exercise": "스텝 포워드 다이나믹 런지", "note": "session:런지 assessment_end_ms=20000 ",
            "mode": "coach", "measurements": ["참고 · 합성"], "frames": frames}
    ids = {"P1": "20260925T010000-pair0001", "P2": "20260925T010500-pair0002", "P3": "20260925T011000-pair0003",
           "P4": "20260925T011500-pair0004"}

    def logs(block_of) -> list[str]:
        out = []
        for k, (key, sid) in enumerate(ids.items()):
            out.append(encode_setlog({**base, "set_id": sid, "created_at": f"2026-09-25T01:{k * 5:02d}:30Z", "reps": block_of(key)}))
        return out

    # 1차: 카운트 자리 0 인 레거시 로그 → 재생으로 앱(같은 카운터)이 적었을 사이클 수를 얻는다
    d1 = work / "pair_logs1"
    d1.mkdir(parents=True, exist_ok=True)
    (d1 / "sets-20260925.jsonl").write_text("\n".join(logs(lambda k: None)) + "\n", encoding="utf-8")
    idx1 = build([d1], work / "pair_cap1", [d1])
    _, res1, _ = run_replay.replay_index(work / "pair_cap1", work / "pair_res1", {"live"})
    r = res1[f"{run_replay.set_key(idx1['sets'][0])}|live"]
    c = r["reps"]
    times = list(r["publishedMs"])

    def block(key: str) -> dict:
        b = {"count": c, "invalid": 0, "signal": "knee_mean", "t_ms": times, "min": [95.0] * c, "max": [170.0] * c,
             "valid": [None] * c, "engine": "return_v1", "config": _config("knee_mean", "return_v1"), "resets": []}
        b["config"].pop("rom_direction")   # 런지 knee_mean 은 ROM 기준이 없다(rom_tier reference)
        if key == "P4":
            return b                                            # 표시 단위 이전 빌드: unit 키 없음(그때 화면 = 걸음)
        b.update({"unit": "side_pair", "cycles_per_rep": 2, "completed": c // 2, "half_pending": c % 2 == 1})
        if key == "P3":
            b["completed"] = c // 2 + 1                         # 음성 대조: 화면 수가 사이클과 안 맞는 로그
        return b

    d2 = work / "pair_logs2"
    d2.mkdir(parents=True, exist_ok=True)
    (d2 / "sets-20260925.jsonl").write_text("\n".join(logs(block)) + "\n", encoding="utf-8")
    # 라벨은 화면 단위: P1 edited 2(쌍), P2 confirmed 2(쌍 — 화면 수와 같다), P4 edited 5(옛 빌드 화면 = 걸음)
    (d2 / "rep_truth.csv").write_text("\n".join([
        "set_id,reps_min,reps_max,exercise,form,source,created_at",
        f"{ids['P1']},2,2,스텝 포워드 다이나믹 런지,,edited,2026-09-25T02:00:00Z",
        f"{ids['P2']},2,2,스텝 포워드 다이나믹 런지,,confirmed,2026-09-25T02:00:01Z",
        f"{ids['P4']},5,5,스텝 포워드 다이나믹 런지,,edited,2026-09-25T02:00:02Z"]) + "\n", encoding="utf-8")
    # 앱은 같은 라벨을 labels/set_labels.jsonl 에도 쓰고 거기에만 화면 단위(rep_unit)를 적는다(SetLabelStore). P3 는 폼만 + 로그와 다른 단위(음성 대조)
    (d2 / "labels").mkdir(exist_ok=True)
    (d2 / "labels" / "set_labels.jsonl").write_text("\n".join([
        json.dumps({"set_id": ids["P1"], "exercise": "스텝 포워드 다이나믹 런지", "actual_reps": 2, "reps_source": "edited", "form": None,
                    "created_at": "2026-09-25T02:00:00Z", "rep_unit": "side_pair"}, ensure_ascii=False),
        json.dumps({"set_id": ids["P3"], "exercise": "스텝 포워드 다이나믹 런지", "actual_reps": None, "reps_source": None, "form": "good",
                    "created_at": "2026-09-25T02:00:03Z", "rep_unit": "cycle"}, ensure_ascii=False)]) + "\n", encoding="utf-8")
    idx = build([d2], work / "pair_cap2", [d2])
    by = {e["setId"][-8:]: e for e in idx["sets"]}
    p1, p2, p3, p4 = by["pair0001"], by["pair0002"], by["pair0003"], by["pair0004"]
    check("짝: 합성 5걸음을 레거시 카운터가 5사이클로 센다(홀수 — 짝 없는 한쪽이 남는다)", c == 5, str(c))
    check("짝: 로그의 unit·cycles_per_rep·completed·half_pending → index (사이클 수는 그대로)",
          p1["repUnit"] == "side_pair" and p1["cyclesPerRep"] == 2 and p1["repUnitFrom"] == "log" and p1["loggedReps"] == c
          and p1["loggedCompleted"] == c // 2 and p1["loggedHalfPending"] is (c % 2 == 1)
          # 지금 앱의 런지(스텝 포워드)는 쪽별 카운트(side_each, spec §63) — 옛 로그의 단위(side_pair)는 로그 값 그대로
          and p1["loggedDisplayedReps"] == c // 2 and p1["loggedUnitConsistent"] is True and p1["currentUnit"] == "side_each",
          json.dumps({k: p1[k] for k in ("repUnit", "cyclesPerRep", "loggedReps", "loggedCompleted", "loggedHalfPending",
                                          "loggedDisplayedReps", "loggedUnitConsistent")}, ensure_ascii=False))
    check("짝: edited 2쌍 → 화면 단위 2 · 사이클 4~5(짝 없는 한쪽) · 정확하지 않아 사이클 정답(truthReps)은 없음",
          p1["truthDisplayedReps"] == 2 and p1["truthReps"] is None and p1["truthCyclesMin"] == 4 and p1["truthCyclesMax"] == 5
          and p1["truthCyclesExact"] is False and p1["truthUnit"] == "side_pair" and p1["truthSource"] == "edited",
          f"{p1['truthDisplayedReps']} {p1['truthReps']} {p1['truthCyclesMin']}~{p1['truthCyclesMax']} {p1['truthCyclesExact']}")
    check("짝: confirmed 2쌍 → 정답 아님, 사이클 4~5(정확한 사이클 수 없음), 화면 수(completed)와 같은 단위로 비교해 일치",
          p2["truthReps"] is None and p2["confirmedDisplayedReps"] == 2 and p2["confirmedReps"] is None
          and p2["confirmedCyclesMin"] == 4 and p2["confirmedCyclesMax"] == 5 and p2["confirmedMatchesLog"] is True,
          f"{p2['confirmedDisplayedReps']} {p2['confirmedReps']} {p2['confirmedCyclesMin']}~{p2['confirmedCyclesMax']} {p2['confirmedMatchesLog']}")
    check("짝: 라벨 jsonl 의 rep_unit → labelUnit, 로그 단위와 대조(P1 일치 · P3 불일치 · P4 단위 없음), csv 줄이 단위를 지우지 않는다",
          p1["labelUnit"] == "side_pair" and p1["labelUnitMatchesLog"] is True and p3["labelUnit"] == "cycle"
          and p3["labelUnitMatchesLog"] is False and p3["labelForm"] == "good" and p3["truthReps"] is None
          and p4["labelUnit"] is None and p4["labelUnitMatchesLog"] is None,
          f"{p1['labelUnit']} {p1['labelUnitMatchesLog']} · {p3['labelUnit']} {p3['labelUnitMatchesLog']} · {p4['labelUnit']}")
    check("짝(음성 대조): completed 가 count // 2 와 다르면 loggedUnitConsistent=false", p3["loggedUnitConsistent"] is False)
    check("짝: unit 없는 옛 런지 로그 → 사이클 단위(출처 표시), 라벨 5 = 사이클 5 정확, 지금 앱 단위는 side_each(§63)",
          p4["repUnit"] == "cycle" and p4["repUnitFrom"].startswith("default") and p4["loggedDisplayedReps"] == c
          and p4["truthReps"] == 5 and p4["truthDisplayedReps"] == 5 and p4["truthCyclesExact"] is True
          and p4["currentUnit"] == "side_each" and p4["loggedHalfPending"] is None)
    _, res, _ = run_replay.replay_index(work / "pair_cap2", work / "pair_res2", {"live"})
    q1 = res[f"{run_replay.set_key(p1)}|live"]
    check("짝: 재생 파리티는 사이클끼리(재생 5 = 로그 count 5), 화면 수는 재생 // 2 = completed",
          q1.get("parityCount") is True and q1.get("parityTimes") is True and q1["reps"] // p1["cyclesPerRep"] == p1["loggedCompleted"],
          f"재생 {q1['reps']} 로그 {q1.get('loggedReps')} completed {p1['loggedCompleted']}")
    # run_replay 는 truthReps 를 정확한 사이클 정답으로 채점한다 — 쌍 라벨(P1: 2쌍, 실제 5걸음)을 4로 넣으면 정직한 5걸음이 과다로 채점된다
    rows = run_replay.set_rows(idx["sets"], res, "live", None)
    by_row = {r["set"]: r for r in rows}
    m = run_replay.metrics(rows)
    check("짝: run_replay 채점 — 쌍 라벨 세트는 사이클 정답 없음(unlabeled), 정확한 옛 라벨(P4 5)만 채점해 과다 0",
          by_row[p1["capture"]]["truth"] is None and by_row[p1["capture"]]["detected"] == c and by_row[p4["capture"]]["truth"] == 5
          and m["sets"] == 1 and m["unlabeled"] == 3 and m["over"] == 0 and m["exact"] == 1.0,
          json.dumps({k: m.get(k) for k in ("sets", "unlabeled", "exact", "over")}))
    apps, aihub = kotlin_side_pair_exercises()
    check("짝: SIDE_PAIR_AIHUB = ExerciseProfiles.kt lunges(SIDE_PAIR) → postureExerciseMap (코틀린과 어긋나지 않음)",
          apps == set(SIDE_PAIR_APP) and aihub == set(SIDE_PAIR_AIHUB), f"{apps} → {aihub}")
    check("짝: 바닥 경로·다른 종목은 사이클 단위", current_unit("바벨 런지", True) == "cycle" and current_unit("바벨 스쿼트", False) == "cycle"
          and current_unit("덤벨 컬", False) == "cycle")
    check("짝: 런지(스텝 포워드)는 쪽별 카운트(side_each), 다른 런지류는 좌우 짝",
          current_unit("스텝 포워드 다이나믹 런지", False) == "side_each" and current_unit("바벨 런지", False) == "side_pair"
          and "SIDE_EACH" in PROFILES_KT.read_text(encoding="utf-8"))


def _validation_self_test(work: Path, golden: list[str], check) -> None:
    """렙 검증 모드(spec §61): 골든 넷째 줄(코틀린 SetLogJson 이 쓴 validation 로그) → 랜드마크 캡처 → capture_format 로 다시 읽어
    xy·w·up 이 로그와 같은가, 검증 아닌 줄은 캡처를 만들지 않는가, 재생기가 U 줄 캡처를 읽는가."""
    import subprocess  # noqa: PLC0415
    import run_replay  # noqa: PLC0415

    d = work / "validation_logs"
    d.mkdir(parents=True, exist_ok=True)
    (d / "sets-20260924.jsonl").write_text("\n".join(golden) + "\n", encoding="utf-8")
    idx = build([d], work / "validation_cap", [d])
    by = {e["setId"][-8:]: e for e in idx["sets"]}
    v = by.get("gold0004", {})
    others = [by[k] for k in ("gold0001", "gold0002", "gold0003") if k in by]
    check("검증 모드: validation 로그만 랜드마크 캡처 + index(validation·이미지 크기), 나머지 셋은 없음(제품 로그)",
          v.get("validation") is True and v.get("landmarkCapture") == "20260924T021500-gold0004.cap" and v.get("imageW") == 360
          and v.get("imageH") == 640 and len(others) == 3
          and all(o["landmarkCapture"] is None and o["validation"] is False and o["imageW"] is None for o in others),
          json.dumps({k: v.get(k) for k in ("validation", "landmarkCapture", "imageW", "imageH")}))
    if not v.get("landmarkCapture"):
        return
    cap = work / "validation_cap" / v["landmarkCapture"]
    meta, frames = capture_format.read_capture(cap)
    log = parse_line(golden[3])
    same = len(frames) == len(log["frames"])
    for fr, lf in zip(frames, log["frames"]):
        if fr["t"] != int(lf["t_ms"]):
            same = False
        if lf.get("xy") is None:
            same &= fr["poses"] == 0 and not fr["landmarks"] and fr["up"] is None
            continue
        lms = fr["landmarks"]
        same &= fr["poses"] == 1 and len(lms) == 33
        same &= all(lms[i][0] == float(lf["xy"][2 * i]) and lms[i][1] == float(lf["xy"][2 * i + 1]) for i in range(33))
        same &= all(tuple(lms[i][4:7]) == tuple(float(q) for q in lf["w"][3 * i:3 * i + 3]) for i in range(33))
        same &= fr["up"] == tuple(float(q) for q in lf["up"])
        # 로그 vis 가 null(가시성 미기록) → nan: 재생기는 1 로 본다
        same &= all(math.isnan(lms[i][2]) and math.isnan(lms[i][3]) for i in range(33))
    check("검증 모드 왕복: .cap → capture_format.read_capture 의 xy·w·up·미검출 프레임이 골든 줄과 같다",
          same and meta.get("source") == "phone-setlog" and meta.get("validation") == "1" and meta.get("imageW") == "360"
          and meta.get("setId") == v["setId"] and sum(fr["up"] is not None for fr in frames) == 3,
          f"frames {len(frames)} meta {meta}")
    if run_replay.REPLAY_BIN.is_file():
        out = work / "validation_dump.jsonl"
        subprocess.run([str(run_replay.REPLAY_BIN), "--dump-features", str(cap), str(out)], check=True, capture_output=True)
        rows = [json.loads(x) for x in out.read_text(encoding="utf-8").splitlines() if x.strip()]
        check("검증 모드: 재생기가 U 줄 캡처를 읽는다(--dump-features 프레임 수·검출 여부가 로그와 같다)",
              [r["t"] for r in rows] == [0, 300, 600, 900] and [r["detected"] for r in rows] == [True, True, True, False],
              str([(r["t"], r["detected"]) for r in rows]))
    else:
        check("검증 모드: 재생기 빌드 필요 (cd replay-jvm && gradle -q test installDist)", False, str(run_replay.REPLAY_BIN))


def self_test(work: Path) -> int:
    import run_replay  # noqa: PLC0415 — 같은 폴더의 재생·채점 도구

    work.mkdir(parents=True, exist_ok=True)
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, ok: bool, detail: str = "") -> None:
        checks.append((name, bool(ok), detail))

    def write_inputs(folder: Path, pass2: dict | None) -> None:
        logs, truth, labels = _synthetic_logs(pass2)
        folder.mkdir(parents=True, exist_ok=True)
        (folder / "sets-20260924.jsonl").write_text("\n".join(logs[:1] + logs[2:]) + "\n\n{broken json\n", encoding="utf-8")
        (folder / "sets-20260901.jsonl").write_text(logs[1] + "\n", encoding="utf-8")
        (folder / "rep_truth.csv").write_text("\n".join(truth) + "\n", encoding="utf-8")
        (folder / "labels").mkdir(exist_ok=True)
        (folder / "labels" / "set_labels.jsonl").write_text("\n".join(labels) + "\n", encoding="utf-8")

    # 1차: reps 블록 없이 변환·재생 → 앱(같은 카운터)이 적었을 카운트를 얻는다
    write_inputs(work / "logs1", None)
    idx1 = build([work / "logs1"], work / "cap1", [work / "logs1"])
    _, res1, _ = run_replay.replay_index(work / "cap1", work / "res1", {"live", "hysteresis"})
    key = {e["setId"][-8:]: run_replay.set_key(e) for e in idx1["sets"]}
    pass2 = {"A": res1[f"{key['aaaa0001']}|live"], "C": res1[f"{key['cccc0003']}|live"],
             "F": res1[f"{key['ffff0006']}|live"], "G": res1[f"{key['gggg0007']}|hysteresis"]}
    # 옛 빌드의 로그 카운트 = 옛 신호(knee_out_mean 0.10)로 센 값 — live@log 행이 같은 구성을 만든다
    old = work / "old_manifest.tsv"
    run_replay.write_manifest([("B|old", idx1["sets"][[e["setId"][-8:] for e in idx1["sets"]].index("bbbb0002")]["capture"],
                                "스텝 포워드 다이나믹 런지", "live", "knee_out_mean", "0.1", "0", "", "")], old, work / "cap1")
    pass2["B"] = run_replay.run_jvm(old, work / "old.jsonl")["B|old"]
    # H 의 리셋 위치: A 의 세 번째 완료 프레임 t3(레거시 경로의 발표 시각 = 완료 프레임)과 그 직전 프레임
    a_frame_ts = [t for t, _ in read_feature_capture(work / "cap1" / idx1["sets"][[e["setId"][-8:] for e in idx1["sets"]].index("aaaa0001")]["capture"])[1]]
    t3 = pass2["A"]["publishedMs"][2]
    pass2["H_resets"] = [(t3 + 40, "camera_switch", max(t for t in a_frame_ts if t < t3))]
    # 1b차: H 로그(리셋만, 카운트 자리 0)를 재생해 앱이 적었을 카운트를 얻는다 — 앱의 락 순서(after_t_ms)로 리셋한 값
    write_inputs(work / "logs1b", pass2)
    idx1b = build([work / "logs1b"], work / "cap1b", [work / "logs1b"])
    key1b = {e["setId"][-8:]: run_replay.set_key(e) for e in idx1b["sets"]}
    _, res1b, _ = run_replay.replay_index(work / "cap1b", work / "res1b", {"live"})
    pass2["H"] = res1b[f"{key1b['hhhh0008']}|live"]

    # 2차: 앱이 적었을 reps 블록을 넣은 로그 → 변환 → 재생 → 파리티
    write_inputs(work / "logs2", pass2)
    idx = build([work / "logs2"], work / "cap2", [work / "logs2"])
    by = {e["setId"][-8:]: e for e in idx["sets"]}
    check("세트 수(깨진 줄 1개 건너뜀)", len(idx["sets"]) == 9, str(len(idx["sets"])))

    # 왕복: 프레임 수·시각·피처 문자열이 로그와 같다
    raw = {}
    for f, _, log in iter_logs([work / "logs2"]):
        raw[str(log["set_id"])[-8:]] = log
    same = True
    for k, e in by.items():
        meta, frames = read_feature_capture(work / "cap2" / e["capture"])
        log = raw[k]
        if len(frames) != len(log["frames"]):
            same = False
        for (t, feats), lf in zip(frames, log["frames"]):
            lfe = lf.get("features") or {}
            if t != int(lf["t_ms"]) or set(feats) != set(lfe) or any(abs(feats[q] - float(lfe[q])) > 0 for q in feats):
                same = False
        text = (work / "cap2" / e["capture"]).read_text(encoding="utf-8")
        for lf in log["frames"][:20]:
            for q, v in (lf.get("features") or {}).items():
                if f"{q}={_num_text(v)}" not in text:
                    same = False
    check("왕복: 프레임·시각·피처 값(문자열 그대로)", same)
    check("A: edited 라벨 → truthReps 6", by["aaaa0001"]["truthReps"] == 6 and by["aaaa0001"]["truthSource"] == "edited")
    check("A: mode·view·anchor 보존", by["aaaa0001"]["mode"] == "coach" and by["aaaa0001"]["view"] == "C" and by["aaaa0001"]["anchorTMs"] == 5500)
    check("B: 옛 로그 — mode·view 없음 = null", by["bbbb0002"]["mode"] is None and by["bbbb0002"]["view"] is None)
    check("B: 렙 시각 절대 epoch → loggedTimesRelative=false", by["bbbb0002"]["loggedTimesRelative"] is False)
    check("B: confirmed 라벨은 정답이 아니다(순환)", by["bbbb0002"]["truthReps"] is None and by["bbbb0002"]["confirmedReps"] == 0)
    check("C: note 'floor' → 바닥 경로 + 규칙 rep ROM", by["cccc0003"]["floor"] is True and by["cccc0003"]["romDirection"] == "min"
          and abs(by["cccc0003"]["romThreshold"] - 0.7101) < 1e-9, f"{by['cccc0003']['romDirection']} {by['cccc0003']['romThreshold']}")
    check("D: 랩 로그·reps 블록 없음 → 파리티 없음, 폼만 라벨 → 정답 없음",
          by["dddd0004"]["origin"] == "lab" and by["dddd0004"]["loggedReps"] is None and by["dddd0004"]["truthReps"] is None
          and by["dddd0004"]["labelForm"] == "good")

    _, res, names = run_replay.replay_index(work / "cap2", work / "res2", {"live", "hysteresis"})
    rk = {k: run_replay.set_key(e) for k, e in by.items()}
    a_live, a_hys = res[f"{rk['aaaa0001']}|live"], res[f"{rk['aaaa0001']}|hysteresis"]
    check("A live: 합성 6회를 6회로 센다(독립 기대값)", a_live["reps"] == 6, str(a_live["reps"]))
    check("A hysteresis: 6회", a_hys["reps"] == 6, str(a_hys["reps"]))
    check("A live: 카운트·발화 시각·ROM 판정 파리티", a_live.get("parityCount") is True and a_live.get("parityTimes") is True
          and a_live.get("parityValid") is True, f"{a_live.get('parityCount')} {a_live.get('parityTimes')} {a_live.get('parityValid')}")
    b_now = res[f"{rk['bbbb0002']}|live"]
    b_log = res.get(f"{rk['bbbb0002']}|live@log", {})
    check("B live: 로그 신호(knee_out_mean) ≠ 지금 신호(knee_mean)", b_now.get("signalMatchesLog") is False and b_now["feature"] == "knee_mean")
    check("B live@log: 옛 신호로 카운트 파리티, 절대 epoch 시각은 비교 안 함(null)", b_log.get("parityCount") is True
          and b_log.get("parityTimes") is None, f"{b_log.get('reps')} vs {b_log.get('loggedReps')}, times {b_log.get('parityTimes')}")
    check("B: 옛 신호(보폭 성분만)는 5회를 다 못 세고, 지금 신호는 5회", 0 < b_log.get("reps", 0) < 5 and b_now["reps"] == 5,
          f"old {b_log.get('reps')} now {b_now['reps']}")
    c_live, c_hys = res[f"{rk['cccc0003']}|live"], res[f"{rk['cccc0003']}|hysteresis"]
    check("C(실기기 baseline1): 현재 카운터 0회, 새 코어 4회 (RepHysteresisTest 기록과 같다)",
          c_live["reps"] == 0 and c_hys["reps"] == 4, f"live {c_live['reps']} hysteresis {c_hys['reps']}")
    check("C live: 카운트 파리티", c_live.get("parityCount") is True)
    check("C: forSession(floor, 규칙 ROM) — ROM 방향·임계값이 규칙 값", c_live["romDirection"] == "min" and abs(c_live["romThreshold"] - 0.7101) < 1e-6)
    check("D: 파리티 필드 없음", "parityCount" not in res[f"{rk['dddd0004']}|live"])
    e_live = res[f"{rk['eeee0005']}|live"]
    check("E(음성 대조): 로그 카운트 +1 → 카운트·시각 파리티 깨짐", e_live.get("parityCount") is False and e_live.get("parityTimes") is False)
    check("A: 1.5 s 넘는 틈 없음, B 세트에도 없음", a_live["gapsOverMaxGap"] == 0 and b_now["gapsOverMaxGap"] == 0)
    check("A: §58 필드 — engine·config·thermal·app_version 이 index 에", by["aaaa0001"]["loggedEngine"] == "return_v1"
          and by["aaaa0001"]["loggedMinAmp"] == 35.0 and by["aaaa0001"]["thermalStart"] == 0
          and by["aaaa0001"]["thermalChanges"] == [[9000, 1]] and by["aaaa0001"]["appVersion"] == "0.9.1-debug")
    f_live = res[f"{rk['ffff0006']}|live"]
    check("F: 로그의 카메라 전환 리셋을 재생 → 카운트·시각 파리티 (리셋 1회 적용)", f_live.get("parityCount") is True
          and f_live.get("parityTimes") is True and f_live["resetsApplied"] == 1, f"{f_live['reps']} vs {f_live.get('loggedReps')}")
    check("F: 리셋이 결과를 바꾼다 (같은 프레임의 A 는 6회, F 는 그보다 적다)", f_live["reps"] < a_live["reps"],
          f"F {f_live['reps']} / A {a_live['reps']}")
    g_hys, g_live = res[f"{rk['gggg0007']}|hysteresis"], res[f"{rk['gggg0007']}|live"]
    check("G: 새 코어 로그 — 6회, 버려진 준비 사이클 1, 세트 끝 확정 대기 1", g_hys["reps"] == 6 and len(g_hys["droppedMs"]) == 1
          and g_hys["pendingReps"] == 1, f"{g_hys['reps']} dropped {g_hys['droppedMs']} pending {g_hys['pendingReps']}")
    check("G: 새 코어 구성에서 카운트·시각·버림·대기 파리티", g_hys.get("parityCount") is True and g_hys.get("parityTimes") is True
          and g_hys.get("parityDropped") is True and g_hys.get("parityPending") is True)
    h_live, h2_live = res[f"{rk['hhhh0008']}|live"], res[f"{rk['hhhh0009']}|live"]
    check("H: 추론 중에 누른 전환 — after_t_ms 로 리셋이 세 번째 완료 프레임 앞에 놓여 그 반복을 잃는다(A − 1), 파리티",
          h_live["reps"] == a_live["reps"] - 1 and h_live.get("parityCount") is True and h_live.get("parityTimes") is True
          and h_live["resetsApplied"] == 1, f"H {h_live['reps']} / A {a_live['reps']}")
    check("H2(음성 대조): after_t_ms 가 없으면 누른 시각으로 잘라 리셋이 한 프레임 늦다 → 앱이 적은 수와 어긋난다",
          h2_live.get("parityCount") is False and h2_live["reps"] != h_live["reps"],
          f"H2 {h2_live['reps']} vs 로그 {h2_live.get('loggedReps')}")

    # 골든: 코틀린 SetLogJson 이 쓴 줄(app/src/test/resources/setlog_s58_fixture.txt — PostureSetLogTest 가 같은 줄을 요구한다)
    golden = [ln for ln in GOLDEN_FIXTURE.read_text(encoding="utf-8").splitlines() if ln.strip() and not ln.startswith("#")]
    mine = [encode_setlog(d) for d in _golden_logs()]
    diff = next((i for i, (x, y) in enumerate(zip(golden, mine)) if x != y), None)
    check("골든: 파이썬 인코더 사본 = 코틀린 SetLogJson 줄 (바이트까지)", len(golden) == len(mine) == 4 and diff is None,
          "" if diff is None else f"줄 {diff + 1}: …{next(golden[diff][k-40:k+40] for k in range(len(golden[diff])) if k >= len(mine[diff]) or golden[diff][k] != mine[diff][k])}…")
    floor_ex, rep_rules = rule_tables()
    gl = [convert(parse_line(ln), f"golden:{i}", floor_ex, rep_rules) for i, ln in enumerate(golden, 1)]
    (g0_text, g0), (_, g1) = gl[:2]
    check("골든: 변환기가 코틀린 줄의 §58 필드를 읽는다 — 리셋(누른 시각·after)·열·버전",
          g0["loggedEngine"] == "return_v1" and g0["loggedResets"] == [[-120, "camera_switch"], [410, "pause"]]
          and g0["loggedResetsAfterMs"] == [None, 300] and "loggedResetsAfterMs=none,300" in g0_text
          and g0["thermalChanges"] == [[600, 1]] and g0["appVersion"] == "0.9.1-debug" and g0["loggedMinAmp"] == 35.0,
          f"{g0['loggedResets']} {g0['loggedResetsAfterMs']}")
    c1 = g1["loggedConfig"] or {}
    check("골든: 새 코어 로그 — 극성·첫 쌍 8 s·이후 창 없음·비 0.5~2·불응기 0.8 s, 대기·진행 중·버림",
          g1["loggedEngine"] == "hysteresis_v1" and c1.get("polarity") == "down" and c1.get("first_pair_window_ms") == 8000
          and c1.get("later_window_ms", "missing") is None and c1.get("min_ratio") == 0.5 and c1.get("max_ratio") == 2
          and c1.get("refractory_ms") == 800 and g1["loggedPendingMs"] == 900 and g1["loggedDroppedMs"] == [250]
          and g1["loggedInProgress"] is True, json.dumps(c1, ensure_ascii=False))

    g2 = gl[2][1]
    check("골든: 좌우 짝 줄 — 사이클 3(count·배열 그대로) · 화면 1 · 짝 없는 한쪽 · 단위 일치 · 옛 줄은 사이클 기본값",
          g2["repUnit"] == "side_pair" and g2["cyclesPerRep"] == 2 and g2["loggedReps"] == 3 and g2["loggedCompleted"] == 1
          and g2["loggedHalfPending"] is True and g2["loggedDisplayedReps"] == 1 and g2["loggedUnitConsistent"] is True
          and g2["loggedValid"] == [True, False, True] and g0["repUnit"] == "cycle" and g0["loggedDisplayedReps"] == 1
          and g0["loggedHalfPending"] is None, json.dumps({k: g2[k] for k in ("repUnit", "loggedReps", "loggedCompleted",
                                                                              "loggedHalfPending", "loggedUnitConsistent")}))
    _validation_self_test(work, golden, check)
    _side_pair_self_test(work, check)

    summary = run_replay.summarize(idx, res, names, 10, None, 200, 1)
    live = summary["configs"]["live"]
    check("채점: 정답 있는 세트만 (A 스쿼트 1, C 푸시업 1; D·E·F·G·H·H2 는 unlabeled)",
          live["바벨 스쿼트"]["sets"] == 1 and live["바벨 스쿼트"]["unlabeled"] == 6 and live["푸시업"]["sets"] == 1,
          f"{live['바벨 스쿼트']['sets']} / {live['바벨 스쿼트']['unlabeled']}")
    par = summary["parity"]["live"]["바벨 스쿼트"]
    check("파리티 요약(live): 레거시 로그 스쿼트 5세트(A·E·F·H·H2) 중 일치 3, E·H2 불일치 — G(새 코어 로그)는 빠진다",
          par["sets"] == 5 and par["countMatch"] == 3
          and sorted(m["set"][-13:] for m in par["mismatches"]) == ["eeee0005.fcap", "hhhh0009.fcap"], str(par))
    parh = summary["parity"].get("hysteresis", {}).get("바벨 스쿼트", {})
    check("파리티 요약(hysteresis): 새 코어 로그 G 1세트 일치", parh.get("sets") == 1 and parh.get("countMatch") == 1, str(parh))

    width = max(len(n) for n, _, _ in checks)
    for name, ok, detail in checks:
        print(f"{'PASS' if ok else 'FAIL'}  {name.ljust(width)}  {detail}")
    failed = sum(not ok for _, ok, _ in checks)
    print(f"\n{len(checks) - failed}/{len(checks)} 통과 — 작업 폴더 {work}")
    return 1 if failed else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("inputs", nargs="*", type=Path, help="sets-*.jsonl 파일 또는 그 폴더")
    parser.add_argument("--out", type=Path)
    parser.add_argument("--labels", nargs="*", type=Path, default=None,
                        help="rep_truth.csv / set_labels.jsonl 파일 또는 폴더 (기본: 입력 폴더)")
    parser.add_argument("--since", default=None, help="created_at 이 이 값 이상(ISO, 예 2026-09-24)")
    parser.add_argument("--until", default=None, help="created_at 이 이 값 미만")
    parser.add_argument("--exercise", nargs="*", default=None, help="이 AIHub 종목만")
    parser.add_argument("--session-only", action="store_true", help="세션 로그(note 가 session: 으로 시작)만")
    parser.add_argument("--truth-confirmed", action="store_true", help="confirmed(순환) 라벨도 정답에 넣는다 — 표시됨")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        work = args.out or Path(tempfile.mkdtemp(prefix="setlog_selftest_"))
        return self_test(work)
    if not args.inputs or args.out is None:
        parser.error("inputs 와 --out 이 필요하다")
    label_paths = args.labels if args.labels is not None else [p if p.is_dir() else p.parent for p in args.inputs]
    index = build(args.inputs, args.out, label_paths, args.since, args.until,
                  set(args.exercise) if args.exercise else None, args.session_only, args.truth_confirmed)
    n = len(index["sets"])
    with_reps = sum(e["loggedReps"] is not None for e in index["sets"])
    with_truth = sum(e["truthReps"] is not None for e in index["sets"])
    print(f"{n} sets -> {args.out}  (카운터 기록 {with_reps}, edited 정답 {with_truth}, 라벨 {index['labelsLoaded']})")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
