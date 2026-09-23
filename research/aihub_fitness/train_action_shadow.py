"""AIHub의 sparse MP 관절로 운동 종류 TCN을 학습한다. 제품 계수/교정에는 shadow만 허용.

원본/개인 로그는 쓰지 않으며 raw tar를 해제하지 않는다. 같은 수행자의 모든 clip/view는
하나의 분할에 속한다. 운동 외 생활 행동·정오·phase·피로 모델을 학습했다고 주장하지 않는다.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
os.environ.setdefault("TF_NUM_INTRAOP_THREADS", "4")
os.environ.setdefault("TF_NUM_INTEROP_THREADS", "2")
import numpy as np
import pandas as pd
from scipy.optimize import minimize_scalar
from scipy.special import logsumexp, softmax
from sklearn.metrics import balanced_accuracy_score, confusion_matrix
from sklearn.model_selection import GroupShuffleSplit
from action_input import SCHEMA, FRAMES, FEATURES, encode, windows

ROOT = Path(__file__).resolve().parents[2]
OOD = {"버피 테스트", "페이스 풀", "바이시클 크런치"}


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def catalog():
    source = (ROOT / "app/src/main/java/com/example/trex_kotlin/posture/ExerciseProfiles.kt").read_text("utf-8")
    names = re.findall(r'p\("[^"]+","([^"]+)"', source)
    assert len(names) == len(set(names)) == 26
    return names + ["__other_exercise__"]


def prepare(root, out, labels):
    sample = pd.read_parquet(root / "mp/sample.parquet")
    assert sample.performer.notna().all()
    # 3가지 미지원 운동은 훈련/보정에서 제외하고 잠금 OOD 시험에만 사용한다.
    eligible = sample[~sample.exercise.isin(OOD)]
    groups = np.array(sorted(eligible.performer.unique()))
    train, rest = next(GroupShuffleSplit(n_splits=1, test_size=.4, random_state=20260923).split(groups, groups=groups))
    cal, test = next(GroupShuffleSplit(n_splits=1, test_size=.5, random_state=42).split(groups[rest], groups=groups[rest]))
    split = {**dict.fromkeys(groups[train], "train"), **dict.fromkeys(groups[rest][cal], "cal"),
             **dict.fromkeys(groups[rest][test], "test")}
    rows = []
    files = sorted((root / "mp").glob("landmarks_*.parquet"))
    inputs = {f.name: sha(f) for f in files}
    for f in files:
        lm = pd.read_parquet(f)
        rows.append(sample.merge(lm, on="img_key", how="inner", validate="one_to_one"))
    data = pd.concat(rows, ignore_index=True).drop_duplicates("img_key")
    arrays = {k: [] for k in ("train", "cal", "test", "ood")}
    ys = {k: [] for k in arrays}
    meta = {k: [] for k in arrays}
    example = None
    for (clip, view), block in data.groupby(["clip_id", "view_letter"], sort=True):
        block = block.sort_values("frame_idx")
        exercise, person = block.exercise.iloc[0], block.performer.iloc[0]
        part = split.get(person)
        if exercise in OOD:
            if part != "test":
                continue
            part = "ood"
        if part is None:
            continue
        seq = np.zeros((FRAMES, FEATURES), np.float32)
        for _, row in block.iterrows():
            # sample의 frame_idx는 0-based. 존재하지 않는 프레임은 마스크한 채 둔다.
            idx = int(row.frame_idx)
            if not 0 <= idx < FRAMES or not row.detected:
                continue
            xy = np.array([[row[f"l{j}_x"] / row.w, row[f"l{j}_y"] / row.h] for j in range(33)], np.float32)
            vis = np.array([row[f"l{j}_v"] for j in range(33)], np.float32)
            pres = np.array([row[f"l{j}_p"] for j in range(33)], np.float32)
            seq[idx] = encode(xy, vis, pres, row.w, row.h)
            if example is None and part == "train" and np.any(seq[idx]):
                example = dict(xy=xy.ravel().tolist(), visibility=vis.tolist(), presence=pres.tolist(),
                               width=int(row.w), height=int(row.h), encoded=seq[idx].tolist())
        y = labels.index(exercise) if exercise in labels else 26
        for window in windows(seq):
            arrays[part].append(window); ys[part].append(y)
            meta[part].append([str(person), str(clip), str(view), str(exercise)])
    for part in arrays:
        assert arrays[part], f"No {part} data"
        np.savez_compressed(out / f"{part}.npz", x=np.asarray(arrays[part]), y=np.asarray(ys[part], np.int32))
    assert set(ys["train"]) == set(range(len(labels))), "Missing training class"
    provenance = {"seed": 20260923, "split_subjects": {p: sorted(k for k, v in split.items() if v == p) for p in ("train", "cal", "test")},
                  "input_hashes": inputs, "sample_sha256": sha(root / "mp/sample.parquet"),
                  "windows": {p: len(arrays[p]) for p in arrays}, "ood_exercises": sorted(OOD),
                  "rows": meta, "personal_logs_used": False}
    (out / "provenance.json").write_text(json.dumps(provenance, ensure_ascii=False), "utf-8")
    (out / "input_fixture.json").write_text(json.dumps(example), "utf-8")
    print("prepared", provenance["windows"], flush=True)


def train(out, assets, labels, epochs):
    import tensorflow as tf
    tf.keras.utils.set_random_seed(20260923)
    packs = {p: np.load(out / f"{p}.npz") for p in ("train", "cal", "test", "ood")}
    inp = tf.keras.Input(batch_shape=(1, FRAMES, FEATURES))
    x = tf.keras.layers.Dense(48, activation="relu")(inp)
    for dilation in (1, 2, 4):
        y = tf.keras.layers.Conv1D(48, 3, padding="causal", dilation_rate=dilation, activation="relu")(x)
        x = tf.keras.layers.Add()([x, y])
    # 마지막 시점의 과거 수용영역만 사용한다.
    logits = tf.keras.layers.Dense(len(labels))(x[:, -1, :])
    model = tf.keras.Model(inp, logits)
    model.compile(optimizer=tf.keras.optimizers.Adam(.001),
                  loss=tf.keras.losses.SparseCategoricalCrossentropy(from_logits=True), metrics=["accuracy"])
    y = packs["train"]["y"]
    counts = np.bincount(y, minlength=len(labels))
    weights = {i: float(len(y) / len(labels) / c) for i, c in enumerate(counts)}
    model.fit(packs["train"]["x"], y, batch_size=128, epochs=epochs, verbose=2,
              validation_data=(packs["cal"]["x"], packs["cal"]["y"]), class_weight=weights,
              callbacks=[tf.keras.callbacks.EarlyStopping(monitor="val_loss", patience=5, restore_best_weights=True)])
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    rng = np.random.default_rng(73)
    rep = rng.choice(len(y), size=min(400, len(y)), replace=False)
    converter.representative_dataset = lambda: ([packs["train"]["x"][i:i+1]] for i in rep)
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    # 외부 float I/O, 내부 INT8. Android 표준 CPU Interpreter와 호환되는 기본 연산만 허용.
    content = converter.convert()
    assets.mkdir(parents=True, exist_ok=True)
    model_path = assets / "exercise_context.tflite"
    model_path.write_bytes(content)
    interpreter = tf.lite.Interpreter(model_content=content, num_threads=1)
    interpreter.allocate_tensors()
    ii = interpreter.get_input_details()[0]["index"]
    oi = interpreter.get_output_details()[0]["index"]
    def predict(xs):
        result = []
        for value in xs:
            interpreter.set_tensor(ii, value[None])
            interpreter.invoke()
            result.append(interpreter.get_tensor(oi)[0])
        return np.array(result)
    cal_logits = predict(packs["cal"]["x"])
    cy = packs["cal"]["y"]
    def nll(t):
        z = cal_logits / t
        return float(np.mean(logsumexp(z, axis=1) - z[np.arange(len(z)), cy]))
    temperature = float(minimize_scalar(nll, bounds=(.5, 8), method="bounded").x)
    cal_prob = softmax(cal_logits / temperature, axis=1)
    threshold = float(np.quantile(cal_prob.max(axis=1), .10))
    energy_threshold = float(np.quantile(-logsumexp(cal_logits / temperature, axis=1), .95))
    report = {"scope": "AIHub sparse-image exercise classification; not live action/form/count accuracy",
              "parameters": model.count_params(), "model_bytes": len(content), "temperature": temperature,
              "mode": "shadow", "untrained": ["daily_activity", "phase", "form_error", "fatigue", "rep_count"]}
    for part in ("cal", "test", "ood"):
        z = cal_logits if part == "cal" else predict(packs[part]["x"])
        prob = softmax(z / temperature, axis=1); pred = prob.argmax(axis=1)
        accepted = (prob.max(axis=1) >= threshold) & (-logsumexp(z / temperature, axis=1) <= energy_threshold)
        truth = packs[part]["y"]
        report[part] = {"windows": len(truth), "accuracy": float(np.mean(pred == truth)),
                        "balanced_accuracy": float(balanced_accuracy_score(truth, pred)) if part != "ood" else None,
                        "accepted_fraction": float(accepted.mean()),
                        "confusion": confusion_matrix(truth, pred, labels=range(len(labels))).tolist(),
                        "per_class": {name: {"n": int((truth == i).sum()), "recall": float(np.mean(pred[truth == i] == i)) if (truth == i).any() else None}
                                      for i, name in enumerate(labels)}}
        if part == "test":
            float_logits = model.predict(packs[part]["x"][:128], batch_size=32, verbose=0)
            report["quantized_top1_agreement"] = float(np.mean(float_logits.argmax(1) == pred[:128]))
            fixture = {"input": packs[part]["x"][0].ravel().tolist(), "logits": z[0].tolist()}
            (out / "model_fixture.json").write_text(json.dumps(fixture), "utf-8")
    manifest = {"schema": "trex.action.bundle/1", "bundle_id": "aihub26-context-shadow-1", "mode": "shadow",
                "input_schema": SCHEMA, "frames": FRAMES, "features": FEATURES, "labels": labels,
                "model_file": model_path.name, "sha256": sha(model_path), "temperature": temperature,
                "confidence_threshold": threshold, "energy_threshold": energy_threshold,
                "pose_model": "full", "pose_sha256": sha(ROOT / "app/src/main/assets/posture/pose_landmarker_full.task"),
                "provenance_sha256": sha(out / "provenance.json"),
                "tasks": ["exercise_clip_classification"], "can_affect_count": False, "can_speak": False}
    (assets / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", "utf-8")
    (out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), "utf-8")
    print(json.dumps({k:v for k,v in report.items() if k not in ("cal","test","ood")}, ensure_ascii=False), flush=True)
    for p in ("test", "ood"):
        print(p, {k:v for k,v in report[p].items() if k not in ("confusion", "per_class")}, flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=Path(__file__).parent / "outputs/action_model")
    parser.add_argument("--assets", type=Path, default=ROOT / "app/src/main/assets/posture/action")
    parser.add_argument("--epochs", type=int, default=25)
    parser.add_argument("--prepared", action="store_true")
    args = parser.parse_args(); args.out.mkdir(parents=True, exist_ok=True)
    labels = catalog()
    if not args.prepared:
        prepare(args.source, args.out, labels)
    train(args.out, args.assets, labels, args.epochs)


if __name__ == "__main__":
    main()
