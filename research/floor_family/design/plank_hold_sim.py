# -*- coding: utf-8 -*-
# 09-23 폰 플랭크(좌표 없음)에서 제안 PlankHoldGate 를 피처만으로 근사: 가까운 쪽 = 왼쪽(가시성 50/7 프레임)
import json, numpy as np
P=r"C:/Users/hp276/Desktop/trex/data/phone/20260925-main/sets-20260923.jsonl"
for line in open(P,encoding="utf-8"):
    s=json.loads(line)
    if s.get("exercise")!="플랭크": continue
    fr=s.get("frames") or []
    t0=fr[0]["t_ms"] if fr else 0
    print("set",s.get("set_id"),"frames",len(fr))
    state="WAIT"; since=None; cand=None; held=0; stops={}; last=None; log=[]
    for f in fr:
        t=f["t_ms"]-t0; F=f.get("features") or {}
        hip=F.get("hip_dev_ankle_L"); knee=F.get("knee_ang_L"); hipang=F.get("hip_ang_L")
        if hip is None or knee is None: reason="out_of_view"
        elif knee<145: reason="knees_down"
        elif hip< -0.10: reason="hips_down"
        elif hip> 0.25: reason="pike"
        else: reason=None
        dt=0 if last is None else min(t-last,750); last=t
        # 1 s 지속 규칙: 상태 변경 후보가 1 s 이어져야 전환
        target="HOLD" if reason is None else reason
        cur="HOLD" if state=="HOLD" else state
        if target!=cur:
            if cand!=target: cand=target; since=t
            if t-since>=1000:
                log.append((round(since/1000,1),cur,"->",target)); state=target; cand=None
        else: cand=None
        if state=="HOLD": held+=dt
        else: stops[state]=stops.get(state,0)+dt
    print(" 전환:",log); print(" 인정 유지 %.1f s · 멈춤 %s · 세트 길이 %.1f s"%(held/1000,{k:round(v/1000,1) for k,v in stops.items()},(fr[-1]['t_ms']-t0)/1000 if fr else 0))
