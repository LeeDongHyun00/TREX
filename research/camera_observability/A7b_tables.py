# -*- coding: utf-8 -*-
"""A7b — reps.csv 를 표로: 후보 × (정상 반복 오탐률 뷰별·스트림별 / 검출 / 유보율), 안정성, 특수 반복, 발끝 후보, 필요 프레임 수.

정상 반복 = rehab correctness 1 + mmfit 라벨 창 안(정상 가정). 오탐 = 정상 반복이 위반으로 판정된 비율(유보 제외 분모 = 판정된 반복).
뷰 = rehab17_front(C) · rehab18_obl(B/D) · mmfit_C · mmfit_BD, 참고 rehab17_half(앱 SIDE 등급) · rehab18_side(앱 A/E 등급, 옆).
출력: outputs/A7b/summary.md, tables/*.csv
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.stdout.reconfigure(encoding="utf-8")
HERE = Path(__file__).resolve().parent
OUT = HERE / "outputs" / "A7b"
TAB = OUT / "tables"
TAB.mkdir(parents=True, exist_ok=True)
R = pd.read_csv(OUT / "reps.csv", encoding="utf-8-sig")
S = pd.read_csv(OUT / "sessions.csv", encoding="utf-8-sig")
VIEWS = ["rehab17_front", "mmfit_C", "rehab18_obl", "mmfit_BD", "rehab17_half", "rehab18_side"]
VIEW_KO = {"rehab17_front": "REHAB 정면(C)", "mmfit_C": "MM-Fit C", "rehab18_obl": "REHAB 사선(B/D)", "mmfit_BD": "MM-Fit B/D",
           "rehab17_half": "REHAB 반측면(SIDE)", "rehab18_side": "REHAB 옆(A/E)"}
L: list[str] = ["# A7b — 발 너비·발끝 후보 측정법: 모집단 오탐률·폰 검출 (생성물, 요약은 A7b_REPORT.md)\n"]


def log(s=""):
    print(s); L.append(s)


def pct(k, n):
    return "—" if n == 0 else f"{100 * k / n:.0f}% ({k}/{n})"


NORMAL = R[(R.label == "normal") & (R.dataset != "phone")].copy()
# MM-Fit 300 ms 캡처(64세트)와 cap30 c300(15세트)은 같은 워크아웃 5개가 겹친다 — 뷰별 300 ms 표는 64세트 캡처(mmfit)만, 30 fps 대조는 cap30(mmfit30) 짝으로
POP300 = NORMAL[((NORMAL.dataset == "rehab") & (NORMAL.stream == "c300")) | (NORMAL.dataset == "mmfit")]
PAIR = NORMAL[NORMAL.dataset.isin(["rehab", "mmfit30"])]

# ----------------------------------------------------------------------------------------------- 1. 발 너비 오탐
ST_CANDS = [  # (열, 표시, 방향, 임계 목록)
    ("st_app", "(a) 앱: 서 있는 극값 ÷ 시작 stance_2d(현재 어깨)", "rel", [1.3, 1.4, 1.5]),
    ("st_stand_ext_ank", "(a′) 서 있는 극값, 발목 px ÷ 시작 발목 px", "rel", [1.3, 1.4, 1.5]),
    ("st_bot_rel", "(b) 바닥 중앙값 발목 px ÷ 시작 발목 px", "rel", [1.3, 1.4, 1.5]),
    ("st_bot_abs", "(c) 바닥 중앙값 발목 px ÷ 시작 어깨 px (절대)", "abs", [1.5, 1.6, 1.8]),
    ("st_fr_max", "(d) 창 프레임마다 stance_2d, 최대 (절대)", "abs", [1.5, 1.6, 1.8]),
    ("st_fr_med", "(d′) 창 프레임마다 stance_2d, 중앙값 (절대)", "abs", [1.5, 1.6, 1.8]),
    ("st_stand_max_abs", "(d″) 서 있는 프레임 stance_2d 최대 (절대)", "abs", [1.5, 1.6, 1.8]),
]


def fp_table(df: pd.DataFrame, group_col: str, groups: list[str], cands, thr_pick=None, title=""):
    log(f"\n### {title}\n")
    hdr = "| 후보 | 임계 | " + " | ".join(groups) + " |"
    log(hdr); log("|---|---|" + "---|" * len(groups))
    rows = []
    for col, name, kind, thrs in cands:
        for thr in thrs:
            if thr_pick is not None and thr not in thr_pick:
                continue
            cells = []
            for g in groups:
                d = df[df[group_col] == g]
                v = d[col].to_numpy(float)
                judged = np.isfinite(v)
                fp = judged & (v > thr)
                cells.append(pct(int(fp.sum()), int(judged.sum())))
                rows.append({"candidate": col, "thr": thr, "group": g, "n": int(judged.sum()), "n_abstain": int((~judged).sum()), "fp": int(fp.sum()),
                             "fp_rate": float(fp.sum() / judged.sum()) if judged.sum() else np.nan})
            log(f"| {name} | ×{thr} | " + " | ".join(cells) + " |")
    return pd.DataFrame(rows)


def abstain_table(df, group_col, groups, cands, title):
    log(f"\n### {title}\n")
    log("| 후보 | " + " | ".join(groups) + " |"); log("|---|" + "---|" * len(groups))
    for col, name, kind, thrs in cands:
        cells = []
        for g in groups:
            d = df[df[group_col] == g]
            v = d[col].to_numpy(float)
            cells.append(pct(int((~np.isfinite(v)).sum()), len(v)))
        log(f"| {name} | " + " | ".join(cells) + " |")


log("## 0. 표본\n")
log("정상 반복 수(카운터가 센 사이클 중 정답 반복에 대응한 것) — 뷰 × 스트림:\n")
cnt = NORMAL.groupby(["view", "dataset", "stream"]).size().unstack(["dataset", "stream"]).fillna(0).astype(int)
log(cnt.to_string()); log("")
gt = {"rehab17_front": 72, "rehab17_half": 62, "rehab18_obl": 62, "rehab18_side": 72}
log("REHAB 정답 정상 반복 수(카운터 검출 전): " + ", ".join(f"{VIEW_KO[k]} {v}" for k, v in gt.items()) + " — 옆(A/E)은 월드 무릎각이 흔들려 카운터가 거의 못 센다(A3 §5).")
log("사람 수: REHAB 9명, MM-Fit 워크아웃 21개(사람 ~10명, 워크아웃 단위로만 구분). 폰 1명.")

log("\n## 1. 발 너비 후보 — 정상 반복 오탐률 (진짜 300 ms: REHAB c300 + MM-Fit 64세트 캡처)")
fp300 = fp_table(POP300, "view", VIEWS, ST_CANDS, title="1a. 300 ms, 뷰별 (넓어짐 방향만: 값 > 임계)")
fp300.to_csv(TAB / "stance_fp_300.csv", index=False, encoding="utf-8-sig")
abstain_table(POP300, "view", VIEWS, ST_CANDS, "1b. 300 ms, 유보율(값 없음 ÷ 정상 반복)")
log("\n### 1c. 30 fps 대 진짜 300 ms — 같은 세트(REHAB + MM-Fit cap30 15세트), 주요 뷰 합산 (C = 정면+MM-Fit C, B/D = 사선+MM-Fit B/D)\n")
PAIR = PAIR.assign(grp=PAIR.view.map({"rehab17_front": "C", "mmfit_C": "C", "rehab18_obl": "BD", "mmfit_BD": "BD"}))
PAIR2 = PAIR[PAIR.grp.notna()].copy()
PAIR2["grp_stream"] = PAIR2.grp + "/" + PAIR2.stream
fpp = fp_table(PAIR2, "grp_stream", ["C/f30", "C/c300", "BD/f30", "BD/c300"], ST_CANDS, thr_pick=[1.4, 1.5, 1.6], title="")
fpp.to_csv(TAB / "stance_fp_30v300.csv", index=False, encoding="utf-8-sig")
log("\n### 1d. 낮아짐 방향(값 < 임계) 참고 — 300 ms, 상대 후보\n")
log("| 후보 | 임계 | " + " | ".join(VIEWS[:4]) + " |"); log("|---|---|" + "---|" * 4)
for col, name, kind, thrs in ST_CANDS[:3]:
    for thr in (0.6, 0.7):
        cells = []
        for g in VIEWS[:4]:
            v = POP300[POP300.view == g][col].to_numpy(float); j = np.isfinite(v)
            cells.append(pct(int((j & (v < thr)).sum()), int(j.sum())))
        log(f"| {name} | <×{thr} | " + " | ".join(cells) + " |")

# ----------------------------------------------------------------------------------------------- 2. 안정성
log("\n## 2. 반복 안 안정성 — 정상 반복의 프레임 간 SD 중앙값 (p50, [p90])\n")
SD_COLS = [("sd_stand_2d", "서 있는 프레임 stance_2d"), ("sd_stand_ank", "서 있는 프레임 발목 px ÷ 시작"), ("sd_bot_2d", "바닥 stance_2d"),
           ("sd_bot_ank", "바닥 발목 px ÷ 시작"), ("sd_win_2d", "창 전체 stance_2d"), ("sd_win_ank", "창 전체 발목 px ÷ 시작"),
           ("cv_sh_stand", "어깨 px 변동계수(서 있음)"), ("cv_sh_win", "어깨 px 변동계수(창 전체)")]
groups = [("REHAB 정면 300", POP300[POP300.view == "rehab17_front"]), ("MM-Fit C 300", POP300[POP300.view == "mmfit_C"]),
          ("MM-Fit B/D 300", POP300[POP300.view == "mmfit_BD"]), ("REHAB 정면 30fps", PAIR[(PAIR.view == "rehab17_front") & (PAIR.stream == "f30")]),
          ("폰 12:19·16:14·16:16 정상", R[(R.dataset == "phone") & (R.label == "normal") & (R.session != "16:13")])]
log("| 양 | " + " | ".join(g for g, _ in groups) + " |"); log("|---|" + "---|" * len(groups))
for col, name in SD_COLS:
    cells = []
    for g, d in groups:
        v = d[col].to_numpy(float); v = v[np.isfinite(v)]
        cells.append(f"{np.median(v):.3f} [{np.percentile(v, 90):.3f}] n{len(v)}" if len(v) else "—")
    log(f"| {name} | " + " | ".join(cells) + " |")
log("")
for g, d in groups:
    v = d["sh_min_over_start"].to_numpy(float); v = v[np.isfinite(v)]
    if len(v):
        log(f"- {g}: 창 안 어깨 px 최소 ÷ 시작 어깨 px < 0.5 인 반복 {pct(int((v < 0.5).sum()), len(v))}, < 0.7 {pct(int((v < 0.7).sum()), len(v))}")

# ----------------------------------------------------------------------------------------------- 3. 특수 반복
log("\n## 3. 특수 반복 — 후보별 값 (위반 판정: 상대 ×1.4 / 절대 1.6 기준)\n")
CANDS_SHORT = [("st_app", "(a)앱", 1.5), ("st_stand_ext_ank", "(a′)서있음극값·발목", 1.4), ("st_bot_rel", "(b)바닥÷시작발목", 1.4), ("st_bot_abs", "(c)바닥÷시작어깨", 1.6),
               ("st_fr_max", "(d)프레임최대", 1.6), ("st_fr_med", "(d′)프레임중앙", 1.6)]


def special(df, title, note=""):
    log(f"\n### {title}\n" + (note + "\n" if note else ""))
    log("| 반복 | 라벨 | " + " | ".join(n for _, n, _ in CANDS_SHORT) + " |"); log("|---|---|" + "---|" * len(CANDS_SHORT))
    for _, r in df.iterrows():
        cells = []
        for col, n, thr in CANDS_SHORT:
            v = r[col]
            cells.append("유보" if not np.isfinite(v) else (f"**{v:.2f}**" if v > thr else f"{v:.2f}"))
        log(f"| {r['session']} #{int(r['cycle'])} | {r['label']} | " + " | ".join(cells) + " |")


valg = R[(R.dataset == "rehab") & (R.session.str.startswith("PM_029")) & (R.label == "error")]
special(valg[valg.stream == "c300"], "3a. 무릎 모임(valgus) 반복 — REHAB PM_029 오류 반복, 300 ms (기준 = 그 블록 첫 반복)",
        "PM_029 오류 블록은 정면(b1)·반측면(b3) 각 5회, cam17·cam18. valgus 반복에서 발 너비 후보가 '넓어짐'을 내면 안 된다(REHAB 오류는 스탠스가 좁다 — 좁아짐 방향은 별도).")
for col, n, thr in CANDS_SHORT:
    v = valg[valg.stream == "c300"][col].to_numpy(float); j = np.isfinite(v)
    log(f"- {n}: 넓어짐 위반 {pct(int((j & (v > thr)).sum()), int(j.sum()))}, 좁아짐(<0.7 / 절대 <0.5) {pct(int((j & (v < (0.7 if thr < 1.6 else 0.5))).sum()), int(j.sum()))}")
ph = R[R.dataset == "phone"]
special(ph[ph.label.isin(["toe_in", "toe_out", "wide+toe_out"])], "3b. 발끝 회전 반복(폰) — 발 너비 후보는 위반이면 안 된다(12:19 3~6, 16:16 3·6; 11:37 은 좌표 없음)")
special(ph[ph.label.isin(["wide", "wide?"]) & (ph.session != "16:13")], "3c. 넓힘 반복(폰) — 검출돼야 한다(12:19 7·8, 16:14 5~7, 16:16 4·5)")
special(ph[ph.session == "16:16"], "3d. 16:16 세트 전체 — 오늘 오탐 2건(#2 ×3.21, #6 ×1.90)이 해소되는가")
special(ph[(ph.session == "12:19") | (ph.session == "16:14")], "3e. 12:19 · 16:14 세트 전체(참고)")
special(ph[ph.session == "16:13"], "3f. 16:13 세트(참고 — 시작 자세가 발을 모은 채 잡혔다: 첫 상단 창 발목 60 px → 하강 직전 97 px. up 도 틀린 세트라 2D 만)")

# ----------------------------------------------------------------------------------------------- 4. 발끝
log("\n## 4. 발끝 후보 — 시작 대비 변화(°; lat 는 비율 ×100 ≈ °)")
MEAS = [("w", "월드 toe_out(앱)"), ("ank2d", "발목→발끝 2D 각"), ("heel2d", "뒤꿈치→발끝 2D 각"), ("lat", "(발끝x−뒤꿈치x)÷정강이 px ×100")]
COMB = [("max", "maxside(앱: 더 벌어진 쪽)"), ("any", "한쪽이라도"), ("mean", "양발 평균")]


def toe_val(df, m, comb, n):
    """comb 별 판정값 — max/mean 은 열 그대로, any 는 |L|,|R| 중 큰 쪽(부호는 그쪽)."""
    suf = f"_n{n}" if isinstance(n, int) else "_stand"
    if comb in ("max", "mean"):
        v = df[f"toe_{m}_{comb}{suf}"].to_numpy(float)
    else:
        a, b = df[f"toe_{m}_L{suf}"].to_numpy(float), df[f"toe_{m}_R{suf}"].to_numpy(float)
        aa, bb = np.nan_to_num(np.abs(a), nan=-1), np.nan_to_num(np.abs(b), nan=-1)
        v = np.where(aa >= bb, a, b); v[(aa < 0) & (bb < 0)] = np.nan
    return v * (100.0 if m == "lat" else 1.0)


log("\n### 4a. 정상 반복 분포 p5 / p50 / p95 (300 ms, 하강 직전 3프레임 중앙값 − 시작 1 s 중앙값)\n")
log("| 측정 | 결합 | " + " | ".join(VIEWS[:4]) + " |"); log("|---|---|" + "---|" * 4)
for m, mn in MEAS:
    for c, cn in COMB:
        cells = []
        for g in VIEWS[:4]:
            v = toe_val(POP300[POP300.view == g], m, c, 3); v = v[np.isfinite(v)]
            cells.append(f"{np.percentile(v, 5):+.0f} / {np.percentile(v, 50):+.0f} / {np.percentile(v, 95):+.0f} n{len(v)}" if len(v) else "—")
        log(f"| {mn} | {cn} | " + " | ".join(cells) + " |")

log("\n### 4b. 정상 반복 오탐률 |Δ| > 10 / 15 / 20 (300 ms, 하강 직전 3프레임 중앙값)\n")
log("| 측정 | 결합 | " + " | ".join(VIEWS[:4]) + " |"); log("|---|---|" + "---|" * 4)
toe_rows = []
for m, mn in MEAS:
    for c, cn in COMB:
        cells = []
        for g in VIEWS[:4]:
            v = toe_val(POP300[POP300.view == g], m, c, 3); j = np.isfinite(v)
            ks = [int((j & (np.abs(v) > thr)).sum()) for thr in (10, 15, 20)]
            cells.append(f"{100 * ks[0] / j.sum():.0f} / {100 * ks[1] / j.sum():.0f} / {100 * ks[2] / j.sum():.0f}% (n{j.sum()}, 유보 {(~j).sum()})" if j.sum() else "—")
            for thr, k in zip((10, 15, 20), ks):
                toe_rows.append({"measure": m, "comb": c, "n_frames": 3, "thr": thr, "view": g, "n": int(j.sum()), "fp": k})
        log(f"| {mn} | {cn} | " + " | ".join(cells) + " |")
pd.DataFrame(toe_rows).to_csv(TAB / "toe_fp_300.csv", index=False, encoding="utf-8-sig")

log("\n### 4c. 앱 창(상단 + 복귀 뒤 서 있음, 중앙값)으로 잰 오탐률 |Δ| > 15 — 300 ms (4b 의 '하강 직전 3프레임' 과 비교)\n")
log("| 측정 | 결합 | " + " | ".join(VIEWS[:4]) + " |"); log("|---|---|" + "---|" * 4)
for m, mn in MEAS:
    for c, cn in COMB:
        cells = []
        for g in VIEWS[:4]:
            v = toe_val(POP300[POP300.view == g], m, c, "stand"); j = np.isfinite(v)
            cells.append(pct(int((j & (np.abs(v) > 15)).sum()), int(j.sum())))
        log(f"| {mn} | {cn} | " + " | ".join(cells) + " |")

log("\n### 4d. 30 fps 대 300 ms (같은 세트, C·B/D 합산, |Δ| > 15, 3프레임 중앙값)\n")
log("| 측정 | 결합 | C 30fps | C 300 | B/D 30fps | B/D 300 |"); log("|---|---|---|---|---|---|")
for m, mn in MEAS:
    for c, cn in COMB:
        cells = []
        for grp in ("C", "BD"):
            for stv in ("f30", "c300"):
                d = PAIR2[(PAIR2.grp == grp) & (PAIR2.stream == stv)]
                v = toe_val(d, m, c, 3); j = np.isfinite(v)
                cells.append(pct(int((j & (np.abs(v) > 15)).sum()), int(j.sum())))
        log(f"| {mn} | {cn} | " + " | ".join(cells) + " |")

log("\n### 4e. 폰 정답 세트 — 반복별 Δ (3프레임 중앙값; 괄호 = 앱 창 중앙값) · 12:19 / 16:16 은 2D 가능, 11:37 은 월드만\n")
log("| 반복 | 라벨 | 발 너비(b) | 월드 max | 월드 any | 월드 mean | ank2d max | ank2d any | heel2d max | heel2d any | lat max |")
log("|---|---|---|---|---|---|---|---|---|---|---|")
for _, r in ph[ph.session.isin(["12:19", "11:37", "16:16", "16:14"])].iterrows():
    d1 = ph.loc[[r.name]]

    def f(m, c):
        v = toe_val(d1, m, c, 3)[0]; s = toe_val(d1, m, c, "stand")[0]
        if not np.isfinite(v):
            return "유보"
        return (f"**{v:+.0f}**" if abs(v) > 15 else f"{v:+.0f}") + (f" ({s:+.0f})" if np.isfinite(s) else "")
    sb = r["st_bot_rel"]
    log(f"| {r['session']} #{int(r['cycle'])} | {r['label']} | {'유보' if not np.isfinite(sb) else f'{sb:.2f}'} | {f('w','max')} | {f('w','any')} | {f('w','mean')} | "
        f"{f('ank2d','max')} | {f('ank2d','any')} | {f('heel2d','max')} | {f('heel2d','any')} | {f('lat','max')} |")

log("\n### 4f. 폰 검출·오탐 요약 (±15, 3프레임 중앙값). 유보 규칙 = 같은 반복의 발 너비(b) 가 ×1.4 를 넘거나 ×0.7 미만이면 발끝 유보\n")
truth_toe = ph[ph.label.isin(["toe_in", "toe_out", "wide+toe_out"])]
norm_ph = ph[(ph.label == "normal")]
wide_ph = ph[ph.label.isin(["wide"])]
log("| 측정 | 결합 | 검출(회전 반복 8) | 오탐 정상 반복 | 오탐 넓힘 반복(유보 없음) | 오탐 넓힘 반복(유보 있음) |"); log("|---|---|---|---|---|---|")
for m, mn in MEAS:
    for c, cn in COMB:
        det = toe_val(truth_toe, m, c, 3); jd = np.isfinite(det)
        fpn = toe_val(norm_ph, m, c, 3); jn = np.isfinite(fpn)
        fpw = toe_val(wide_ph, m, c, 3); jw = np.isfinite(fpw)
        sb = wide_ph["st_bot_rel"].to_numpy(float); abst = np.isfinite(sb) & ((sb > 1.4) | (sb < 0.7))
        log(f"| {mn} | {cn} | {pct(int((jd & (np.abs(det) > 15)).sum()), int(jd.sum()))} | {pct(int((jn & (np.abs(fpn) > 15)).sum()), int(jn.sum()))} | "
            f"{pct(int((jw & (np.abs(fpw) > 15)).sum()), int(jw.sum()))} | {pct(int((jw & ~abst & (np.abs(fpw) > 15)).sum()), int((jw & ~abst).sum()))} |")

# ----------------------------------------------------------------------------------------------- 5. 필요 프레임 수
log("\n## 5. 서 있는 프레임 수 — 하강 직전 N 프레임 중앙값 (300 ms, 정상 반복, C·B/D 뷰별)\n")
log("### 5a. 발 너비: 발목 px ÷ 시작 발목 px, 위반 > ×1.4 · 참고 앱 stance_2d ÷ 시작 (> ×1.5)\n")
log("| N | " + " | ".join(f"{VIEW_KO[g]} 발목/시작 · stance_2d/시작 · (N개 확보율)" for g in VIEWS[:4]) + " |"); log("|---|" + "---|" * 4)
for n in (1, 2, 3, 5):
    cells = []
    for g in VIEWS[:4]:
        d = POP300[POP300.view == g]
        a = d[f"st_top{n}_ank"].to_numpy(float); ja = np.isfinite(a)
        b = d[f"st_top{n}_2d"].to_numpy(float); jb = np.isfinite(b)
        have = (d[f"n_top{n}"].to_numpy(float) >= n)
        cells.append(f"{pct(int((ja & (a > 1.4)).sum()), int(ja.sum()))} · {pct(int((jb & (b > 1.5)).sum()), int(jb.sum()))} · ({100 * have.mean():.0f}%)")
    log(f"| {n} | " + " | ".join(cells) + " |")
log("\n### 5b. 발끝 |Δ| > 15 — 월드 maxside / 월드 any / ank2d maxside / heel2d maxside\n")
log("| N | " + " | ".join(VIEW_KO[g] for g in VIEWS[:4]) + " |"); log("|---|" + "---|" * 4)
for n in (1, 2, 3, 5):
    cells = []
    for g in VIEWS[:4]:
        d = POP300[POP300.view == g]
        parts = []
        for m, c in (("w", "max"), ("w", "any"), ("ank2d", "max"), ("heel2d", "max")):
            v = toe_val(d, m, c, n); j = np.isfinite(v)
            parts.append(f"{100 * (j & (np.abs(v) > 15)).sum() / j.sum():.0f}%" if j.sum() else "—")
        cells.append(" / ".join(parts))
    log(f"| {n} | " + " | ".join(cells) + " |")
log("\n### 5c. 30 fps 에서 같은 N (프레임 간격 33 ms — N 프레임이 시간으로는 짧다) — C 합산, 발목/시작 > ×1.4 · 월드 maxside |Δ| > 15\n")
log("| N | 30 fps | 300 ms |"); log("|---|---|---|")
for n in (1, 2, 3, 5):
    cells = []
    for stv in ("f30", "c300"):
        d = PAIR2[(PAIR2.grp == "C") & (PAIR2.stream == stv)]
        a = d[f"st_top{n}_ank"].to_numpy(float); ja = np.isfinite(a)
        v = toe_val(d, "w", "max", n); j = np.isfinite(v)
        cells.append(f"{pct(int((ja & (a > 1.4)).sum()), int(ja.sum()))} · {pct(int((j & (np.abs(v) > 15)).sum()), int(j.sum()))}")
    log(f"| {n} | " + " | ".join(cells) + " |")
log("\n### 5d. 폰 정답 세트에서 N 별 발끝 검출/오탐 (월드 maxside · ank2d maxside, ±15; 검출 8 회전 반복 / 오탐 10 정상 반복)\n")
log("| N | 월드 검출 | 월드 오탐 | ank2d 검출 | ank2d 오탐 |"); log("|---|---|---|---|---|")
for n in (1, 2, 3, 5):
    cells = []
    for m in ("w", "ank2d"):
        det = toe_val(truth_toe, m, "max", n); jd = np.isfinite(det)
        fpn = toe_val(norm_ph, m, "max", n); jn = np.isfinite(fpn)
        cells += [pct(int((jd & (np.abs(det) > 15)).sum()), int(jd.sum())), pct(int((jn & (np.abs(fpn) > 15)).sum()), int(jn.sum()))]
    log(f"| {n} | " + " | ".join(cells) + " |")

(OUT / "summary.md").write_text("\n".join(L), encoding="utf-8")
print(f"\n→ {OUT / 'summary.md'}")
