# -*- coding: utf-8 -*-
"""재생 캡처 형식과 앱 추론 주기 — 추출기 두 개(MediaPipe 영상, MM-Fit 3D 포즈)가 같이 쓴다.

캡처 한 줄이 앱 분석 스레드의 추론 1회다. JVM 재생기(replay-jvm/Replay.kt)가 이 줄을
PostureAnalyzer.analyzeBitmap 과 같은 후처리로 피처로 바꾼 뒤 현재 RepCounter 에 넣는다.

    # 주석
    H key=value ...
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


def write_capture(path: Path, meta: dict[str, object], lines: Iterable[str]) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    count = 0
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write("H\t" + "\t".join(f"{k}={v}" for k, v in meta.items()) + "\n")
        for line in lines:
            handle.write(line + "\n")
            count += 1
    return count
