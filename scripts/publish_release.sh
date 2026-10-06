#!/usr/bin/env bash
# 체험판 GitHub 사전 릴리스 만들기 — gh CLI 없이 REST API 로(토큰은 git credential 저장소의 github.com 비밀번호 = PAT, repo 범위).
#   scripts/publish_release.sh <버전> <태그> <브랜치/커밋> <본문 .md> <자산 폴더>
#   예: scripts/publish_release.sh 1.3.0-preview.6 v1.3.0-preview.6 redesign docs/ANDROID_PREVIEW_RELEASE_1_3_0_6.md dist/1.3.0-preview.6
# 자산 폴더에는 TREX-<버전>.apk · SHA256SUMS.txt · BUILD_INFO.json 이 있어야 한다 — BUILD_INFO.json 은 앱 내 업데이트(AppUpdate.kt)의 정본이라 빠뜨리면 앱이 이 릴리스를 건너뛴다.
set -euo pipefail
VER="$1"; TAG="$2"; TARGET="$3"; BODY="$4"; DIR="$5"
OWNER="LeeDongHyun00"; REPO="TREX"
TOK=$(printf "protocol=https\nhost=github.com\n\n" | git credential fill | sed -n 's/^password=//p')
[ -n "$TOK" ] || { echo "github.com 자격증명이 없다(git credential)"; exit 1; }
for f in "TREX-$VER.apk" SHA256SUMS.txt BUILD_INFO.json; do [ -f "$DIR/$f" ] || { echo "자산 없음: $DIR/$f"; exit 1; }; done
H=(-H "Authorization: Bearer $TOK" -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28")
# 1) 릴리스(사전 릴리스, 초안 아님). 같은 태그가 있으면 그것을 쓴다
python - "$TAG" "$TARGET" "$VER" "$BODY" > /tmp/release_payload.json <<'EOF'
import json, sys
tag, target, ver, body = sys.argv[1:5]
text = open(body, encoding="utf-8").read()
print(json.dumps({"tag_name": tag, "target_commitish": target, "name": f"TREX {ver} · 한 다리 계열 v3와 앱 내 업데이트", "body": text, "draft": False, "prerelease": True}))
EOF
EXIST=$(curl -s "${H[@]}" "https://api.github.com/repos/$OWNER/$REPO/releases/tags/$TAG" | python -c "import sys,json; d=json.load(sys.stdin); print(d.get('id',''))")
if [ -n "$EXIST" ]; then RID="$EXIST"; echo "기존 릴리스 id $RID"; else
  RID=$(curl -s "${H[@]}" -X POST "https://api.github.com/repos/$OWNER/$REPO/releases" --data-binary @/tmp/release_payload.json | python -c "import sys,json; d=json.load(sys.stdin); print(d.get('id') or ('ERR ' + json.dumps(d)[:300]))")
  case "$RID" in ERR*) echo "$RID"; exit 1;; esac
  echo "릴리스 생성 id $RID"
fi
# 2) 자산 업로드(같은 이름이 있으면 지우고 다시)
upload() {
  local f="$1" type="$2" name; name=$(basename "$f")
  local old; old=$(curl -s "${H[@]}" "https://api.github.com/repos/$OWNER/$REPO/releases/$RID/assets?per_page=100" | python -c "import sys,json; print(' '.join(str(a['id']) for a in json.load(sys.stdin) if a['name']=='$name'))")
  for a in $old; do curl -s "${H[@]}" -X DELETE "https://api.github.com/repos/$OWNER/$REPO/releases/assets/$a" >/dev/null; done
  curl -s "${H[@]}" -H "Content-Type: $type" -X POST "https://uploads.github.com/repos/$OWNER/$REPO/releases/$RID/assets?name=$name" --data-binary @"$f" \
    | python -c "import sys,json; d=json.load(sys.stdin); print(d.get('state','?'), d.get('name'), d.get('size'), d.get('browser_download_url') or json.dumps(d)[:300])"
}
upload "$DIR/TREX-$VER.apk" application/vnd.android.package-archive
upload "$DIR/SHA256SUMS.txt" text/plain
upload "$DIR/BUILD_INFO.json" application/json
curl -s "${H[@]}" "https://api.github.com/repos/$OWNER/$REPO/releases/$RID" | python -c "import sys,json; d=json.load(sys.stdin); print(d['html_url'], 'draft', d['draft'], 'prerelease', d['prerelease'], [a['name'] for a in d['assets']])"
