# -*- coding: utf-8 -*-
"""합성 휴대폰 검증 세션 — 폰도 데이터셋도 없이 Gate A 파이프라인(gate_a.py)을 끝까지 돌려 보기 위한 픽스처 (spec §61).

무엇을 만드나 (결정적 — 같은 seed 면 바이트까지 같다)
    <out>/phone/sets-20260924.jsonl        렙 검증 모드 세트 로그(`validation`·`image`, 프레임마다 `xy`·`w`·`up`) — setlog_captures.encode_setlog
                                           (코틀린 SetLogJson 과 골든 줄로 바이트 일치가 검사된 파이썬 사본)로 쓴다
    <out>/phone/rep_truth.csv, labels/set_labels.jsonl   자가 라벨(edited — 화면 단위: 런지는 쌍)
    <out>/plan_gateA_fixture.csv           rep_validation_plan.py 의 계획표 열 그대로, 집계 칸 = 알고 있는 정답
    <out>/fixture_truth.json               세트별 정답·기대(드라이런 검사용)
세트 (서서 하는 두 종목, 세로 360×640 · 가로 640×360 둘 다)
    스쿼트 세로·가로(8회), 런지 세로·가로(왼·오른 번갈아 10걸음), 런지 가로 7걸음(짝 없는 한쪽), 스쿼트 세로 8회 중
    중간 약 40% 동안 몸이 아래로 밀려 무릎·발목이 화면 밖(가시성 < 0.5 — 프레이밍 요약이 찾아야 한다), 스쿼트 가로 6회 + 세트 앞뒤 준비·정리 동작
어떻게
    1) 절차적 33관절 골격(MediaPipe 순서·부호: 월드 좌표 m, y 아래, z 카메라 쪽이 음수, 원점 = 엉덩이 중점)을 앱 추론 간격(~300 ms,
       setlog_captures._cadence)으로 샘플링. 좌표는 로그와 같게 소수 4자리·가시성 3자리로 **미리 반올림**한다 — 그래야 로그의 좌표가
       피처를 만든 좌표 그 자체다(실기기 로그는 반올림 전 좌표로 피처를 만들어 무결성 검사에 작은 차이가 남는다).
    2) 피처 = 재생기 `Replay --dump-features`(앱 PostureAnalyzer 후처리 + PoseFrame.features + ViewEstimator.frameFeatures 를 앱 소스 그대로
       컴파일한 것). 파이썬으로 피처를 다시 구현하지 않는다.
    3) reps 블록 = 그 피처(로그에 적힌 5자리 값)를 지금 빌드의 live 카운터(RepCounter.forSession)로 재생한 결과 — 앱이 적었을 값.
       런지는 표시 단위 side_pair(completed = 사이클 // 2, half_pending). 구성(reps.config)은 재생기가 돌려준 카운터의 실제 값.
    4) 세트 view 블록 = 프레임 view_cos/view_sin 원형 평균 → PostureView.kt 의 등급 상수(파일에서 읽는다)로 분류.
카운터가 정답을 맞히는지는 이 픽스처가 약속하지 않는다 — 합성 동작은 사람의 동작이 아니다. 드라이런은 파이프라인이 정답·잘림·무결성을
제대로 **재는지**를 검사한다.

사용법
    python make_phone_fixture.py --out <폴더> [--seed 20260924]
MM-Fit MediaPipe 캡처(data/mm-fit/mp_captures)를 원천으로 쓰는 선택지는 두지 않았다 — 그 캡처에는 up·이미지 크기가 없어 검증 로그로
옮기려면 규약을 새로 정해야 하고, 이 컨테이너에는 data/ 가 없어 검증할 수도 없다. 절차적 골격이 유일한 원천이다.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import random
import re
import subprocess
import sys
from pathlib import Path

import capture_format
import rep_validation_plan
import run_replay
import setlog_captures

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
VIEW_KT = REPO / "app" / "src" / "main" / "java" / "com" / "example" / "trex_kotlin" / "posture" / "PostureView.kt"

# 몸 치수(m) — 모집단 평균 근처의 한 사람. 좌우는 사람 기준(+X = 사람의 왼쪽)
THIGH, SHANK, TORSO = 0.43, 0.42, 0.50
ANKLE_H = 0.08
HIP_X, KNEE_X, ANKLE_X, SH_X, EL_X, WR_X = 0.10, 0.11, 0.12, 0.18, 0.20, 0.21

APP_NAME = {"바벨 스쿼트": "바벨 스쿼트", "스텝 포워드 다이나믹 런지": "런지"}
PLAN_EX = {e["aihub"]: e for e in rep_validation_plan.EXERCISES}


# ---------------------------------------------------------------- 골격

def _rot(v: tuple[float, float], a: float) -> tuple[float, float]:
    """(앞, 위) 평면 벡터를 반시계(앞쪽으로) a 라디안 돌린다."""
    c, s = math.cos(a), math.sin(a)
    return (v[0] * c - v[1] * s, v[0] * s + v[1] * c)


def _leg(hip: tuple[float, float], ankle: tuple[float, float]) -> tuple[float, float]:
    """2관절 역기구학 — 무릎은 앞으로 굽는다. 너무 멀면 다리를 편다."""
    dx, dy = ankle[0] - hip[0], ankle[1] - hip[1]
    d = min(math.hypot(dx, dy), THIGH + SHANK - 1e-4)
    u = (dx / math.hypot(dx, dy), dy / math.hypot(dx, dy))
    a = math.acos(max(-1.0, min(1.0, (THIGH ** 2 + d ** 2 - SHANK ** 2) / (2 * THIGH * d))))
    t = _rot(u, a)
    return (hip[0] + THIGH * t[0], hip[1] + THIGH * t[1])


def body(squat: float = 0.0, lunge: float = 0.0, side: str = "R", sway: float = 0.0, arms: float = 0.0) -> dict[int, tuple]:
    """몸 좌표계 (X 사람 왼쪽, Y 위, F 앞) 33관절. squat 0~1 = 무릎 굴곡 0~95°, lunge 0~1 = 한 걸음의 위상(0·1 선 자세, 0.5 가장 깊음),
    side = 내딛는 발(사용자 기준 L/R), sway = 좌우 체중 이동(m), arms = 팔 흔들기(라디안)."""
    pts: dict[int, tuple] = {}
    if lunge > 0:
        e = math.sin(math.pi * lunge)
        depth = e * e
        stride = 0.70
        hip = (0.5 * stride * e, ANKLE_H + THIGH + SHANK - 0.02 - 0.40 * depth)
        front, back = (stride * e, ANKLE_H), (0.0, ANKLE_H)
        feet = {"L": front if side == "L" else back, "R": front if side == "R" else back}
        knees = {k: _leg(hip, a) for k, a in feet.items()}
        lean = 0.05 * depth
    else:
        phi = math.radians(95.0) * squat
        al, be = 0.4 * phi, 0.6 * phi
        ank = (0.0, ANKLE_H)
        knee = (ank[0] + SHANK * math.sin(al), ank[1] + SHANK * math.cos(al))
        hip = (knee[0] - THIGH * math.sin(be), knee[1] + THIGH * math.cos(be))
        feet = {"L": ank, "R": ank}
        knees = {"L": knee, "R": knee}
        lean = 0.5 * be
    sgn = {"L": 1.0, "R": -1.0}
    for s, (hip_i, knee_i, ank_i, heel_i, toe_i) in {"L": (23, 25, 27, 29, 31), "R": (24, 26, 28, 30, 32)}.items():
        x = sgn[s]
        pts[hip_i] = (x * HIP_X + sway, hip[1], hip[0])
        pts[knee_i] = (x * (KNEE_X + 0.03 * squat) + sway * 0.5, knees[s][1], knees[s][0])
        pts[ank_i] = (x * ANKLE_X, feet[s][1], feet[s][0])
        pts[heel_i] = (x * ANKLE_X, feet[s][1] - 0.05, feet[s][0] - 0.06)
        pts[toe_i] = (x * (ANKLE_X + 0.01), feet[s][1] - 0.07, feet[s][0] + 0.14)
    up = (math.sin(lean), math.cos(lean))            # 몸통 방향 (앞, 위)
    sh = (hip[0] + TORSO * up[0], hip[1] + TORSO * up[1])
    head = (sh[0] + 0.25 * up[0], sh[1] + 0.25 * up[1])
    for s, (sh_i, el_i, wr_i, pk_i, ix_i, th_i) in {"L": (11, 13, 15, 17, 19, 21), "R": (12, 14, 16, 18, 20, 22)}.items():
        x = sgn[s]
        swing = arms * (1 if s == "L" else -1)
        el = (sh[0] - 0.27 * math.sin(lean - swing), sh[1] - 0.27 * math.cos(lean - swing))
        wr = (el[0] + 0.25 * math.sin(0.3 + swing), el[1] - 0.25 * math.cos(0.3 + swing))
        pts[sh_i] = (x * SH_X + sway, sh[1], sh[0])
        pts[el_i] = (x * EL_X + sway, el[1], el[0])
        pts[wr_i] = (x * WR_X + sway, wr[1], wr[0])
        pts[pk_i] = (x * (WR_X + 0.02) + sway, wr[1] - 0.07, wr[0] - 0.01)
        pts[ix_i] = (x * (WR_X + 0.01) + sway, wr[1] - 0.08, wr[0] + 0.02)
        pts[th_i] = (x * (WR_X - 0.02) + sway, wr[1] - 0.05, wr[0] + 0.03)
    hx = sway
    pts[0] = (hx, head[1], head[0] + 0.10)
    for i, (dx, dy, df) in {1: (0.02, 0.03, 0.09), 2: (0.035, 0.03, 0.085), 3: (0.05, 0.03, 0.08), 4: (-0.02, 0.03, 0.09),
                            5: (-0.035, 0.03, 0.085), 6: (-0.05, 0.03, 0.08), 7: (0.075, 0.01, 0.0), 8: (-0.075, 0.01, 0.0),
                            9: (0.025, -0.04, 0.09), 10: (-0.025, -0.04, 0.09)}.items():
        pts[i] = (hx + dx, head[1] + dy, head[0] + df)
    return pts


def camera(pts: dict[int, tuple], yaw_deg: float, img: tuple[int, int], floor_y: float, zoom: float, rng: random.Random,
) -> tuple[list[float], list[float], list[float]]:
    """몸 좌표 → (xy 66, w 99, vis 33). 약원근 투영. yaw > 0 = 사용자 오른쪽이 카메라에 가깝다(뷰 B). 화면 밖(0..1 밖) 관절은
    MediaPipe 처럼 가시성이 떨어진다(< 0.5). 반올림은 로그와 같게: 좌표 4자리, 가시성 3자리."""
    w_img, h_img = img
    px_per_m = zoom * 0.80 * h_img / 1.8
    ps = math.radians(yaw_deg)
    cam = {}
    for i, (x, y, f) in pts.items():
        xc = x * math.cos(ps) + f * math.sin(ps)
        fc = -x * math.sin(ps) + f * math.cos(ps)
        cam[i] = (xc, y, fc)
    hipc = tuple((cam[23][k] + cam[24][k]) / 2 for k in range(3))
    xy, world, vis = [], [], []
    for i in range(capture_format.LANDMARKS):
        xc, y, fc = cam[i]
        nx = 0.5 + xc * px_per_m / w_img + rng.gauss(0, 0.0015)
        ny = floor_y - y * px_per_m / h_img + rng.gauss(0, 0.0015)
        # MediaPipe 월드 좌표 원본: x 화면 오른쪽(= 마주 선 사람의 왼쪽), y 아래, z 카메라 쪽이 음수, 원점 = 엉덩이 중점
        wx = (xc - hipc[0]) + rng.gauss(0, 0.004)
        wy = -(y - hipc[1]) + rng.gauss(0, 0.004)
        wz = -(fc - hipc[2]) + rng.gauss(0, 0.004)
        inside = 0.0 <= nx <= 1.0 and 0.0 <= ny <= 1.0
        far = (yaw_deg > 20 and i in (11, 13, 15, 17, 19, 21, 23, 25, 27, 29, 31)) or (yaw_deg < -20 and i in (12, 14, 16, 18, 20, 22, 24, 26, 28, 30, 32))
        v = 0.62 + 0.12 * rng.random() if far else 0.90 + 0.09 * rng.random()   # 먼 쪽 관절은 가시성이 조금 낮다
        if not inside:
            v = 0.05 + 0.25 * rng.random()
        xy += [round(nx, 4), round(ny, 4)]
        world += [round(wx, 4), round(wy, 4), round(wz, 4)]
        vis.append(round(v, 3))
    return xy, world, vis


# ---------------------------------------------------------------- 세트 대본

def schedule(kind: str, reps: int, rng: random.Random, pre_s: float = 2.5, post_s: float = 2.0, idle: bool = False,
             first_side: str = "R", period_s: float = 2.6, hold_s: float = 1.0):
    """시각(s) → 몸 인자 함수와 세트 길이. 반복 사이 상단에서 hold_s 멈춘다(레거시 복귀형이 상단 체류를 얻게 — 사람도 그렇게 한다).
    idle = 세트 앞 준비 동작(좌우 흔들기·팔 풀기·얕은 굽힘 2회 — 게이트 35° 아래)과 세트 뒤 정리 동작."""
    cycle = period_s + hold_s
    start = pre_s + (6.0 if idle else 0.0)
    total = start + reps * cycle + post_s + (5.0 if idle else 0.0)
    sides = [first_side if k % 2 == 0 else ("L" if first_side == "R" else "R") for k in range(reps)]
    jitter = [rng.uniform(-0.15, 0.15) for _ in range(reps)]

    def at(s: float) -> dict:
        u = s - start
        k = int(u // cycle) if u >= 0 else -1
        args: dict = {}
        if 0 <= k < reps:
            w = u - k * cycle
            p = w / (period_s + jitter[k])
            if 0 <= p < 1:
                if kind == "squat":
                    args["squat"] = (1 - math.cos(2 * math.pi * p)) / 2
                else:
                    args["lunge"] = p
                    args["side"] = sides[k]
        elif idle and s < start - 0.5 and s > 0.5:
            q = s - 0.5
            args["sway"] = 0.04 * math.sin(2 * math.pi * q / 2.2)
            args["arms"] = 0.5 * math.sin(2 * math.pi * q / 1.3)
            if 2.0 <= q < 3.2 or 3.8 <= q < 5.0:           # 얕은 굽힘(무릎 약 14°) — 반복이 아니다
                r = ((q - 2.0) if q < 3.2 else (q - 3.8)) / 1.2
                args["squat"] = 0.15 * (1 - math.cos(2 * math.pi * r)) / 2
        elif idle and u >= reps * cycle + post_s * 0.5:
            q = u - reps * cycle
            args["sway"] = 0.06 * math.sin(2 * math.pi * q / 1.8)
            args["arms"] = 0.6 * math.sin(2 * math.pi * q / 1.1)
        return args

    return at, total, sides


# (id, 종목, 방향, 반복(사이클), 뷰 yaw, 특이사항)
SETS = [
    ("s1", "바벨 스쿼트", "portrait", 8, 0.0, {}),
    ("s2", "스텝 포워드 다이나믹 런지", "portrait", 10, 35.0, {}),
    ("s3", "바벨 스쿼트", "landscape", 8, 0.0, {}),
    ("s4", "스텝 포워드 다이나믹 런지", "landscape", 10, 35.0, {"first_side": "L"}),
    ("s5", "바벨 스쿼트", "portrait", 8, 0.0, {"drift": True}),
    ("s6", "스텝 포워드 다이나믹 런지", "landscape", 7, 35.0, {}),
    ("s7", "바벨 스쿼트", "landscape", 6, 0.0, {"idle": True}),
]
IMAGE = {"portrait": (360, 640), "landscape": (640, 360)}
FLOOR_Y = {"portrait": 0.90, "landscape": 0.93}


def synth_frames(spec: tuple, seed: int) -> tuple[list[dict], dict]:
    """세트 하나의 프레임 [{t_ms, infer_ms, xy, w, vis, up}] 과 정답."""
    sid, ex, orient, reps, yaw, opt = spec
    rng = random.Random(f"{seed}-{sid}")
    kind = "squat" if ex == "바벨 스쿼트" else "lunge"
    at, total, sides = schedule(kind, reps, rng, idle=opt.get("idle", False), first_side=opt.get("first_side", "R"))
    img = IMAGE[orient]
    # 폰 기울기 약 3° — up 은 IMU 중력축(앱이 그 프레임 피처에 쓴 값). 세트 안에서 아주 조금 흔들린다
    tilt = math.radians(3.0)
    frames = []
    for t in setlog_captures._cadence(int(total / 0.30) + 5, rng):
        s = t / 1000.0
        if s > total:
            break
        floor_y, zoom = FLOOR_Y[orient], 1.0
        if opt.get("drift"):
            # 세트 35~75% 구간: 사용자가 폰 쪽으로 다가와 몸이 아래로 밀리고 커진다 → 무릎·발목이 화면 아래로 나간다
            a = (s / total - 0.35) / 0.40
            if 0 <= a <= 1:
                bump = math.sin(math.pi * a) ** 0.5
                floor_y += 0.45 * bump
                zoom += 0.12 * bump
        xy, w, vis = camera(body(**at(s)), yaw, img, floor_y, zoom, rng)
        jit = rng.gauss(0, 0.003)
        up = [round(math.sin(tilt) + jit, 4), round(math.cos(tilt), 4), round(0.02 + jit, 4)]
        frames.append({"t_ms": t, "infer_ms": rng.randint(38, 92), "xy": xy, "w": w, "vis": vis, "up": up})
    truth = {"cycles": reps}
    if kind == "lunge":
        truth.update({"left": sides.count("L"), "right": sides.count("R"), "order": "".join(sides)})
    return frames, truth


# ---------------------------------------------------------------- 앱 피처 (재생기)

def dump_features(frames: list[dict], meta: dict, work: Path, name: str) -> list[dict]:
    """프레임 → 랜드마크 캡처(U 줄 포함) → Replay --dump-features → 프레임별 피처 사전(앱 순서)."""
    if not run_replay.REPLAY_BIN.is_file():
        raise SystemExit(f"재생기가 없다: {run_replay.REPLAY_BIN}\n  (cd replay-jvm && gradle -q test installDist)")
    lines = []
    for f in frames:
        lines.append(capture_format.up_line(f["t_ms"], [repr(v) for v in f["up"]]))
        lms = [(f["xy"][2 * i], f["xy"][2 * i + 1], f["vis"][i], None, f["w"][3 * i], f["w"][3 * i + 1], f["w"][3 * i + 2])
               for i in range(capture_format.LANDMARKS)]
        lines.append(capture_format.frame_line(f["t_ms"], lms))
    cap = work / f"{name}.cap"
    capture_format.write_capture(cap, meta, lines)
    out = work / f"{name}.features.jsonl"
    subprocess.run([str(run_replay.REPLAY_BIN), "--dump-features", str(cap), str(out)], check=True, capture_output=True)
    rows = [json.loads(x) for x in out.read_text(encoding="utf-8").splitlines() if x.strip()]
    if [r["t"] for r in rows] != [f["t_ms"] for f in frames]:
        raise SystemExit(f"{name}: 재생기 프레임 시각이 어긋난다")
    return [r["features"] for r in rows]


def _view_constants() -> dict[str, float]:
    text = VIEW_KT.read_text(encoding="utf-8")
    out = {}
    for k in ("B_SIGN", "FRONT_MAX_DEG", "OBLIQUE_MAX_DEG", "REAR_MIN_DEG", "REAR_PURE_MIN_DEG", "MIN_R", "MIN_FRAMES"):
        m = re.search(rf"const val {k}\s*=\s*([-0-9.]+)f?", text)
        if not m:
            raise SystemExit(f"PostureView.kt 에서 {k} 를 찾지 못했다")
        out[k] = float(m.group(1))
    return out


def view_block(features: list[dict]) -> dict | None:
    """ViewEstimator.estimate(frames) + classify 와 같은 계산 — 세트 로그 `view` 블록. 프레임이 모자라면 None(블록 없음)."""
    k = _view_constants()
    cs = [(f["view_cos"], f["view_sin"]) for f in features if f.get("view_cos") is not None and f.get("view_sin") is not None]
    if len(cs) < k["MIN_FRAMES"]:
        return None
    # 코틀린은 Float 로 더해 평균을 Float 로 내린다 — 여기는 float64. yaw 소수 2자리·r 3자리 표기에서 차이가 날 수 있다(픽스처라 괜찮다)
    c = sum(x for x, _ in cs) / len(cs)
    s = sum(y for _, y in cs) / len(cs)
    yaw, r = math.degrees(math.atan2(s, c)), math.hypot(c, s)
    a = abs(yaw)
    b_side = (yaw > 0) == (k["B_SIGN"] > 0)
    if r < k["MIN_R"]:
        cls = "UNKNOWN"
    elif a <= k["FRONT_MAX_DEG"]:
        cls = "C"
    elif a <= k["OBLIQUE_MAX_DEG"]:
        cls = "B" if b_side else "D"
    elif a < k["REAR_MIN_DEG"]:
        cls = "SIDE_B" if b_side else "SIDE_D"
    elif a < k["REAR_PURE_MIN_DEG"]:
        cls = "A" if b_side else "E"
    else:
        cls = "R"
    return {"yaw_deg": yaw, "r": r, "class": cls, "frames": len(cs)}


# ---------------------------------------------------------------- 로그·계획·라벨

def _log_dict(spec: tuple, idx: int, frames: list[dict], feats: list[dict], reps: dict | None) -> dict:
    sid, ex, orient, n, _, _ = spec
    w, h = IMAGE[orient]
    fr = []
    for f, fe in zip(frames, feats):
        fr.append({"t_ms": f["t_ms"], "infer_ms": f["infer_ms"], "visible": sum(v >= 0.5 for v in f["vis"]), "vis": f["vis"],
                   "xy": f["xy"], "w": f["w"], "up": f["up"], "features": fe})
    last = frames[-1]["t_ms"]
    return {"set_id": f"20260924T09{idx:02d}00-fixt{idx:04d}", "created_at": f"2026-09-24T09:{idx:02d}:30Z", "subject_id": "s-fixture01",
            "exercise": ex, "note": f"session:{APP_NAME[ex]} assessment_end_ms={last} ", "mode": "coach", "measurements": [],
            "app_version": "fixture-procedural", "validation": True, "image": {"w": w, "h": h}, "view": view_block(feats),
            "frames": fr, "reps": reps}


def _reps_block(r: dict, ex: str) -> dict:
    """재생 결과(live) → 앱 SetLogJson 의 reps 블록(RepEngineLog.of 와 같은 필드)."""
    cyc = r["cycles"]
    rom_tier = "none" if r.get("romThreshold") is None or r.get("romDirection") not in ("min", "max") else (
        "validated" if r.get("romValidated") else "reference")
    config = {"feature": r["feature"], "min_amp": r["minAmp"], "refractory_ms": r["refractoryMs"], "max_gap_ms": r["maxGapMs"],
              "complete_on_return": r["completeOnReturn"], "rom_tier": rom_tier}
    if rom_tier != "none":
        config.update({"rom_direction": r["romDirection"], "rom_threshold": r["romThreshold"]})
    block = {"count": r["reps"], "invalid": sum(c[2] is False for c in cyc), "signal": r["feature"], "t_ms": list(r["publishedMs"]),
             "min": [c[0] for c in cyc], "max": [c[1] for c in cyc], "valid": [c[2] for c in cyc],
             "engine": "return_v1", "config": config, "resets": []}
    unit = setlog_captures.current_unit(ex, False)
    if unit == "side_pair":
        cpr = setlog_captures.UNIT_CYCLES[unit]
        block.update({"unit": unit, "cycles_per_rep": cpr, "completed": r["reps"] // cpr, "half_pending": r["reps"] % cpr != 0})
    return block


def _plan_rows(specs: list[tuple], truths: dict[str, dict]) -> list[dict]:
    rows = []
    for k, spec in enumerate(specs, 1):
        sid, ex, orient, n, _, opt = spec
        pe = PLAN_EX[ex]
        cond = "prep" if opt.get("idle") else "normal"
        cpr = rep_validation_plan.UNIT_CYCLES[pe["unit"]]
        t = truths[sid]
        row = {f: "" for f in rep_validation_plan.FIELDS}
        row.update({"plan_id": f"fixture-P1-{k:02d}", "gate": "gateA", "person": "P1", "block": 1 if ex == "바벨 스쿼트" else 2,
                    "set_in_block": k, "exercise_app": pe["app"], "exercise": ex, "placement": f"{pe['place']} ({pe['placeName']})",
                    "condition": cond, "rep_unit": pe["unit"], "planned_reps": n // cpr, "planned_cycles": n,
                    "tempo_s_per_rep": pe["pace"], "instructions": f"(픽스처) {rep_validation_plan.count_phrase(pe['unit'], n)}"
                                    + (" — 앞뒤 준비·정리 동작" if opt.get("idle") else ""),
                    "notes": f"픽스처 {sid} · {orient}" + (" · 중간에 무릎·발목 화면 밖" if opt.get("drift") else "")})
        if pe["unit"] == "side_pair":
            row.update({"tally_left": t["left"], "tally_right": t["right"], "tally_order": t["order"]})
        else:
            row["tally_reps"] = t["cycles"]
        rows.append(row)
    return rows


def make(out: Path, seed: int = 20260924) -> dict:
    work = out / "work"
    phone = out / "phone"
    work.mkdir(parents=True, exist_ok=True)
    phone.mkdir(parents=True, exist_ok=True)
    synth, truths = {}, {}
    for spec in SETS:
        frames, truth = synth_frames(spec, seed)
        meta = {"source": "fixture", "set": spec[0], "exercise": spec[1], "imageW": IMAGE[spec[2]][0], "imageH": IMAGE[spec[2]][1]}
        feats = dump_features(frames, meta, work, spec[0])
        synth[spec[0]] = (frames, feats)
        truths[spec[0]] = truth
    # 1차: reps 없이 → 지금 빌드의 live 카운터로 재생(로그에 적힌 5자리 피처 그대로) → 앱이 적었을 reps 블록
    pass1 = work / "pass1"
    pass1.mkdir(exist_ok=True)
    logs1 = [setlog_captures.encode_setlog(_log_dict(spec, k, *synth[spec[0]], None)) for k, spec in enumerate(SETS, 1)]
    (pass1 / "sets-20260924.jsonl").write_text("\n".join(logs1) + "\n", encoding="utf-8")
    idx1 = setlog_captures.build([pass1], work / "pass1_cap", [])
    _, res1, _ = run_replay.replay_index(work / "pass1_cap", work / "pass1_res", {"live"})
    by_set = {e["setId"]: e for e in idx1["sets"]}
    lines, expected = [], {}
    for k, spec in enumerate(SETS, 1):
        d = _log_dict(spec, k, *synth[spec[0]], None)
        r = res1[f"{run_replay.set_key(by_set[d['set_id']])}|live"]
        d["reps"] = _reps_block(r, spec[1])
        lines.append(setlog_captures.encode_setlog(d))
        t = truths[spec[0]]
        expected[d["set_id"]] = {"fixture": spec[0], "exercise": spec[1], "orientation": spec[2], "cycles": t["cycles"],
                                 "left": t.get("left"), "right": t.get("right"), "drift": bool(spec[5].get("drift")),
                                 "idle": bool(spec[5].get("idle")), "appCount": r["reps"], "view": (d["view"] or {}).get("class")}
    (phone / "sets-20260924.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")
    # 자가 라벨(완료 화면 — 화면 단위: 런지는 쌍 = min(왼, 오른)). 검증 모드에서 사용자는 숫자를 못 봤으므로 edited
    truth_rows = ["set_id,reps_min,reps_max,exercise,form,source,created_at"]
    label_rows = []
    for k, (sid, e) in enumerate(expected.items(), 1):
        unit = setlog_captures.current_unit(e["exercise"], False)
        disp = min(e["left"], e["right"]) if unit == "side_pair" else e["cycles"]
        created = f"2026-09-24T09:{k:02d}:55Z"
        truth_rows.append(f"{sid},{disp},{disp},{e['exercise']},,edited,{created}")
        label_rows.append(json.dumps({"set_id": sid, "exercise": e["exercise"], "actual_reps": disp, "reps_source": "edited", "form": None,
                                      "created_at": created, "rep_unit": unit}, ensure_ascii=False))
    (phone / "rep_truth.csv").write_text("\n".join(truth_rows) + "\n", encoding="utf-8")
    (phone / "labels").mkdir(exist_ok=True)
    (phone / "labels" / "set_labels.jsonl").write_text("\n".join(label_rows) + "\n", encoding="utf-8")
    plan = out / "plan_gateA_fixture.csv"
    rep_validation_plan.write_csv(_plan_rows(SETS, truths), plan)
    info = {"seed": seed, "source": "procedural", "phone": str(phone), "plan": str(plan), "sets": expected}
    (out / "fixture_truth.json").write_text(json.dumps(info, ensure_ascii=False, indent=1), encoding="utf-8")
    return info


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--seed", type=int, default=20260924)
    args = ap.parse_args()
    info = make(args.out, args.seed)
    for sid, e in info["sets"].items():
        side = f" (왼 {e['left']} · 오른 {e['right']})" if e["left"] is not None else ""
        print(f"{sid}  {e['exercise']:<16} {e['orientation']:<9} 정답 {e['cycles']}{side}  앱(live) {e['appCount']}  뷰 {e['view']}"
              + ("  [화면 밖 구간]" if e["drift"] else "") + ("  [준비·정리 동작]" if e["idle"] else ""))
    print(f"\n→ {args.out}/phone (세트 로그·라벨), {info['plan']}")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
