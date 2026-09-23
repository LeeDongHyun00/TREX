"""요청된 GitHub 사전 릴리스에 APK/체크섬을 게시한다. 자격 증명은 메모리에서만 사용.

기존 공개 릴리스/자산은 덮어쓰지 않는다. 모든 파일을 draft에 올린 뒤 공개하고,
실제 공개 다운로드의 SHA-256을 로컬 파일과 비교한다.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request


def git(*args):
    return subprocess.check_output(["git", *args], text=True, encoding="utf-8").strip()


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--repo", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--notes", type=Path, required=True)
    parser.add_argument("--asset", type=Path, action="append", required=True)
    args=parser.parse_args()
    if not re.fullmatch(r"[\w.-]+/[\w.-]+", args.repo):
        raise SystemExit("Invalid repository")
    if git("status", "--porcelain"):
        raise SystemExit("Commit the reviewed source before publishing")
    if len({path.name for path in args.asset}) != len(args.asset) or any(not path.is_file() for path in args.asset):
        raise SystemExit("Assets must be existing files with unique names")
    remote=git("remote", "get-url", "origin")
    if remote not in (f"https://github.com/{args.repo}.git", f"https://github.com/{args.repo}"):
        raise SystemExit("Origin repository mismatch")
    commit=git("rev-parse", "HEAD")
    result=subprocess.run(["git","credential","fill"],input="protocol=https\nhost=github.com\n\n",
                          text=True,capture_output=True,env={**os.environ,"GCM_INTERACTIVE":"never"})
    if result.returncode:
        raise SystemExit("GitHub credential helper failed (credential output withheld)")
    fields=dict(line.split("=",1) for line in result.stdout.splitlines() if "=" in line)
    token=fields.get("password")
    if not token:
        raise SystemExit("GitHub authentication is required")

    def request(method, url, payload=None, binary=False):
        data=payload if binary else (json.dumps(payload).encode("utf-8") if payload is not None else None)
        req=urllib.request.Request(url,data=data,method=method,headers={
            "Authorization":f"Bearer {token}","Accept":"application/vnd.github+json",
            "X-GitHub-Api-Version":"2022-11-28","User-Agent":"TREX-preview-publisher",
            "Content-Type":"application/octet-stream" if binary else "application/json"})
        with urllib.request.urlopen(req,timeout=180) as response:
            return json.load(response)

    base=f"https://api.github.com/repos/{args.repo}"
    request("GET",f"{base}/commits/{commit}")
    # 태그가 이미 사용 중이면 공개 자산을 교체하지 않는다.
    try:
        request("GET",f"{base}/releases/tags/{urllib.parse.quote(args.tag,safe='')}")
    except urllib.error.HTTPError as error:
        if error.code != 404: raise
    else:
        raise SystemExit("Release already exists; use a new version/tag")
    body=args.notes.read_text("utf-8")+f"\n\nSource commit: `{commit}`\n"
    release=request("POST",f"{base}/releases",{
        "tag_name":args.tag,"target_commitish":commit,"name":f"TREX {args.tag}",
        "body":body,"draft":True,"prerelease":True,"make_latest":"false"})
    assets=[]
    for path in args.asset:
        content=path.read_bytes()
        url=f"https://uploads.github.com/repos/{args.repo}/releases/{release['id']}/assets?name={urllib.parse.quote(path.name)}"
        uploaded=request("POST",url,content,binary=True)
        if uploaded["size"] != len(content):
            raise SystemExit("Upload size mismatch; release remains draft")
        assets.append((path,uploaded))
    release=request("PATCH",f"{base}/releases/{release['id']}",{"draft":False})
    try:
        for path, uploaded in assets:
            for attempt in range(3):
                digest=hashlib.sha256()
                req=urllib.request.Request(uploaded["browser_download_url"],headers={"User-Agent":"TREX-preview-verifier"})
                try:
                    with urllib.request.urlopen(req,timeout=180) as response:
                        while chunk:=response.read(1024*1024): digest.update(chunk)
                    break
                except (urllib.error.URLError, TimeoutError):
                    if attempt == 2: raise
                    time.sleep(3)
            if digest.hexdigest() != hashlib.sha256(path.read_bytes()).hexdigest():
                raise ValueError("Download checksum mismatch")
            print(json.dumps({"name":path.name,"url":uploaded["browser_download_url"],"sha256":digest.hexdigest()}))
    except Exception:
        # 공개 다운로드를 검증하지 못하면 사용자에게 미검증 파일을 계속 배포하지 않는다.
        request("PATCH",f"{base}/releases/{release['id']}",{"draft":True})
        raise SystemExit("Download verification failed; release returned to draft")
    print(json.dumps({"release":release["html_url"],"source_commit":commit}))


if __name__=="__main__":
    try: main()
    except urllib.error.HTTPError as error:
        raise SystemExit(f"GitHub HTTP {error.code}; response body withheld")
