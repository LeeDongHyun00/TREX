#!/usr/bin/env bash
# 마지막 묶음(12_13_14_15_16) 변환이 끝나면 finalize + dataset.tar 까지 이어서 한다.
# 파이프라인 자체는 finalize 를 부르지 않는다("다음: …" 로그만 남긴다).
#
# 사용: nohup bash tools/after_last_bundle.sh >> after.log 2>&1 &
#   터미널을 닫아도 살아 있어야 한다. 에이전트 도구의 백그라운드는 셸이 끝나면 같이 죽고,
#   bash -lc 로 감싸 띄우면 로그인 셸이 프로필을 읽느라 본체가 안 뜨는 경우가 있었다.
#   띄운 뒤 .after_last_bundle.lock 이 생겼는지로 본체가 돌기 시작했는지 확인할 것.
set -u
ROOT="C:/Workspace/TREX/aihub74_raw"
DIR="$ROOT/images_12_13_14_15_16"
LOG="$ROOT/pipeline.log"
say() { echo "$(date '+%m-%d %H:%M:%S') [after] $*" | tee -a "$LOG"; }

# 한 번만 돌게 잠근다. 두 벌이 동시에 finalize 하면 한쪽이 dataset_final 을 지우는 사이
# 다른 쪽이 쓰다가 EPERM 으로 깨진다(2026-09-23 실제로 겪음). mkdir 은 원자적이다.
LOCK="$ROOT/.after_last_bundle.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
  echo "[after] 이미 실행 중이다($LOCK). 빠진다." >&2
  exit 0
fi
trap 'rmdir "$LOCK" 2>/dev/null' EXIT

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
# -C 로 들어가서 . 을 만다. `tar -cf x.tar dataset_final` 로 하면 tar 안에 폴더가 한 겹 더 생겨
# Colab 노트북이 기대하는 /content/dataset/data.yaml 대신 .../dataset_final/data.yaml 이 된다
# (2026-09-23 실제로 이렇게 만들어 학습 노트북 4번 셀이 assert 로 죽었다).
if (cd "$ROOT" && tar -cf dataset.tar -C dataset_final .); then
  say "완료: dataset.tar $(du -h "$ROOT/dataset.tar" | cut -f1) · 클래스 $(wc -l < "$ROOT/dataset_final/food_labels.txt")종"
  say "다음: Drive MyDrive/trex/dataset.tar 로 올리고 training/train_food_yolov8_colab.ipynb 실행"
else
  say "tar 생성 실패"
  exit 1
fi
