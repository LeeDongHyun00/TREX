#!/bin/bash
# AI Hub 74번 Validation 원천을 묶음 단위로: 다운로드 → tar/zip 해제 → 640px 변환 → 원본 삭제.
# 서버가 이어받기를 지원하지 않아 dl.sh 가 멈춤을 감지해 처음부터 다시 받는다(묶음당 5~10시간).
# 사용: bash pipeline.sh <API키> [묶음이름 …]   (묶음을 안 주면 아래 기본 우선순위 전체)
# 진행: pipeline.log / 각 폴더의 dl.log
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEY="$1"; shift

# 이름:filekey:크기GB — 앱 식단 기록에서 자주 찍을 음식 순서.
# 04 국/탕/찌개 · 06_07_08 구이/전/볶음 · 01 밥 · 09_10 조림/튀김(치킨) · 02_03_05 면·만두/죽/찜 · 12~16 김치/젓갈/회/떡/한과 · 11 무침/나물
ALL="04:44882:31 06_07_08:44883:30 01:44880:28 09_10:44884:18 02_03_05:44881:30 12_13_14_15_16:44886:25 11:44885:15"
QUEUE="$ALL"
if [ "$#" -gt 0 ]; then
  QUEUE=""
  for want in "$@"; do for item in $ALL; do [ "${item%%:*}" = "$want" ] && QUEUE="$QUEUE $item"; done; done
fi

log() { echo "$(date '+%m-%d %H:%M:%S') $*" | tee -a "$ROOT/pipeline.log"; }

for item in $QUEUE; do
  name="${item%%:*}"; rest="${item#*:}"; key="${rest%%:*}"; gb="${rest##*:}"
  dir="$ROOT/images_$name"
  mkdir -p "$dir"
  [ -f "$dir/.done" ] && { log "[$name] 이미 처리됨 — 건너뜀"; continue; }

  # 1) 다운로드 (zip 이 이미 있으면 생략 — Innorix 로 직접 받아 넣어둔 경우)
  if ! find "$dir" -iname '*.zip' | grep -q . ; then
    if [ ! -s "$dir/download.tar" ] || [ "$(stat -c %s "$dir/download.tar")" -lt 1000000000 ]; then
      log "[$name] 다운로드 시작 (${gb}GB, 약 $((gb * 1024 / 52))분 예상)"
      bash "$ROOT/tools/dl.sh" "$key" "$dir" "$KEY" || { log "[$name] 다운로드 실패 — 다음 묶음으로"; continue; }
      log "[$name] 다운로드 완료: $(tail -1 "$dir/dl.log")"
    fi
    if [ -f "$dir/download.tar" ]; then
      log "[$name] tar 해제"
      (cd "$dir" && tar -xf download.tar) && rm -f "$dir/download.tar"
    fi
  fi

  # 2) zip 해제
  zip="$(find "$dir" -iname '*.zip' | head -1)"
  [ -z "$zip" ] && { log "[$name] zip 없음 — 건너뜀"; continue; }
  if [ ! -d "$dir/unz" ]; then
    log "[$name] zip 해제 ($(du -h "$zip" | cut -f1))"
    mkdir -p "$dir/unz" && unzip -qo "$zip" -d "$dir/unz" 2>>"$ROOT/pipeline.log"
    log "[$name] 해제 완료: $(find "$dir/unz" -type f | wc -l)개"
  fi

  # 3) 640px 변환 (이미 변환된 파일은 건너뜀)
  log "[$name] 변환 시작"
  if node "$ROOT/tools/prep_dataset.js" add --images "$dir/unz" --classes all >> "$ROOT/pipeline.log" 2>&1; then
    rm -rf "$dir/unz" "$zip" "$dir"/122.*
    touch "$dir/.done"
    log "[$name] 완료. 디스크 여유: $(df -h "$ROOT" | tail -1 | awk '{print $4}')"
  else
    log "[$name] 변환 실패 — 원본 보존"
  fi
done
log "큐 종료. 다음: node tools/prep_dataset.js finalize"
