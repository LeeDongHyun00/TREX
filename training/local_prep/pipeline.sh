#!/bin/bash
# AI Hub 74번 Validation 원천 묶음을 순서대로: 다운로드 → tar/zip 해제 → 640px 변환(prep_dataset.js add) → 원본 삭제.
# 며칠 걸리므로 세션과 무관한 별도 프로세스로 띄운다. 진행은 ../pipeline.log 에 남는다.
# 폴더 images_<이름>/ 에 이미 *.zip 이 있으면(예: Innorix 로 직접 받아 둔 경우) 다운로드를 건너뛴다.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEY="$1"
LOG="$ROOT/pipeline.log"
cd "$ROOT"

# 이름:filekey — 앱 식단 기록에 자주 나올 순서. 11(무침/나물)은 속도 측정용으로 먼저 받기 시작했다.
QUEUE="11:44885 06_07_08:44883 04:44882 01:44880 09_10:44884 02_03_05:44881 12_13_14_15_16:44886"

log() { echo "$(date '+%m-%d %H:%M:%S') $*" | tee -a "$LOG"; }

for item in $QUEUE; do
  name="${item%%:*}"; key="${item##*:}"
  dir="$ROOT/images_$name"
  mkdir -p "$dir"
  if [ -f "$dir/.done" ]; then log "[$name] 이미 처리됨 — 건너뜀"; continue; fi

  # 1) 다운로드 (이미 zip 이 있으면 생략, 다른 프로세스가 받는 중이면 끝날 때까지 대기)
  if ! ls "$dir"/*.zip >/dev/null 2>&1 && ! ls "$dir"/122.*/**/*.zip >/dev/null 2>&1; then
    if [ -f "$dir/download.log" ] && ! grep -q "exit=" "$dir/download.log"; then
      log "[$name] 다른 프로세스가 받는 중 — 완료 대기"
      while ! grep -q "exit=" "$dir/download.log"; do sleep 60; done
    elif [ ! -f "$dir/download.tar" ]; then
      log "[$name] 다운로드 시작 (filekey $key)"
      (cd "$dir" && bash "$ROOT/aihubshell" -mode d -datasetkey 74 -filekey "$key" -aihubapikey "$KEY" > download.log 2>&1; echo "exit=$?" >> download.log)
      log "[$name] 다운로드 끝: $(tail -1 "$dir/download.log")"
    fi
    if [ -f "$dir/download.tar" ]; then
      log "[$name] tar 해제 ($(du -h "$dir/download.tar" | cut -f1))"
      (cd "$dir" && tar -xf download.tar) && rm -f "$dir/download.tar"
    fi
  fi

  # 2) zip 해제
  zip="$(find "$dir" -iname '*.zip' | head -1)"
  if [ -z "$zip" ]; then log "[$name] zip 을 찾지 못함 — 건너뜀"; continue; fi
  if [ ! -d "$dir/unz" ]; then
    log "[$name] zip 해제 시작 ($(du -h "$zip" | cut -f1))"
    mkdir -p "$dir/unz" && unzip -qo "$zip" -d "$dir/unz" 2>>"$LOG"
    log "[$name] zip 해제 끝: $(find "$dir/unz" -type f | wc -l)개 파일"
  fi

  # 3) 640px 변환 (이미 변환된 파일은 건너뜀)
  log "[$name] 변환 시작"
  node "$ROOT/tools/prep_dataset.js" add --images "$dir/unz" --classes all >> "$LOG" 2>&1
  rc=$?
  if [ $rc -ne 0 ]; then log "[$name] 변환 실패(rc=$rc) — 원본 보존, 다음 묶음으로"; continue; fi

  # 4) 원본 삭제 (디스크 회수)
  rm -rf "$dir/unz" "$zip" "$dir"/122.*
  touch "$dir/.done"
  log "[$name] 완료. 디스크 여유: $(df -h "$ROOT" | tail -1 | awk '{print $4}')"
done
log "큐 전체 종료. 다음: node tools/prep_dataset.js finalize"
