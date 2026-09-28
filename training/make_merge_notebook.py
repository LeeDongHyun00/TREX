# train_food_merge_colab.ipynb 를 만든다 — merge_food101.py 를 노트북 안에 그대로 심어(%%writefile) Colab 이 저장소 없이도 돈다.
# 노트북을 고칠 때는 이 파일을 고치고 다시 만든다: python make_merge_notebook.py
import json
from pathlib import Path

HERE = Path(__file__).parent
script = (HERE / 'merge_food101.py').read_text(encoding='utf-8')
uec_script = (HERE / 'merge_uec.py').read_text(encoding='utf-8')
import sys
UEC = '--uec' in sys.argv   # 둘째 판: Food-101 + UEC FOOD-256

def md(text): return {'cell_type': 'markdown', 'metadata': {}, 'source': text}
def code(text): return {'cell_type': 'code', 'metadata': {}, 'execution_count': None, 'outputs': [], 'source': text}

cells = [
md("""# T-REX 음식 모델 — AI Hub 한식 + Food-101 양식·일식 35종 머지 학습

**런타임 → 모두 실행** 한 번이면 끝까지 돈다. 결과는 Drive `MyDrive/trex/merge_out/` 에 남는다(자는 동안 창을 닫아도 된다 — Pro+ 백그라운드 실행).

- **왜 머지인가**: 학습된 모델에 새 음식만 이어서 학습하면 기존 음식을 잊는다(파국적 망각). 옛 데이터(AI Hub)와 새 데이터(Food-101)를 **한 데이터셋으로 합쳐** 다시 학습한다.
- **새 음식 35종**: 피자·햄버거·핫도그·라멘·타코야키·스테이크·타코·나초·시저샐러드·클럽샌드위치·그릴드치즈샌드위치·프렌치토스트·팬케이크·와플·도넛·아이스크림·치즈케이크·초콜릿케이크·컵케이크·마카롱·티라미수·추로스·어니언링·마늘빵·라자냐·리조또·맥앤치즈·피시앤칩스·팟타이·에그베네딕트·폭립·퀘사디아·오믈렛·에다마메·스프링롤. AI Hub 에 이미 있는 음식(초밥·만두·볶음밥·감자튀김 등)은 뺐다.
- **Food-101 에는 박스가 없다** → 위치 모델(YOLOE)로 음식 자리를, 기존 모델(best.pt)로 옆의 한식(감자튀김 등)을 가짜 라벨로 만든다.
- **확인하는 것**: 새 모델이 **기존 음식 성능을 잃지 않았는지**(이전 모델과 같은 AI Hub 검증 사진으로 비교)와 새 35종 성능.

### 필요한 것
- Drive `MyDrive/trex/dataset.tar`(AI Hub, 이전 학습에 쓴 것). 없으면 `dataset_342.tar` 를 찾는다.
- 있으면 좋은 것: `MyDrive/trex/runs/food/weights/best.pt`(이전 학습 결과) — 이어서 학습하고, 한식 가짜 라벨·망각 비교에 쓴다.
- 런타임: **G4 → A100 → L4** 중 잡히는 GPU + 고용량 RAM. **런타임 설정에서 '백그라운드 실행' 을 켠다.**

### 예상 시간(G4 기준, 대략)
압축 풀기 5분 · Food-101 받기 5~20분 · 가짜 라벨 10~15분 · 학습 1.5~3시간 · 변환 10분.
"""),
code("""# 1. 환경 + GPU 확인
!pip install -q -U ultralytics
import torch, ultralytics
ultralytics.checks()
assert torch.cuda.is_available(), 'GPU 가 없다 — 런타임 → 런타임 유형 변경에서 GPU 를 고른다'
p = torch.cuda.get_device_properties(0); print(f'GPU: {p.name}, VRAM {p.total_memory / 1e9:.0f} GB')
!free -g | head -2; df -h /content | tail -1
"""),
code(("""# 2. Drive 연결 + 경로
from google.colab import drive
drive.mount('/content/drive')
import os, glob
from pathlib import Path
TREX = Path('/content/drive/MyDrive/trex')
cands = [TREX / 'dataset.tar', TREX / 'dataset_342.tar']
AIHUB_TAR = next((c for c in cands if c.exists()), None)
assert AIHUB_TAR, f'AI Hub 데이터셋이 없다: {cands}'
BASE_PT = TREX / {BASE_REL!r}
BASE_PT = str(BASE_PT) if BASE_PT.exists() else None
RUNS_DIR = str(TREX / {RUNS_REL!r})          # 학습 가중치(끊겨도 이어서 학습)
OUT_DIR = TREX / {OUT_REL!r}; OUT_DIR.mkdir(parents=True, exist_ok=True)
print('AI Hub:', AIHUB_TAR, '| 이전 모델:', BASE_PT or '없음 — yolov8n 에서 시작, 한식 가짜 라벨·망각 비교 생략')
""").replace('{BASE_REL!r}', repr('runs_merge/merge/weights/best.pt' if UEC else 'runs/food/weights/best.pt'))
       .replace('{RUNS_REL!r}', repr('runs_merge2' if UEC else 'runs_merge'))
       .replace('{OUT_REL!r}', repr('merge_out2' if UEC else 'merge_out'))),
code("""# 3. AI Hub 데이터셋 풀기
import shutil
DATASET = Path('/content/dataset')
if not (DATASET / 'data.yaml').exists():
    !rm -rf /content/dataset && mkdir -p /content/dataset
    !tar -xf "{AIHUB_TAR}" -C /content/dataset
    if not (DATASET / 'data.yaml').exists():   # tar 안에 폴더가 한 겹 더 있으면 끌어올린다
        inner = [d for d in DATASET.iterdir() if d.is_dir() and (d / 'data.yaml').exists()]
        assert len(inner) == 1, os.listdir(DATASET)
        for item in inner[0].iterdir(): shutil.move(str(item), str(DATASET / item.name))
        inner[0].rmdir()
OLD_NAMES = [l.strip() for l in (DATASET / 'food_labels.txt').read_text(encoding='utf-8').splitlines() if l.strip()]
# 머지 전 AI Hub 검증 사진 목록(망각 비교용) — 머지 후에는 Food-101 사진이 섞인다
AIHUB_VAL = sorted(str(p) for p in (DATASET / 'images' / 'val').glob('*.jpg') if not p.name.startswith('f101_'))
print(f'AI Hub {len(OLD_NAMES)}종 · train {len(list((DATASET / "images" / "train").glob("*.jpg")))} · val {len(AIHUB_VAL)}')
"""),
code("""# 4. Food-101 받기 (ETH 서버, 5GB) — 끊기면 이어받는다
F101 = Path('/content/food-101')
if not (F101 / 'meta' / 'train.txt').exists():
    !for i in 1 2 3 4 5 6; do curl -sL -C - --retry 3 -o /content/food-101.tar.gz https://data.vision.ee.ethz.ch/cvl/food-101.tar.gz && break; sleep 10; done
    !ls -la /content/food-101.tar.gz && tar -xzf /content/food-101.tar.gz -C /content && rm /content/food-101.tar.gz
assert (F101 / 'meta' / 'train.txt').exists(), 'Food-101 을 받지 못했다'
print('Food-101 준비됨:', len(os.listdir(F101 / 'images')), '종')
"""),
code("%%writefile /content/merge_food101.py\n" + script),
code("""# 5. 머지 — 새 35종 × (학습 500 + 검증 100)장에 가짜 라벨을 달아 AI Hub 데이터셋에 합친다
import sys; sys.path.insert(0, '/content')
import importlib, merge_food101; importlib.reload(merge_food101)
if not (DATASET / 'merge_stats.json').exists():
    NAMES = merge_food101.merge(DATASET, F101, BASE_PT, per_train=500, per_val=100)
else:
    NAMES = [l.strip() for l in (DATASET / 'food_labels.txt').read_text(encoding='utf-8').splitlines() if l.strip()]
    print('이미 머지됨 —', len(NAMES), '종')
DATA_YAML = DATASET / 'data.yaml'
"""),
] + ([
code("""# 5-2. UEC FOOD-256 받기(Kaggle 사본 rkuo2000/uecfood256, 로그인 없이 받아진다) + 머지
!pip install -q kagglehub
import kagglehub
UEC_ROOT = Path(kagglehub.dataset_download('rkuo2000/uecfood256'))
UEC_DIR = next(p for p in [UEC_ROOT / 'UECFOOD256', UEC_ROOT] if (p / '1' / 'bb_info.txt').exists())
print('UEC:', UEC_DIR)
"""),
code("%%writefile /content/merge_uec.py\n" + uec_script),
code("""import merge_uec; importlib.reload(merge_uec)
if not (DATASET / 'uec_stats.json').exists():
    NAMES, uec_stats = merge_uec.merge(DATASET, UEC_DIR)
else:
    NAMES = [l.strip() for l in (DATASET / 'food_labels.txt').read_text(encoding='utf-8').splitlines() if l.strip()]
    print('이미 머지됨 —', len(NAMES), '종')
"""),
] if UEC else []) + [
code("""# 6. 학습 — 이전 모델이 있으면 그 가중치에서 시작한다(머리만 새 종 수로 다시 맞춘다). 끊기면 RESUME = True 로 다시 실행
from ultralytics import YOLO
RESUME = False
last = Path(RUNS_DIR) / 'merge' / 'weights' / 'last.pt'
if RESUME and last.exists():
    YOLO(str(last)).train(resume=True)
else:
    YOLO(BASE_PT or 'yolov8n.pt').train(
        data=str(DATA_YAML), epochs=80, patience=15, imgsz=640, batch=64, workers=8,
        cache='ram',   # 메모리가 모자라면 ultralytics 가 알아서 끈다
        cos_lr=True, seed=42, project=RUNS_DIR, name='merge', exist_ok=True,
    )
BEST = str(Path(RUNS_DIR) / 'merge' / 'weights' / 'best.pt')
print('best:', BEST)
"""),
code("""# 7. TFLite INT8 변환 + 앱 호환 검사 + Drive 저장 (평가보다 먼저 — 평가가 실패해도 모델은 남는다)
import tensorflow as tf, json, numpy as np
YOLO(BEST).export(format='tflite', int8=True, imgsz=640, data=str(DATA_YAML))
cands = sorted(Path(BEST).parent.rglob('*int8*.tflite'))
assert cands, 'INT8 tflite 를 찾지 못했다'
TFLITE = cands[0]
it = tf.lite.Interpreter(model_path=str(TFLITE)); it.allocate_tensors()
i, o = it.get_input_details()[0], it.get_output_details()[0]
assert i['dtype'] == np.float32 and o['dtype'] == np.float32, (i['dtype'], o['dtype'])
assert len(NAMES) + 4 in list(o['shape']), f"출력 {o['shape']} 가 {len(NAMES)}종과 안 맞는다"
shutil.copy(TFLITE, OUT_DIR / 'yolov8n_food.tflite')
(OUT_DIR / 'food_labels.txt').write_text('\\n'.join(NAMES) + '\\n', encoding='utf-8')
if (DATASET / 'nutrition.json').exists(): shutil.copy(DATASET / 'nutrition.json', OUT_DIR / 'nutrition_aihub.json')
(OUT_DIR / 'new_classes.json').write_text(json.dumps([n for n in NAMES if n not in OLD_NAMES], ensure_ascii=False), encoding='utf-8')
shutil.copy(DATASET / 'merge_stats.json', OUT_DIR / 'merge_stats.json')
if (DATASET / 'uec_stats.json').exists(): shutil.copy(DATASET / 'uec_stats.json', OUT_DIR / 'uec_stats.json')
print('완료 — Drive MyDrive/trex/merge_out/ 에 저장:', sorted(os.listdir(OUT_DIR)))
print(f"입력 {list(i['shape'])} · 출력 {list(o['shape'])} · {len(NAMES)}종")
"""),
code("""# 8. 망각 확인 — 같은 AI Hub 검증 사진으로 이전 모델 vs 새 모델, 그리고 새 35종 (실패해도 노트북을 멈추지 않는다)
import numpy as np, json, traceback
def val_on(model_path, names, images, tag):
    lst = Path(f'/content/{tag}.txt'); lst.write_text('\\n'.join(images) + '\\n')
    y = Path(f'/content/{tag}.yaml')
    y.write_text(f'path: {DATASET}\\ntrain: {lst}\\nval: {lst}\\nnames:\\n' + ''.join(f'  {i}: {n}\\n' for i, n in enumerate(names)), encoding='utf-8')
    m = YOLO(model_path).val(data=str(y), imgsz=640, batch=64, verbose=False, plots=False)
    return dict(zip([names[i] for i in m.box.ap_class_index], m.box.ap50.tolist())), float(m.box.map50)

try:
    summary = {}
    new_old, new_old_map = val_on(BEST, NAMES, AIHUB_VAL, 'val_aihub_new')
    summary['새 모델 · AI Hub 검증 mAP50'] = round(new_old_map, 3)
    if BASE_PT:
        base_names = list(YOLO(BASE_PT).names.values())
        base_imgs = AIHUB_VAL
        old_old, old_old_map = val_on(BASE_PT, base_names, base_imgs, 'val_aihub_old')
        common = [n for n in old_old if n in new_old]
        summary['이전 모델 · AI Hub 검증 mAP50'] = round(old_old_map, 3)
        summary['공통 클래스 평균 AP50 (이전 → 새)'] = f"{np.mean([old_old[n] for n in common]):.3f} → {np.mean([new_old[n] for n in common]):.3f} ({len(common)}종)"
        drops = sorted(((new_old[n] - old_old[n], n) for n in common))[:10]
        summary['가장 많이 떨어진 10종'] = [f'{n} {d:+.2f}' for d, n in drops]
    f101_val = sorted(str(p) for p in (DATASET / 'images' / 'val').glob('f101_*.jpg'))
    new_new, new_new_map = val_on(BEST, NAMES, f101_val, 'val_f101')
    summary['새 35종 · Food-101 검증 mAP50'] = round(new_new_map, 3)
    summary['새 35종 클래스별 AP50'] = {n: round(v, 2) for n, v in sorted(new_new.items(), key=lambda x: -x[1]) if n not in OLD_NAMES}
    uec_val = sorted(str(p) for p in (DATASET / 'images' / 'val').glob('uec_*.jpg'))
    if uec_val:   # UEC 판: 실제 식탁(여러 음식) 사진이 섞인 검증 — 기존·새 음식 모두
        uec_ap, uec_map = val_on(BEST, NAMES, uec_val, 'val_uec')
        summary['UEC 검증(식탁 사진 포함) mAP50'] = round(uec_map, 3)
        summary['UEC 검증 클래스별 AP50'] = {n: round(v, 2) for n, v in sorted(uec_ap.items(), key=lambda x: -x[1])}
    print(json.dumps(summary, ensure_ascii=False, indent=1))
    (OUT_DIR / 'eval_summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding='utf-8')
except Exception:
    traceback.print_exc(); print('평가 실패 — 모델은 7번 셀에서 이미 Drive 에 저장됐다')
"""),
md("""### 아침에 할 일
- `MyDrive/trex/merge_out/eval_summary.json` 으로 **기존 음식 성능이 유지됐는지**(공통 클래스 AP50 이전 → 새)와 새 35종 성능을 본다.
- 괜찮으면 `yolov8n_food.tflite`·`food_labels.txt` 를 앱 `assets/models/` 에 넣고, 새 35종 영양값을 `TrexData.kt` 에 추가한다(Food-101 에는 영양값이 없다 — 추정치로 표시).
- 세션이 끊겼으면: 1~3번 셀 → 6번 셀에서 `RESUME = True` → 7·8번 셀. (4·5번은 이미 했으면 건너뛴다.)
"""),
]
nb = {'cells': cells, 'metadata': {'accelerator': 'GPU', 'colab': {'gpuType': 'L4', 'machine_shape': 'hm'},
      'kernelspec': {'display_name': 'Python 3', 'name': 'python3'}}, 'nbformat': 4, 'nbformat_minor': 0}
(HERE / ('train_food_merge2_colab.ipynb' if UEC else 'train_food_merge_colab.ipynb')).write_text(json.dumps(nb, ensure_ascii=False, indent=1), encoding='utf-8')
print('written', len(cells), 'cells')
