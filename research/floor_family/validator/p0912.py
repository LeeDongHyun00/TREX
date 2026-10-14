import json,numpy as np
for line in open(r"C:/Users/hp276/Desktop/trex/data/phone/20260925-main2/sets-20260912.jsonl",encoding="utf-8"):
    s=json.loads(line)
    if s.get("exercise")!="플랭크": continue
    fr=s["frames"]; nf=sum(1 for x in fr if x.get("features"))
    hip=np.median([max(x["vis"][23],x["vis"][24]) for x in fr]); ank=np.median([max(x["vis"][27],x["vis"][28]) for x in fr])
    print(s["set_id"], len(fr), "feat",nf, "hip vis med",round(hip,2),"ankle",round(ank,2),"front",s.get("front_camera"),"tilt",round(s.get("tilt_deg") or -1,1), [r["verdict"] for r in s["results"]])
