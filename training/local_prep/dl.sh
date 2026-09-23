#!/bin/bash
# AI Hub 원천 다운로드. 서버가 Range 를 거부해(HTTP 200 + curl 33) 이어받기가 안 되므로,
# 멈추거나 끊기면 부분 파일을 버리고 처음부터 다시 받는다.
# ⚠️ curl 이 rc=0 으로 끝나도 파일이 온전하다는 보장이 없다(서버가 중간에 정상 종료 신호를 보냄).
#    그래서 예상 크기의 90% 이상일 때만 성공으로 본다.
# 사용: bash dl.sh <filekey> <저장폴더> <API키> <예상GB>
set -u
KEY="$1"; DIR="$2"; APIKEY="$3"; EXPECT_GB="${4:-0}"
URL="https://api.aihub.or.kr/down/0.6/74.do?fileSn=${KEY}"
MIN_BYTES=$(( EXPECT_GB * 1073741824 * 9 / 10 ))
[ "$MIN_BYTES" -lt 1000000000 ] && MIN_BYTES=1000000000
mkdir -p "$DIR"; cd "$DIR"
for attempt in $(seq 1 30); do
  rm -f download.tar
  start=$(date +%s)
  code=$(curl -sS -L -o download.tar -H "apikey:$APIKEY" \
         --speed-time 120 --speed-limit 50000 --connect-timeout 30 \
         -w "%{http_code}" "$URL" 2>>dl.err); rc=$?
  size=$(stat -c %s download.tar 2>/dev/null || echo 0)
  mins=$(( ($(date +%s) - start) / 60 ))
  if [ "$rc" -eq 0 ] && [ "$code" = "200" ] && [ "$size" -ge "$MIN_BYTES" ]; then
    echo "$(date '+%m-%d %H:%M:%S') 시도 $attempt: 완료 $((size/1073741824))GB ${mins}분" >> dl.log
    exit 0
  fi
  echo "$(date '+%m-%d %H:%M:%S') 시도 $attempt: 실패 http=$code rc=$rc $((size/1048576))MB/$((MIN_BYTES/1048576))MB필요 ${mins}분" >> dl.log
  sleep 30
done
echo "$(date '+%m-%d %H:%M:%S') 포기(30회)" >> dl.log; exit 1
