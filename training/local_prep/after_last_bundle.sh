#!/usr/bin/env bash
# 마지막 묶음(12_13_14_15_16) 변환이 끝나면 finalize + dataset.tar 까지 이어서 한다.
# 파이프라인 자체는 finalize 를 부르지 않는다("다음: …" 로그만 남긴다).
set -u
ROOT="C:/Workspace/TREX/aihub74_raw"
DIR="$ROOT/images_12_13_14_15_16"
LOG="$ROOT/pipeline.log"
say() { echo "$(date '+%m-%d %H:%M:%S') [after] $*" | tee -a "$LOG"; }

# 1) 변환 완료(.done) 또는 실패를 기다린다. 둘 중 뭐가 와도 빠져나온다.
while [ ! -f "$DIR/.done" ]; do
  if grep -q "\[12_13_14_15_16\] 변환 실패\|\[12_13_14_15_16\] 다운로드 실패\|\[12_13_14_15_16\] 병합 실패\|\[12_13_14_15_16\] zip 없음" "$LOG"; then
    say "파이프라인이 실패로 끝났다. finalize 하지 않는다 — pipeline.log 를 볼 것."
    exit 1
  fi
  sleep 20
done

say "변환 완료 확인. finalize 시작"
before_train=$(ls "$ROOT/dataset_640/images/train" 2>/dev/null | wc -l)
say "dataset_640 누적: train $before_train"

if ! node "$ROOT/tools/prep_dataset.js" finalize >> "$LOG" 2>&1; then
  say "finalize 실패 — dataset.tar 만들지 않는다."
  exit 1
fi

# 2) dataset.tar 재생성. 기존 342클래스 tar 는 되돌릴 수 없으니 이름을 바꿔 남긴다.
if [ -f "$ROOT/dataset.tar" ]; then
  mv "$ROOT/dataset.tar" "$ROOT/dataset_342.tar"
  say "기존 tar 를 dataset_342.tar 로 보관(342클래스 재현용)"
fi
say "dataset.tar 생성 중"
if (cd "$ROOT" && tar -cf dataset.tar dataset_final); then
  say "완료: dataset.tar $(du -h "$ROOT/dataset.tar" | cut -f1) · 클래스 $(wc -l < "$ROOT/dataset_final/food_labels.txt")종"
  say "다음: Drive MyDrive/trex/dataset.tar 로 올리고 training/train_food_yolov8_colab.ipynb 실행"
else
  say "tar 생성 실패"
  exit 1
fi
