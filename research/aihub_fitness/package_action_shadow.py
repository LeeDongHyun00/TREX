"""학습 결과에 배포 hash와 합성 파리티 자료를 추가한다. 사람의 원관절을 저장소에 복사하지 않음."""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import os
os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
import numpy as np
from scipy.special import logsumexp, softmax
import tensorflow as tf
from action_input import encode

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "research/aihub_fitness/outputs/action_model"
ASSETS = ROOT / "app/src/main/assets/posture/action"


def main():
    manifest = json.loads((ASSETS / "manifest.json").read_text("utf-8"))
    manifest["pose_sha256"] = hashlib.sha256((ASSETS.parent / "pose_landmarker_full.task").read_bytes()).hexdigest()
    (ASSETS / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+"\n", "utf-8")
    # 단순 수학식으로 만든 관절. 특정 사람/AIHub 프레임이 아니다.
    xy = np.array([[.5 + .12*np.sin(i), .2 + .02*i] for i in range(33)], np.float32)
    xy[11]=(.42,.32); xy[12]=(.58,.32); xy[23]=(.45,.6); xy[24]=(.55,.6)
    v = np.ones(33, np.float32); p = np.ones(33,np.float32); v[0]=.2; p[32]=.3
    encoded=encode(xy,v,p,640,480)
    def line(a): return " ".join(str(float(x)) for x in np.asarray(a).ravel())
    fixture = ROOT / "app/src/test/resources/action_input_fixture.txt"
    fixture.write_text("640 480\n" + "\n".join(map(line,(xy,v,p,encoded)))+"\n", "utf-8")
    window=np.zeros((1,16,132),np.float32)
    for i in range(16):
        varied=xy.copy(); varied[15,1] += np.float32(.03*np.sin(i/3))
        window[0,i]=encode(varied,v,p,640,480)
    runner=tf.lite.Interpreter(model_path=str(ASSETS / manifest["model_file"]),num_threads=1)
    runner.allocate_tensors()
    idx=runner.get_input_details()[0]["index"]; oi=runner.get_output_details()[0]["index"]
    runner.set_tensor(idx,window); runner.invoke(); golden=runner.get_tensor(oi)[0]
    target=ROOT / "app/src/androidTest/assets/action_model_fixture.txt"
    target.parent.mkdir(parents=True,exist_ok=True)
    (target.parent / "action_packet_fixture.txt").write_text(fixture.read_text("utf-8"), "utf-8")
    target.write_text(manifest["sha256"]+"\n"+line(window)+"\n"+line(golden)+"\n", "utf-8")
    report=json.loads((OUT / "report.json").read_text("utf-8"))
    data=np.load(OUT / "ood.npz")
    accepted=[]; target_accepted=[]
    for x in data["x"]:
        runner.set_tensor(idx,x[None]); runner.invoke()
        z=runner.get_tensor(oi)[0]/manifest["temperature"]
        prob=softmax(z)
        good=bool(prob.max()>=manifest["confidence_threshold"] and -logsumexp(z)<=manifest["energy_threshold"])
        accepted.append(good); target_accepted.append(good and int(prob.argmax())<26)
    report["ood"]["accepted_as_service_exercise_fraction"]=float(np.mean(target_accepted))
    provenance=json.loads((OUT / "provenance.json").read_text("utf-8"))
    report["subjects"]={k:len(v) for k,v in provenance["split_subjects"].items()}
    report["limitations"]=["AIHub has been used in earlier feature/rule research; this is an internal model comparison",
                           "Sparse frame ordering has no measured frame durations; live context accuracy is untested",
                           "Unknown rejection failed the proposed release gate; model is shadow-only",
                           "No personal device log was used in training or calibration"]
    report["runtime_versions"]={"tensorflow":tf.__version__,"numpy":np.__version__}
    report["model_sha256"]=manifest["sha256"]
    target_report=ROOT / "research/aihub_fitness/ACTION_SHADOW_RESULTS.json"
    target_report.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n", "utf-8")
    print("synthetic fixtures and aggregate report exported",report["subjects"],report["ood"]["accepted_as_service_exercise_fraction"])


if __name__=="__main__": main()
