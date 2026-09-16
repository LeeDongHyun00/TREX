#!/bin/bash
# 이미 돌고 있는 11번 다운로드가 끝나기를 기다린 뒤, 선택한 묶음(11 04 06_07_08 09_10)을 순서대로 처리한다.
# 동시 다운로드는 서버 멈춤의 원인일 수 있어 항상 하나씩만 받는다.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEY="$1"
log() { echo "$(date '+%m-%d %H:%M:%S') [run_all] $*" | tee -a "$ROOT/pipeline.log"; }

log "11번 다운로드 완료 대기 시작"
while true; do
  size=$(stat -c %s "$ROOT/images_11/download.tar" 2>/dev/null || echo 0)
  if [ "$size" -gt 14000000000 ]; then log "11번 내려받음 $((size/1073741824))GB"; break; fi
  if grep -qE "완료|포기" "$ROOT/images_11/dl.log" 2>/dev/null; then log "11번 dl.sh 종료: $(tail -1 "$ROOT/images_11/dl.log")"; break; fi
  if ! tasklist 2>/dev/null | grep -qi curl; then
    sleep 30
    if ! tasklist 2>/dev/null | grep -qi curl; then log "curl 프로세스 없음 — 11번 중단된 것으로 보고 진행"; break; fi
  fi
  sleep 300
done

bash "$ROOT/tools/pipeline.sh" "$KEY" 11 04 06_07_08 09_10
