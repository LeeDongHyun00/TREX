#!/bin/bash
# AI Hub 원천 다운로드. 서버가 Range 를 거부해(HTTP 200 + curl 33) 이어받기가 안 되므로,
# 멈추면(120초간 50KB/s 미만) 부분 파일을 버리고 처음부터 다시 받는다.
# 사용: bash dl.sh <filekey> <저장폴더> <API키>  → <저장폴더>/download.tar 완성 시 exit 0
set -u
KEY="$1"; DIR="$2"; APIKEY="$3"
URL="https://api.aihub.or.kr/down/0.6/74.do?fileSn=${KEY}"
mkdir -p "$DIR"; cd "$DIR"
for attempt in $(seq 1 30); do
  rm -f download.tar
  start=$(date +%s)
  code=$(curl -sS -L -o download.tar -H "apikey:$APIKEY" \
         --speed-time 120 --speed-limit 50000 --connect-timeout 30 \
         -w "%{http_code}" "$URL" 2>>dl.err); rc=$?
  size=$(stat -c %s download.tar 2>/dev/null || echo 0)
  mins=$(( ($(date +%s) - start) / 60 ))
  echo "$(date '+%m-%d %H:%M:%S') 시도 $attempt: http=$code rc=$rc $((size/1048576))MB ${mins}분" >> dl.log
  if [ "$rc" -eq 0 ] && [ "$code" = "200" ] && [ "$size" -gt 1000000000 ]; then
    echo "완료 $((size/1073741824))GB" >> dl.log; exit 0
  fi
  sleep 30
done
echo "포기(30회)" >> dl.log; exit 1
