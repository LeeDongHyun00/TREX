# -*- coding: utf-8 -*-
"""M0 렙 카운터 재생 하니스 — 실기기 세트 로그로 카운터를 검증한다 (설계 §5, spec §27).

앱에 이식될 **스트리밍 카운터**의 파이썬 레퍼런스를 구현하고, 기기에서 회수한 세트 로그
(outputs/logs/sets-*.jsonl)에 프레임 단위로 재생한다. 정답은 rep_truth.csv (사용자 자가 라벨).

스트리밍 카운터 (Kotlin 이식 대상 규약 — REP_SIGNALS v3 + 설계 §2):
- 평활: 샘플 밀도 적응 — 최근 렙당 샘플 ≥8 이면 3점 이동 중앙값, 아니면 원시값
  (성긴 신호 평활은 렙 꼭대기를 지운다 — AIHub 실측)
- 밴드: 극값-중점 — 최근 W초 창의 p10/p90 → center ± frac×(p90−p10)
  (분위수 밴드는 비대칭 듀티 사이클(컬형)에서 붕괴 — 적대 검증 실측)
- 진폭 게이트: (p90−p10) < 종목별 최소 진폭(물리 단위) → 카운트 정지
  (분위수식 상대 게이트는 방향이 틀림 — 컬형 기각 사고. 물리 단위가 맞다)
- 렙 1 = 하단 아래 → 상단 위 복귀(전체 사이클) + 불응기 1.2s
- 등척성(플랭크): 같은 카운터를 돌리되 기대값 0 — 게이트가 막고, ACTIVE 유지시간이 산출물

한계: 세트 로그에는 셋업/정리 동작(기어들어오기)이 포함된다 — 상태기계(SETTLING/END)가 없는
M0 재생은 그 오염까지 카운터가 견디는지 보는 스트레스 테스트를 겸한다.
"""
from __future__ import annotations

import json
import sys
from bisect import insort
from collections import deque
from pathlib import Path

import numpy as np
import pandas as pd

sys.stdout.reconfigure(encoding="utf-8")
HERE = Path(__file__).resolve().parent
LOGS = HERE / "outputs" / "logs"
OUT = HERE / "outputs"

# 종목 → (렙 신호, 최소 진폭[물리 단위], 등척성 여부). REP_SIGNALS 큐레이션과 일치.
SIGNALS = {
    "푸시업": ("wrist_shoulder_d", 0.30, False),     # 몸통 정규화 거리 — 실기기 렙 스윙 ~1.0
    "니푸쉬업": ("wrist_shoulder_d", 0.30, False),
    "크런치": ("head_ground", 0.15, False),
    "라잉 레그 레이즈": ("hip_ang", 25.0, False),      # 도
    "힙쓰러스트": ("hip_dev_ankle", 0.10, False),
    "Y - Exercise": ("hand_shoulder_off", 0.20, False),
    "시저크로스": ("knee_gap2d", 0.25, False),
    "바이시클 크런치": ("knee_gap2d", 0.25, False),
    "플랭크": ("trunk_ankle_ang", 35.0, True),        # 등척성 진단용 — 게이트를 실측 잡음 바닥(10~30°/5s) 위로
}


class StreamRepCounter:
    """프레임 단위 온라인 카운터 — Kotlin RepCounter 의 레퍼런스 구현."""

    def __init__(self, min_amp: float, frac: float = 0.15, window_s: float = 20.0,
                 refractory_s: float = 1.2, isometric: bool = False):
        self.min_amp = min_amp
        self.frac = frac
        self.window_s = window_s
        self.refractory_s = refractory_s
        self.isometric = isometric
        self.raw3: deque[float] = deque(maxlen=3)          # 평활용 최근 3원시값
        self.hist: deque[tuple[float, float]] = deque()    # (t, 평활값) — 밴드 창
        self.state = "mid"
        self.reps = 0
        self.rep_times: list[float] = []
        self.last_rep_t = -1e9
        self.active_s = 0.0            # 게이트 통과(활동) 시간 — 등척성 유지시간
        self._last_t: float | None = None
        self.period_est: float | None = None               # 최근 렙 주기 추정 (적응 샘플링 신호)

    def _samples_per_rep(self) -> float:
        if self.period_est is None or len(self.hist) < 2:
            return 99.0
        dt = (self.hist[-1][0] - self.hist[0][0]) / max(len(self.hist) - 1, 1)
        return self.period_est / max(dt, 1e-6)

    def on_frame(self, t_s: float, value: float | None) -> bool:
        """value=None 이면 가림(피처 유보) — 카운터 일시정지. 렙 완료 시 True."""
        if value is None or not np.isfinite(value):
            return False
        self.raw3.append(float(value))
        # 평활: 렙당 샘플이 충분할 때만 (성긴 신호 평활 금지 — AIHub 실측)
        v = float(np.median(self.raw3)) if len(self.raw3) == 3 and self._samples_per_rep() >= 8 else float(value)
        self.hist.append((t_s, v))
        while self.hist and t_s - self.hist[0][0] > self.window_s:
            self.hist.popleft()
        if len(self.hist) < 8:
            return False
        vals = np.fromiter((x[1] for x in self.hist), float)
        p10, p90 = np.quantile(vals, [0.10, 0.90])
        if (p90 - p10) < self.min_amp:                      # 진폭 게이트 (물리 단위)
            self.state = "mid"
            return False
        if self._last_t is not None:
            self.active_s += min(t_s - self._last_t, 1.0)
        self._last_t = t_s
        # 밴드 폭은 0.15 고정: 넓히면 얕은 렙(실기기 깊이부족 푸시업)을 놓친다 — M0 실측으로
        # frac 0.30 이 라벨 세트를 ✗ 로 후퇴시켰다. 잡음 억제는 밴드가 아니라 진폭 게이트의 몫.
        center = (p10 + p90) / 2
        half = self.frac * (p90 - p10)
        lo, hi = center - half, center + half
        if self.state != "low" and v <= lo:
            self.state = "low"
        elif self.state == "low" and v >= hi:
            self.state = "high"
            if t_s - self.last_rep_t >= self.refractory_s:
                if self.last_rep_t > -1e8:
                    p = t_s - self.last_rep_t
                    self.period_est = p if self.period_est is None else 0.5 * self.period_est + 0.5 * p
                self.reps += 1
                self.rep_times.append(t_s)
                self.last_rep_t = t_s
                return True
        return False


def batch_v3(values: np.ndarray, frac: float = 0.15) -> int:
    """설문(rep_signal_survey)과 동일한 오프라인 v3 — 스트리밍과의 대조군."""
    v = values[np.isfinite(values)]
    if len(v) < 8:
        return -1
    if len(v) > 24:
        v = np.array([np.median(v[max(0, i - 1):i + 2]) for i in range(len(v))])
    p10, p90 = np.quantile(v, [0.10, 0.90])
    center, half = (p10 + p90) / 2, 0.15 * (p90 - p10)
    lo, hi = center - half, center + half
    if hi <= lo:
        return 0
    st = "high" if v[0] >= hi else ("low" if v[0] <= lo else "mid")
    reps = 0
    for x in v:
        if st != "low" and x <= lo:
            st = "low"
        elif st == "low" and x >= hi:
            reps += 1
            st = "high"
    return reps


def main() -> None:
    truth = {}
    tpath = LOGS / "rep_truth.csv"
    if tpath.exists():
        for _, r in pd.read_csv(tpath, dtype=str).iterrows():
            truth[r.set_id] = (int(r.reps_min), int(r.reps_max))

    rows = []
    for f in sorted(LOGS.glob("sets-*.jsonl")):
        for line in f.open(encoding="utf-8"):
            if not line.strip():
                continue
            log = json.loads(line)
            ex = log["exercise"]
            if ex not in SIGNALS:
                continue
            sig, min_amp, iso = SIGNALS[ex]
            frames = log["frames"]
            sc = StreamRepCounter(min_amp, isometric=iso)
            series = []
            for fr in frames:
                val = (fr.get("features") or {}).get(sig)
                sc.on_frame(fr["t_ms"] / 1000.0, val)
                series.append(val if val is not None else np.nan)
            series = np.array(series, float)
            n_fin = int(np.isfinite(series).sum())
            dur = frames[-1]["t_ms"] / 1000.0 if frames else 0.0
            b = batch_v3(series)
            tr = truth.get(log["set_id"])
            if tr:
                verdict = "✓ 적중" if tr[0] <= sc.reps <= tr[1] else ("±1" if tr[0] - 1 <= sc.reps <= tr[1] + 1 else "✗")
            elif iso:
                # 설계상 등척성은 카운터를 돌리지 않는다(HoldTimer). 여기서는 잡음 진단으로만 표기.
                verdict = f"등척성 — HoldTimer {sc.active_s:.0f}s (잡음 교차 {sc.reps})"
            else:
                verdict = "—"
            amp = np.nan
            fin = series[np.isfinite(series)]
            if len(fin) >= 8:
                amp = float(np.subtract(*np.quantile(fin, [0.90, 0.10])))
            rows.append(dict(
                date=f.stem.replace("sets-", ""), set_id=log["set_id"], exercise=ex,
                note=log.get("note") or "", frames=len(frames), measured=n_fin, dur_s=round(dur, 1),
                signal=sig, amp=round(amp, 3) if np.isfinite(amp) else np.nan,
                stream=sc.reps, batch=b, truth=(f"{tr[0]}~{tr[1]}" if tr else ""),
                verdict=verdict, active_s=round(sc.active_s, 1),
                period_s=round(sc.period_est, 1) if sc.period_est else np.nan,
                rep_times=";".join(f"{t:.1f}" for t in sc.rep_times),
            ))

    res = pd.DataFrame(rows)
    res.to_csv(OUT / "rep_replay.csv", index=False, encoding="utf-8-sig")

    L = ["# M0 렙 카운터 재생 — 실기기 로그 검증\n",
         "스트리밍 카운터(Kotlin 이식 규약: 적응 평활 · 극값-중점 밴드 · 물리 단위 진폭 게이트 · 불응기 1.2s)를",
         "기기 세트 로그에 프레임 단위로 재생. 정답 = 사용자 자가 라벨(rep_truth.csv). 상태기계 없이 돌리므로",
         "셋업/정리 오염을 카운터가 견디는지의 스트레스 테스트를 겸한다.\n",
         "| 날짜 | 세트 | 종목 | 측정/프레임 | 길이(s) | 진폭 | 스트리밍 | 배치 | 정답 | 판정 | 주기(s) |",
         "|---|---|---|---|---|---|---|---|---|---|---|"]
    for _, r in res.iterrows():
        L.append(f"| {r.date} | {r.note or r.set_id[-8:]} | {r.exercise} | {r.measured}/{r.frames} | {r.dur_s} | "
                 f"{r.amp} | **{r.stream}** | {r.batch} | {r.truth} | {r.verdict} | {r.period_s} |")
    labeled = res[res.truth != ""]
    iso = res[res.exercise == "플랭크"]
    L += ["",
          f"- 라벨 세트 {len(labeled)}개: 적중 {int((labeled.verdict == '✓ 적중').sum())} · ±1 {int((labeled.verdict == '±1').sum())} · 실패 {int((labeled.verdict == '✗').sum())}",
          f"- 등척성(플랭크) {len(iso)}개: 앱에서는 카운터 미적용(HoldTimer). 카운터를 강제로 돌린 잡음 교차 {iso.stream.tolist()} — "
          "**기기 잡음 바닥의 실측**: 유지 중에도 trunk_ankle_ang p90-p10 이 5초 창당 10~30° → 각도형 신호의 최소 진폭 게이트는 30° 이상이어야 한다",
          "- 라벨 없는 세트의 스트리밍 값은 참고용 — 다음 촬영부터 세트별 실제 횟수를 기록하면 정답이 늘어난다."]
    (OUT / "REP_REPLAY.md").write_text("\n".join(L), encoding="utf-8")
    print(res[["date", "note", "exercise", "measured", "stream", "batch", "truth", "verdict"]].to_string(index=False))
    print(f"\n[done] → {OUT / 'REP_REPLAY.md'}")


if __name__ == "__main__":
    main()
