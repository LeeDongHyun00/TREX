"""별도 평가 패키지 설치·재생·회수. 각 외부 명령은 제한 시간 안에 끝나야 한다."""
from pathlib import Path
import argparse
import datetime
import hashlib
import json
import subprocess
import sys
import time

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
OUT = HERE / "outputs/device_replay"
SDK = Path.home() / "AppData/Local/Android/Sdk"
ADB = SDK / "platform-tools/adb.exe"
AAPT = SDK / "build-tools/36.1.0/aapt.exe"
PACKAGE = "com.example.trex_kotlin.replay"
REMOTE = f"/sdcard/Android/data/{PACKAGE}/files/aihub_replay"


def run(args, timeout=120):
    print("command:", " ".join(map(str, args)), flush=True)
    r = subprocess.run(list(map(str, args)), capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    print(r.stdout, r.stderr, flush=True)
    r.check_returncode()
    return r.stdout


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--resume", action="store_true", help="설치·전송을 건너뛰고 저장된 조합 뒤부터 재개")
    parser.add_argument("--collect", action="store_true", help="단말에서 계속 실행 중인 평가를 기다려 결과만 회수")
    args = parser.parse_args()
    apk = REPO / "app/build/outputs/apk/debug/app-debug.apk"
    test = REPO / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    badging = run([AAPT, "dump", "badging", apk])
    assert f"package: name='{PACKAGE}'" in badging, "기존 앱에 설치하지 않는다"
    assert "targetPackage='com.example.trex_kotlin'" not in badging
    devices = run([ADB, "devices"]).splitlines()
    serials = [line.split()[0] for line in devices if len(line.split()) == 2 and line.split()[1] == "device"]
    assert len(serials) == 1, "단일 연결 기기가 필요합니다"
    cmd = [ADB, "-s", serials[0]]
    apk_hash = hashlib.sha256(apk.read_bytes()).hexdigest()
    metadata = dict(started=datetime.datetime.now(datetime.timezone.utc).isoformat(), serial=serials[0],
                    apk_sha256=apk_hash, package=PACKAGE,
                    limitations=["camera/IMU bypassed", "synthetic timestamps", "training diagnostic, not held-out accuracy"])
    if args.resume or args.collect:
        metadata = json.loads((OUT / "run.json").read_text(encoding="utf-8"))
        assert metadata["serial"] == serials[0] and metadata["apk_sha256"] == apk_hash, "다른 기기/코드 결과와 혼합할 수 없습니다"
    (OUT / "run.json").write_text(json.dumps(metadata, indent=1), encoding="utf-8")
    if not args.resume and not args.collect:
        run(cmd + ["install", "-r", str(apk)])
        run(cmd + ["install", "-r", str(test)])
        run(cmd + ["shell", "mkdir", "-p", REMOTE])
        run(cmd + ["push", str(OUT / "replay.zip"), REMOTE + "/replay.zip"], timeout=180)
    if args.collect:
        deadline = time.monotonic() + 1250
        while True:
            complete = subprocess.run(list(map(str, cmd + ["shell", "cat", REMOTE + "/complete.json"])), capture_output=True, timeout=30)
            if complete.returncode == 0: break
            assert time.monotonic() < deadline, "완료 대기 시간 초과"
            run(cmd + ["shell", "cat", REMOTE + "/progress.json"], timeout=30)
            time.sleep(10)
    else:
        run(cmd + ["shell", "am", "instrument", "-w", "-r", "-e", "class",
                   "com.example.trex_kotlin.posture.AiHubReplayTest", PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"], timeout=1250)
    run(cmd + ["pull", REMOTE + "/results.jsonl", str(OUT / "results.jsonl")])
    run(cmd + ["pull", REMOTE + "/complete.json", str(OUT / "complete.json")])
    metadata["completed"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    (OUT / "run.json").write_text(json.dumps(metadata, indent=1), encoding="utf-8")
