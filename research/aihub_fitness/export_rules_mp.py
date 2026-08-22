#!/usr/bin/env python
"""rules_mp_v0 확정: 실험 A-2(MediaPipe 피처 재적합) 결과에서 규칙별 최적 뷰 규칙을 골라 앱 포팅용 JSON/MD 로 내보낸다.

선택 기준
  - 단일 뷰(A~E)만 후보 (폰은 카메라 1대). 기본은 전방 반구(B/C/D) 최적 뷰, 참고로 전체 뷰 최적도 기록.
  - 등급: ship  = 전방 최적 AUC ≥ 0.85
          beta  = 0.75 ≤ AUC < 0.85
          exclude = AUC < 0.75, 또는 GT 통제군(동일 표본) < 0.75 (GT로도 관측 불가), 또는 하위유형 cervical/unspecified
  - 주의 플래그: 표본 n<40, 저충실도 피처군(손목각·어깨높이·발목높이·발피치·시계열·valgus[정면 필요])
출력: rules/rules_mp_v0.json, rules/rules_mp_v0.md
"""
from __future__ import annotations

import json
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

HERE = Path(__file__).resolve().parent
OUT = HERE / "outputs"
RULES_DIR = HERE / "rules"
FRONT_VIEWS = ["B", "C", "D"]
VIEW_DESC = {"A": "후방사선L(-40°)", "B": "전방사선L(+40°)", "C": "정면", "D": "전방사선R(-40°)", "E": "후방사선R(+40°)"}
WEAK_FAMILIES = {
    "wrist": "MediaPipe 손 포인트가 거칠어 손목각 신뢰도 낮음",
    "shoulder_h": "어깨 높이(으쓱) 미세 변화는 MP 노이즈에 묻힘",
    "ankle_y": "발목 높이(뒤꿈치 들림) 깊이 의존",
    "foot_pitch": "발 피치(뒤꿈치 들림) 깊이 의존 — MP heel(29/30) 활용 권장",
    "foot_y": "발 높이 깊이 의존",
    "ts_": "성긴 프레임 시계열 상관 — 창 정의에 민감",
    "knee_out": "valgus 는 정면(C) 뷰에서만 신뢰 (전방 사선에서 r≈0.5)",
    "kneefoot": "발 방향은 MP foot_index/heel 정밀도 한계",
}

# 앱 포팅용 피처 문서: base → (설명, 계산식, 필요한 MediaPipe 랜드마크 인덱스)
# 표기: Hip/Knee/... 는 24관절 매핑 후 이름. HipMid=(LHip+RHip)/2, ShMid=(LSh+RSh)/2, EarMid, PalmMid, AnkleMid.
# UP=(0,1,0). 신체좌표계 x_b(좌), y_b(상), z_b(전) — KOTLIN_PORTING_SPEC.md 참고. 각도는 도(deg), 길이는 cm.
FEATURE_DOC = {
    "knee": ("무릎각", "∠(Hip, Knee, Ankle); _mean=(L+R)/2, _minside=min(L,R), _maxside=max(L,R), _asym=L−R", [23, 24, 25, 26, 27, 28]),
    "hip": ("고관절각", "∠(Shoulder, Hip, Knee); _asym=L−R", [11, 12, 23, 24, 25, 26]),
    "elbow": ("팔꿈치각", "∠(Shoulder, Elbow, Wrist); _mean/_minside/_maxside/_asym 동일 규칙", [11, 12, 13, 14, 15, 16]),
    "shoulder": ("팔 거상각", "∠(Elbow, Shoulder, Hip) — shoulder_L/R", [11, 12, 13, 14, 23, 24]),
    "forearm_vert": ("전완 수직각", "angle(Wrist−Elbow, UP); 0=전완이 위로 수직", [13, 14, 15, 16]),
    "torso_incl": ("몸통 기울기", "angle(Neck−HipMid, UP); Neck=ShMid", [11, 12, 23, 24]),
    "torso_pitch": ("몸통 피치(부호)", "atan2(z_b(Neck), y_b(Neck)) in deg; + = 앞으로 숙임", [11, 12, 23, 24]),
    "head_pitch": ("머리 피치", "face=Nose−EarMid; atan2(face·UP, |수평성분|) deg; + = 위를 봄", [0, 7, 8, 23, 24]),
    "face_vs_torso": ("시선-몸통각", "angle(Nose−EarMid, Neck−HipMid); 직립+정면 ≈ 90", [0, 7, 8, 11, 12, 23, 24]),
    "face_vs_forward": ("시선-전방각", "angle(Nose−EarMid, z_b); 0 = 정면", [0, 7, 8, 23, 24]),
    "knee_out": ("무릎 외측 오프셋(valgus−)", "body frame 에서 무릎 x_b 와 Hip→Ankle 직선 위 같은 높이의 x_b 차 / |Hip−Ankle|; L:+외측, R:부호반전; _mean=(L+R)/2. 음수=무릎 모임", [23, 24, 25, 26, 27, 28]),
    "foot_pitch": ("발 피치", "asin(unit(Foot−Ankle).y) deg; Foot=foot_index(31/32). 앱에선 heel(29/30) 높이 변화가 더 직접적", [27, 28, 31, 32]),
    "ear_shoulder_gap": ("귀-어깨 간격", "(EarMid.y − ShMid.y)/torso_len", [7, 8, 11, 12, 23, 24]),
    "grip_w": ("그립 폭", "|LPalm−RPalm| / |LSh−RSh|; Palm=(pinky+index)/2", [11, 12, 17, 18, 19, 20]),
    "stance_w": ("스탠스 폭", "|LAnkle−RAnkle| / |LHip−RHip|", [23, 24, 27, 28]),
    "hand_h_asym": ("좌우 손 높이차", "(LPalm.y − RPalm.y)/torso_len", [11, 12, 17, 18, 19, 20, 23, 24]),
    "palm_h_rel": ("손 높이(키 정규화)", "(PalmMid.y − AnkleMid.y)/(Neck.y − AnkleMid.y)", [11, 12, 17, 18, 19, 20, 27, 28]),
    "palm_lat": ("손 측방 위치", "x_b(PalmMid) / |LSh−RSh|", [11, 12, 17, 18, 19, 20, 23, 24]),
    "palm_head_dist": ("손-머리 거리", "|PalmMid − EarMid| / torso_len", [7, 8, 11, 12, 17, 18, 19, 20, 23, 24]),
    "shoulder_asym": ("어깨 높이 비대칭", "(LSh.y − RSh.y) / |LSh−RSh|", [11, 12]),
    "shoulder_h": ("어깨 높이", "(Sh.y − HipMid.y)/torso_len — shoulder_h_L/R", [11, 12, 23, 24]),
    "sh_over_hip_fwd": ("어깨-골반 전후 오프셋", "z_b(Neck)/torso_len", [11, 12, 23, 24]),
    "knee_h": ("무릎 높이", "(Knee.y − HipMid.y)/leg_len — knee_h_L/R", [23, 24, 25, 26, 27, 28]),
    "knee_lat": ("무릎 측방 위치", "sign·(x_b(Knee) − x_b(Hip))/|LHip−RHip|; + 바깥", [23, 24, 25, 26]),
    "knee_elbow_dist": ("무릎-팔꿈치 거리", "min(|LKnee−LElbow|, |RKnee−RElbow|)/torso_len", [11, 12, 13, 14, 23, 24, 25, 26]),
    "elbow_torso": ("팔꿈치-몸통 거리", "point-line distance(Elbow, Hip→Shoulder)/|Sh−Hip| — elbow_torso_L/R", [11, 12, 13, 14, 23, 24]),
    "hip_height_rel": ("골반 높이(깊이)", "(HipMid.y − AnkleMid.y)/leg_len", [23, 24, 27, 28]),
    "hip_below_knee": ("골반-무릎 높이차", "(HipMid.y − KneeMid.y)/leg_len", [23, 24, 25, 26, 27, 28]),
    "upperarm_vert": ("상완 수직각", "angle(Elbow−Shoulder, UP)", [11, 12, 13, 14]),
    "elbow_h": ("팔꿈치 높이", "(Elbow.y − Sh.y)/torso_len", [11, 12, 13, 14, 23, 24]),
    "elbow_wrist_h": ("팔꿈치-손목 높이차", "(Elbow.y − Wrist.y)/torso_len", [13, 14, 15, 16, 11, 12, 23, 24]),
    "neck_over_ankle": ("상체 전방 이동", "(z_b(Neck) − z_b(AnkleMid))/leg_len", [11, 12, 23, 24, 27, 28]),
    "palm_fwd_hip": ("손 전방 거리(골반 기준)", "z_b(PalmMid)/torso_len", [11, 12, 17, 18, 19, 20, 23, 24]),
    "palm_fwd_knee": ("손 전방 거리(무릎 기준)", "(z_b(PalmMid) − z_b(KneeMid))/torso_len", [11, 12, 17, 18, 19, 20, 23, 24, 25, 26]),
    "palm_fwd_ankle": ("손 전방 거리(발목 기준)", "(z_b(PalmMid) − z_b(AnkleMid))/torso_len", [11, 12, 17, 18, 19, 20, 23, 24, 27, 28]),
    "palm_dist_body": ("손-몸통축 수평거리", "hypot(x_b(PalmMid), z_b(PalmMid))/torso_len", [11, 12, 17, 18, 19, 20, 23, 24]),
    "palm_h_sh": ("손 높이(어깨 기준)", "(PalmMid.y − ShMid.y)/torso_len", [11, 12, 17, 18, 19, 20, 23, 24]),
    "knee_gap": ("두 무릎 간격", "|LKnee−RKnee|/|LHip−RHip|", [23, 24, 25, 26]),
    "knee_fwd": ("무릎 전방 이동", "(z_b(Knee) − z_b(Ankle))/|Knee−Ankle|", [23, 24, 25, 26, 27, 28]),
    "ankle": ("발목각", "∠(Knee, Ankle, Foot)", [25, 26, 27, 28, 31, 32]),
    "ankle_y": ("발목 높이(cm)", "Ankle.y (월드, 골반 원점 → 앱에선 상대 변화량만 의미)", [27, 28]),
    "foot_y": ("발 높이(cm)", "Foot.y", [31, 32]),
    "torso_roll": ("몸통 롤", "atan2(x_b(Neck), y_b(Neck)) deg", [11, 12, 23, 24]),
    "kneefoot": ("무릎-발 방향 불일치", "angle(horiz(Knee−Ankle), horiz(Foot−Ankle)); |horiz(Knee−Ankle)|<8cm 이면 NaN", [25, 26, 27, 28, 31, 32]),
    "ts_corr_knee_torso": ("무릎각-몸통피치 시계열 상관", "corr over frames(knee_mean, torso_pitch)", [11, 12, 23, 24, 25, 26, 27, 28]),
    "ts_corr_knee_hip": ("무릎각-고관절각 시계열 상관", "corr over frames(knee_mean, hip_mean)", [11, 12, 23, 24, 25, 26, 27, 28]),
}


ANTISYM_BASES = {"palm_lat", "head_yaw", "torso_roll", "shoulder_asym", "hand_h_asym", "knee_asym", "hip_asym", "elbow_asym"}


def mirror_safe(base: str, stat: str | None) -> tuple[bool, str | None]:
    """카메라를 사용자의 왼쪽/오른쪽 어느 쪽에 두어도 같은 값이 나오는 피처인가.
    - *_L / *_R 한쪽 지정 피처: 카메라 반대편이면 그 관절이 먼 쪽(가림)이 되어 정밀도가 달라짐 → mean/minside/maxside 변형 권장
    - 반대칭 피처(좌우 부호가 뒤집히는 것): std/range 만 미러 불변, mean/min/max 는 부호 반전
    """
    if base.endswith("_L") or base.endswith("_R"):
        return False, "한쪽(L/R) 지정 피처 — 카메라가 반대편이면 먼 쪽 관절이 되어 정밀도 변동. mean/minside/maxside 변형으로 대체 권장"
    if base in ANTISYM_BASES and stat in ("mean", "min", "max"):
        return False, "반대칭 피처의 mean/min/max — 카메라 좌/우에 따라 부호 반전. std/range 변형 또는 절댓값 처리 필요"
    return True, None


def base_of(feature: str) -> str:
    return feature.rsplit("__", 1)[0] if "__" in feature else feature


def family_of(base: str) -> str:
    """base → FEATURE_DOC 키 (접미사 _L/_R/_mean/_minside/_maxside/_asym 제거)."""
    if base in FEATURE_DOC:
        return base
    for suf in ("_L", "_R", "_mean", "_minside", "_maxside", "_asym"):
        if base.endswith(suf) and base[: -len(suf)] in FEATURE_DOC:
            return base[: -len(suf)]
    return base


def weak_note(base: str) -> str | None:
    for k, v in WEAK_FAMILIES.items():
        if base.startswith(k):
            return v
    return None


def main():
    RULES_DIR.mkdir(exist_ok=True)
    r = pd.read_csv(OUT / "expA_refit.csv")
    r["subtype"] = r["subtype"].fillna("")
    r["qc_flag"] = r["qc_flag"].fillna("")
    gt = pd.read_csv(OUT / "rules_v0.csv")
    gt["subtype"] = gt["subtype"].fillna("")
    gt_idx = gt.set_index(["exercise", "base_condition", "subtype"])

    single = r[r.view.isin(list("ABCDE")) & r.mp_refit_auc.notna()].copy()
    ctrl = r[r.view == "GT_SUBSET"].set_index(["exercise", "condition", "subtype"])["mp_refit_auc"]
    # 미러 불변 화이트리스트로 재적합한 결과 (experiment_a_refit.py --mirror-safe). 있으면 미러 안전 규칙을 우선 채택.
    mirror_path = OUT / "expA_refit_mirror.csv"
    rm = None
    if mirror_path.exists():
        rm = pd.read_csv(mirror_path)
        rm["subtype"] = rm["subtype"].fillna("")
        rm = rm[rm.view.isin(FRONT_VIEWS) & rm.mp_refit_auc.notna()].copy()
    keys = single[["exercise", "condition", "subtype"]].drop_duplicates()
    rules, rows_md = [], []
    n_mirror_primary = 0
    for k in keys.itertuples(index=False):
        g = single[(single.exercise == k.exercise) & (single.condition == k.condition) & (single.subtype == k.subtype)]
        gf = g[g.view.isin(FRONT_VIEWS)]
        if gf.empty:
            continue
        bu = gf.sort_values("mp_refit_auc", ascending=False).iloc[0]      # 비제약 전방뷰 최적
        ba = g.sort_values("mp_refit_auc", ascending=False).iloc[0]       # 전체 뷰 최적 (참고)
        bm = None
        if rm is not None:
            gm = rm[(rm.exercise == k.exercise) & (rm.condition == k.condition) & (rm.subtype == k.subtype)]
            if not gm.empty:
                bm = gm.sort_values("mp_refit_auc", ascending=False).iloc[0]
        # 채택 정책: 비제약 최적이 이미 미러 안전이면 그대로. 아니면 미러 안전 대안이 ship 수준(≥0.85)이거나
        # 비제약 대비 −0.03 이내(단 ≥0.75)면 미러 안전 규칙을 정본으로, 비제약은 alt 로 보존.
        u_stat = bu.mp_feature.rsplit("__", 1)[1] if "__" in bu.mp_feature else None
        u_safe = mirror_safe(base_of(bu.mp_feature), u_stat)[0]
        alt = None
        if u_safe or bm is None:
            bf = bu
        else:
            m_auc, u_auc = float(bm.mp_refit_auc), float(bu.mp_refit_auc)
            if m_auc >= 0.85 or (m_auc >= 0.75 and m_auc >= u_auc - 0.03):
                bf = bm
                alt = dict(kind="unconstrained", feature=bu.mp_feature, op=("<" if int(bu.mp_sign) > 0 else ">"),
                           threshold=round(float(bu.mp_threshold), 6), cv_auc=round(u_auc, 4), view=bu.view, mirror_safe=False)
                n_mirror_primary += 1
            else:
                bf = bu
                alt = dict(kind="mirror_safe", feature=bm.mp_feature, op=("<" if int(bm.mp_sign) > 0 else ">"),
                           threshold=round(float(bm.mp_threshold), 6), cv_auc=round(m_auc, 4), view=bm.view, mirror_safe=True)
        gt_sub = float(ctrl.get((k.exercise, k.condition, k.subtype), np.nan))
        gt_row = gt_idx.loc[(k.exercise, k.condition, k.subtype)] if (k.exercise, k.condition, k.subtype) in gt_idx.index else None
        if gt_row is not None and isinstance(gt_row, pd.DataFrame):
            gt_row = gt_row.iloc[0]
        gt_full = float(gt_row["wl_auc"]) if gt_row is not None else np.nan
        bad3d = (bf.qc_flag == "3D불량")
        auc = float(bf.mp_refit_auc)
        base = base_of(bf.mp_feature)
        stat = bf.mp_feature.rsplit("__", 1)[1] if "__" in bf.mp_feature else None
        cautions, reason = [], ""
        if bad3d:
            status, reason = "exclude", "3D GT 불량 종목(바닥/누운 자세) — 규칙 검증 불가"
        elif k.subtype in ("cervical", "unspecified"):
            status, reason = "exclude", ("경추(시선) 편차는 MP 로 약함" if k.subtype == "cervical" else "하위유형 미특정 라벨")
        elif not np.isnan(gt_sub) and gt_sub < 0.75:
            status, reason = "exclude", f"GT 3D 로도 관측 불가 (통제군 AUC {gt_sub:.2f})"
        elif auc < 0.75:
            status, reason = "exclude", f"MediaPipe 전이 실패 (전방뷰 최적 AUC {auc:.2f}, GT 통제군 {gt_sub:.2f})"
        elif auc < 0.85:
            status = "beta"
        else:
            status = "ship"
        if int(bf.n) < 40:
            cautions.append(f"표본 적음 (n={int(bf.n)}, AUC 표준오차 ≈ ±0.06)")
        wn = weak_note(base)
        if wn:
            cautions.append(wn)
        ms, ms_note = mirror_safe(base, stat)
        if not ms:
            cautions.append(ms_note)
        if status != "exclude" and ba.view not in FRONT_VIEWS and float(ba.mp_refit_auc) - auc > 0.05:
            cautions.append(f"후방 뷰({ba.view})에서 더 좋음 (AUC {float(ba.mp_refit_auc):.2f}) — 촬영 가이드와 상충")
        op = "<" if int(bf.mp_sign) > 0 else ">"
        rid = f"{k.exercise}|{k.condition}" + (f"[{k.subtype}]" if k.subtype else "")
        rules.append(dict(
            id=rid, exercise=k.exercise, condition=k.condition, subtype=k.subtype or None, status=status, reason=reason or None,
            feature=bf.mp_feature, base_feature=base, stat=stat, family=family_of(base), op=op, threshold=round(float(bf.mp_threshold), 6),
            violation_if=f"{bf.mp_feature} {op} {float(bf.mp_threshold):.4g}",
            view_best_front=bf.view, view_best_front_desc=VIEW_DESC[bf.view], cv_auc=round(auc, 4), cv_balacc=round(float(bf.mp_refit_balacc), 4),
            n=int(bf.n), n_performers=int(bf.n_performers),
            view_best_any=ba.view, cv_auc_best_any=round(float(ba.mp_refit_auc), 4),
            mirror_safe=ms,
            alt_rule=alt,
            gt_auc_control=(None if np.isnan(gt_sub) else round(gt_sub, 4)), gt_auc_full=(None if np.isnan(gt_full) else round(gt_full, 4)),
            cautions=cautions,
        ))
    rules.sort(key=lambda x: ({"ship": 0, "beta": 1, "exclude": 2}[x["status"]], x["exercise"], -x["cv_auc"]))

    used_bases = sorted({x["base_feature"] for x in rules if x["status"] != "exclude"})
    used_fams = []
    for b in used_bases:
        f = family_of(b)
        if f not in used_fams:
            used_fams.append(f)
    features_used = []
    for f in used_fams:
        d = FEATURE_DOC.get(f)
        features_used.append(dict(family=f, bases=[b for b in used_bases if family_of(b) == f],
                                  description=(d[0] if d else ""), formula=(d[1] if d else "(문서 없음)"), mp_landmarks=(d[2] if d else [])))

    doc = dict(
        version=("mp_v0.1" if rm is not None else "mp_v0"),
        revision_note=("mp_v0.1: 미러 불변(좌/우 카메라 위치 무관) 재적합 결과를 우선 채택, 비제약 규칙은 alt_rule 로 보존. "
                       f"미러 안전 규칙이 정본이 된 건수 {n_mirror_primary}" if rm is not None else None),
        generated=str(date.today()),
        source="AIHub 013 피트니스자세 (41종목, 수행자 113명) × MediaPipe pose_landmarker_full — 실험 A-2 (MP 피처 재적합, 수행자 GroupKFold, 종목당 ≤60클립)",
        coordinate_convention=dict(
            world="MediaPipe worldLandmarks (m, 골반 중점 원점, y 아래+) → ×100 cm, y·z 부호 반전 → y 위+",
            joint_map="Neck=(11+12)/2, LPalm=(17+19)/2, RPalm=(18+20)/2, LFoot=31, RFoot=32, Back/Waist 없음(spine_* 미사용)",
            body_frame="x_b = unit(horiz(LHip−RHip)) [사람의 왼쪽+], y_b = (0,1,0), z_b = unit(x_b × y_b) [전방+]; 원점 = HipMid",
            aggregation="프레임 피처 → 세트 창 통계(mean/min/max/std/range). AIHub 기준 창 = 여러 렙에 걸친 16프레임(≈2~4fps 샘플링) — 앱도 비슷한 창/샘플링으로 맞출 것",
            abstain="랜드마크 visibility/presence < 0.5 인 프레임은 해당 관절 NaN, 유효 프레임 < 8 이면 판정 유보",
        ),
        status_definition=dict(ship="전방 반구(B/C/D) 최적 뷰 MP 재적합 AUC ≥ 0.85", beta="0.75 ≤ AUC < 0.85 — 보정 후 사용", exclude="AUC < 0.75 또는 GT 통제군 < 0.75 또는 cervical/unspecified/3D불량"),
        counts={s: sum(1 for x in rules if x["status"] == s) for s in ("ship", "beta", "exclude")},
        mirror_safe_counts={s: sum(1 for x in rules if x["status"] == s and x["mirror_safe"]) for s in ("ship", "beta")},
        rules=rules,
        features_used=features_used,
    )
    (RULES_DIR / "rules_mp_v0.json").write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")

    # ---- MD
    L = [f"# rules_{doc['version']} — MediaPipe 포팅용 규칙 ({doc['generated']})\n",
         f"- 출처: {doc['source']}", f"- 등급: ship {doc['counts']['ship']} / beta {doc['counts']['beta']} / exclude {doc['counts']['exclude']} (미러 불변 규칙: ship {doc['mirror_safe_counts']['ship']}, beta {doc['mirror_safe_counts']['beta']})",
         *( [f"- {doc['revision_note']}"] if doc.get("revision_note") else [] ),
         "- 규칙 해석: `violation_if` 가 참이면 해당 조건 위반. 임계값은 MediaPipe full 모델·AIHub 스튜디오 분포 기준 — **자체 촬영 데이터로 재보정 필수**\n"]
    for status in ("ship", "beta", "exclude"):
        L += [f"## {status.upper()} ({doc['counts'][status]})\n", "| 종목 | 조건 | 하위유형 | 규칙(위반 if) | 뷰 | AUC | 균형정확도 | n | GT 통제군 | 주의/사유 |", "|---|---|---|---|---|---|---|---|---|---|"]
        for x in rules:
            if x["status"] != status:
                continue
            note = x["reason"] or "; ".join(x["cautions"])
            gt_ctrl = "" if x["gt_auc_control"] is None else f"{x['gt_auc_control']:.3f}"
            L.append(f"| {x['exercise']} | {x['condition']} | {x['subtype'] or ''} | `{x['violation_if']}` | {x['view_best_front']} | {x['cv_auc']:.3f} | {x['cv_balacc']:.3f} | {x['n']} | {gt_ctrl} | {note} |")
        L.append("")
    L += ["## 사용 피처 (ship/beta)\n", "| 패밀리 | 사용 변형 | 설명 | 계산식 | MP 랜드마크 |", "|---|---|---|---|---|"]
    for f in features_used:
        L.append(f"| {f['family']} | {', '.join(f['bases'])} | {f['description']} | {f['formula']} | {f['mp_landmarks']} |")
    (RULES_DIR / "rules_mp_v0.md").write_text("\n".join(L), encoding="utf-8")
    print(f"[done] rules {len(rules)} → ship {doc['counts']['ship']} / beta {doc['counts']['beta']} / exclude {doc['counts']['exclude']} | 피처 패밀리 {len(features_used)} | {RULES_DIR}")


if __name__ == "__main__":
    main()
