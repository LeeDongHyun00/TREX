# -*- coding: utf-8 -*-
"""재생 캡처 형식과 앱 추론 주기 — 추출기 두 개(MediaPipe 영상, MM-Fit 3D 포즈)가 같이 쓴다.

캡처 한 줄이 앱 분석 스레드의 추론 1회다. JVM 재생기(replay-jvm/Replay.kt)가 이 줄을
PostureAnalyzer.analyzeBitmap 과 같은 후처리로 피처로 바꾼 뒤 현재 RepCounter 에 넣는다.

    # 주석
    H key=value ...
    U <tMs> <x>,<y>,<z>        (선택) 바로 다음 F 줄의 up 벡터 — 휴대폰 검증 로그(spec §61)에서 온 캡처만. 없으면 재생기가 화면 세로축
    F <tMs> <poses> <i>:<x>,<y>,<vis>,<pres>,<wx>,<wy>,<wz> ... (33개)

x,y 는 정규화 이미지 좌표, vis·pres 는 정규화 랜드마크의 visibility·presence,
wx..wz 는 MediaPipe 월드 좌표(m, y 아래·z 카메라 쪽이 음수 — 재생기가 부호를 뒤집는다).
"""
from __future__ import annotations

from pathlib import Path
from typing import Iterable, Sequence

LANDMARKS = 33

# PostureLive: InferencePolicy(sampleIntervalMs = 300). RECORDING 단계는 샘플 간격과 1:1 이고,
# shouldInfer 는 "마지막 추론 이후 300ms 이상" 인 첫 카메라 프레임에서 추론한다. 영상 시각은
# round(프레임×1000/30) 이라 30fps 에서는 9프레임째가 정확히 300ms 로 통과한다(300ms 간격).
# 실기기는 프레임 도착 지터 때문에 300~333ms 사이다 — 재생이 앱보다 성기지는 않다.
APP_SAMPLE_INTERVAL_MS = 300


def app_cadence(times_ms: Sequence[int], interval_ms: int = APP_SAMPLE_INTERVAL_MS) -> list[int]:
    """카메라 프레임 시각 목록에서 앱이 추론했을 프레임의 인덱스를 고른다 (발열 배수 1.0 가정)."""
    chosen: list[int] = []
    last: int | None = None
    for i, t in enumerate(times_ms):
        if last is None or t - last >= interval_ms:
            chosen.append(i)
            last = t
    return chosen


Landmark = tuple[float, float, float, float, float, float, float]


def frame_line(t_ms: int, landmarks: Sequence[Landmark] | None) -> str:
    """landmarks=None 이면 사람 없음(poses=0). 값이 없는 visibility/presence 는 nan 으로 쓴다."""
    if landmarks is None:
        return f"F\t{t_ms}\t0"
    if len(landmarks) != LANDMARKS:
        raise ValueError(f"expected {LANDMARKS} landmarks, got {len(landmarks)}")
    cells = [f"F\t{t_ms}\t1"]
    for i, lm in enumerate(landmarks):
        cells.append(f"{i}:" + ",".join(_num(v) for v in lm))
    return "\t".join(cells)


def _num(v: float | None) -> str:
    if v is None:
        return "nan"
    return f"{v:.5f}"


def up_line(t_ms: int, up: Sequence[float | str]) -> str:
    """U 줄. 값이 문자열이면(세트 로그에 적힌 그대로) 옮기고, 숫자면 repr — 반올림하지 않는다."""
    return f"U\t{t_ms}\t" + ",".join(v if isinstance(v, str) else repr(float(v)) for v in up)


def read_capture(path: Path) -> tuple[dict[str, str], list[dict]]:
    """캡처 읽기(표준 라이브러리). 반환 (메타, 프레임) — 프레임 = {"t", "poses", "landmarks": {i: 7-튜플(float, nan 허용)},
    "up": (x, y, z) | None}. U 줄은 바로 다음 F 줄에 붙는다(시각이 다르면 ValueError — 재생기와 같은 규약)."""
    meta: dict[str, str] = {}
    frames: list[dict] = []
    pending: tuple[int, tuple[float, float, float]] | None = None
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if parts[0] == "H":
            for kv in parts[1:]:
                k, _, v = kv.partition("=")
                meta[k] = v
        elif parts[0] == "U":
            if pending is not None:
                raise ValueError(f"U 줄 뒤에 F 줄이 없다 (t={pending[0]})")
            x, y, z = (float(q) for q in parts[2].split(","))
            pending = (int(parts[1]), (x, y, z))
        elif parts[0] == "F":
            t = int(parts[1])
            if pending is not None and pending[0] != t:
                raise ValueError(f"U 줄 시각 {pending[0]} ≠ 다음 F 줄 시각 {t}")
            lms = {}
            for cell in parts[3:]:
                i, _, rest = cell.partition(":")
                lms[int(i)] = tuple(float(q) for q in rest.split(","))
            frames.append({"t": t, "poses": int(parts[2]), "landmarks": lms, "up": pending[1] if pending else None})
            pending = None
    if pending is not None:
        raise ValueError("마지막 U 줄 뒤에 F 줄이 없다")
    return meta, frames


def write_capture(path: Path, meta: dict[str, object], lines: Iterable[str]) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    count = 0
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write("H\t" + "\t".join(f"{k}={v}" for k, v in meta.items()) + "\n")
        for line in lines:
            handle.write(line + "\n")
            count += 1
    return count
