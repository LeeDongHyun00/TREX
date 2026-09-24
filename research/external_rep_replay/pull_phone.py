# -*- coding: utf-8 -*-
"""휴대폰 ↔ PC — 렙 검증(Gate A) 로그 회수와 검증 모드 표시 파일. 표준 라이브러리만 쓰고 윈도우·맥·리눅스에서 같게 돈다 (spec §61).

왜 새로 만들었나
    research/aihub_fitness/pull_logs.py 는 adb 경로가 윈도우 한 사람의 경로로 박혀 있고 `--clear-device` 로 기기 로그를 지울 수 있다.
    Gate A 는 세트 로그가 곧 측정 원자료라 **기기에서 아무것도 지우지 않는다** — 이 도구에는 지우는 명령이 없다(검증 모드 표시 파일
    `rep_validation.on` 을 끄는 `validation off` 만 예외. 그것은 데이터가 아니라 스위치다). 자가 테스트가 이 약속을 확인한다.

adb 찾는 순서 (처음 있는 것)
    --adb <경로>  →  $ADB  →  PATH 의 adb  →  $ANDROID_HOME·$ANDROID_SDK_ROOT 의 platform-tools  →
    %LOCALAPPDATA%\\Android\\Sdk\\platform-tools (윈도우 Android Studio 기본)  →  ~/Library/Android/sdk/platform-tools (맥)  →
    ~/Android/Sdk/platform-tools (리눅스)

기기 경로 (앱 전용 외부 폴더 — 루팅·권한 없이 adb 로 읽고 쓴다)
    /sdcard/Android/data/com.example.trex_kotlin/files/rep_validation.on              검증 모드 표시 파일(RepValidation.kt)
    /sdcard/Android/data/com.example.trex_kotlin/files/posture_logs/sets-*.jsonl      세트 로그
    .../posture_logs/rep_truth.csv · labels/set_labels.jsonl                          자가 라벨(spec §30)
    .../posture_logs/feedback-*.jsonl                                                 음성·화면 피드백 추적(PostureCoach.traceFeedback)

사용법
    python pull_phone.py devices
    python pull_phone.py validation on|off|status
    python pull_phone.py pull [--out data/phone/<시각>]      # 기기 posture_logs 를 그 폴더에 같은 구조로 복사(+ pull_manifest.json)
    python pull_phone.py --self-test                         # 가짜 adb 로 찾기 순서·명령 구성·'지우지 않음' 확인 (기기 불필요)
공통 옵션: --adb <경로>, --serial <기기 번호>(기기가 여럿일 때)
받은 로그는 개인 측정 기록이다 — 저장소 밖이나 git 이 무시하는 data/ 아래에 둔다(기본값이 그렇다). 커밋하지 않는다.
"""
from __future__ import annotations

import argparse
import datetime as _dt
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
PKG = "com.example.trex_kotlin"
FILES_DIR = f"/sdcard/Android/data/{PKG}/files"
LOG_DIR = f"{FILES_DIR}/posture_logs"
FLAG = f"{FILES_DIR}/rep_validation.on"
DEFAULT_OUT_ROOT = REPO / "data" / "phone"
IS_WINDOWS = os.name == "nt"


# ---------------------------------------------------------------- adb 찾기

def _exe_names() -> tuple[str, ...]:
    return ("adb.exe", "adb") if IS_WINDOWS else ("adb",)


def candidate_dirs(env: dict[str, str] | None = None, home: Path | None = None) -> list[tuple[str, Path]]:
    """(출처, platform-tools 폴더) — 문서의 순서 그대로. 존재 여부는 보지 않는다."""
    env = os.environ if env is None else env
    home = Path.home() if home is None else home
    out = []
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if env.get(key):
            out.append((f"${key}", Path(env[key]) / "platform-tools"))
    if env.get("LOCALAPPDATA"):
        out.append(("%LOCALAPPDATA%", Path(env["LOCALAPPDATA"]) / "Android" / "Sdk" / "platform-tools"))
    out.append(("~/Library", home / "Library" / "Android" / "sdk" / "platform-tools"))
    out.append(("~/Android", home / "Android" / "Sdk" / "platform-tools"))
    return out


def find_adb(explicit: str | None = None, env: dict[str, str] | None = None, home: Path | None = None,
             which=shutil.which) -> tuple[Path | None, str]:
    """(adb 경로, 출처). 못 찾으면 (None, 찾아본 곳 목록)."""
    env = os.environ if env is None else env
    tried = []
    if explicit:
        p = Path(explicit)
        if p.is_file():
            return p, "--adb"
        tried.append(f"--adb {p}")
    if env.get("ADB"):
        p = Path(env["ADB"])
        if p.is_file():
            return p, "$ADB"
        tried.append(f"$ADB {p}")
    w = which("adb", path=env.get("PATH"))
    if w:
        return Path(w), "PATH"
    tried.append("PATH")
    for src, d in candidate_dirs(env, home):
        for name in _exe_names():
            if (d / name).is_file():
                return d / name, src
        tried.append(f"{src} {d}")
    return None, "; ".join(tried)


# ---------------------------------------------------------------- adb 실행

class Adb:
    """adb 호출. runner 를 바꾸면(자가 테스트) 실제 프로세스를 띄우지 않고 명령만 기록한다."""

    def __init__(self, exe: Path, serial: str | None = None, runner=None):
        self.exe, self.serial = exe, serial
        self.runner = runner or self._run
        self.calls: list[list[str]] = []

    @staticmethod
    def _run(cmd: list[str]) -> tuple[int, str]:
        r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
        return r.returncode, (r.stdout or "") + (r.stderr or "")

    def cmd(self, *args: str) -> list[str]:
        base = [str(self.exe)]
        if self.serial:
            base += ["-s", self.serial]
        return base + list(args)

    def __call__(self, *args: str) -> tuple[int, str]:
        c = self.cmd(*args)
        assert_no_device_delete(c)
        self.calls.append(c)
        return self.runner(c)

    def shell(self, script: str) -> tuple[int, str]:
        # 원격 셸 명령은 한 인자로 넘긴다 — adb 가 인자를 공백으로 이어 붙여 기기 셸에 넘기므로 운영체제마다 같게 해석된다
        return self("shell", script)


def assert_no_device_delete(cmd: list[str]) -> None:
    """이 도구가 기기에서 지울 수 있는 것은 검증 모드 표시 파일 하나뿐이다 — 다른 삭제·이동 명령은 코드 버그로 보고 멈춘다.
    원격 셸 명령만 본다(pull 의 PC 쪽 경로 이름은 상관없다)."""
    if "shell" not in cmd:
        return
    script = " ".join(cmd[cmd.index("shell") + 1:])
    words = set(script.replace(";", " ").replace("&", " ").replace("|", " ").split())
    if words & {"rm", "rmdir", "unlink", "mv"} and script != f"rm -f {FLAG}":
        raise RuntimeError(f"기기에서 지우는 명령은 허용하지 않는다: {script}")


def devices(adb: Adb) -> list[tuple[str, str]]:
    """(serial, 상태) — 'device' 가 아닌 것(unauthorized·offline)도 돌려준다(원인을 보여 주려고)."""
    code, out = adb("devices")
    if code != 0:
        raise SystemExit(f"[adb 실패] devices\n{out}")
    rows = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and not line.startswith("*"):
            rows.append((parts[0], parts[1]))
    return rows


def require_device(adb: Adb) -> str:
    rows = devices(adb)
    ready = [s for s, st in rows if st == "device"]
    if adb.serial:
        if adb.serial not in ready:
            raise SystemExit(f"[err] 기기 {adb.serial} 가 준비되지 않았다: {rows}")
        return adb.serial
    if not ready:
        hint = " (폰 화면에서 USB 디버깅 허용을 누르세요)" if any(st == "unauthorized" for _, st in rows) else ""
        raise SystemExit(f"[err] 연결된 기기가 없다{hint}: {rows or '없음'}")
    if len(ready) > 1:
        raise SystemExit(f"[err] 기기가 여럿이다 — --serial 로 고르세요: {ready}")
    return ready[0]


# ---------------------------------------------------------------- 검증 모드 표시 파일

def validation(adb: Adb, action: str) -> str:
    require_device(adb)
    if action == "on":
        code, out = adb.shell(f"touch {FLAG}")
        if code != 0:
            raise SystemExit(f"[err] 표시 파일을 만들지 못했다 — 앱을 한 번 실행해 {FILES_DIR} 가 생긴 뒤 다시 하세요.\n{out}")
    elif action == "off":
        code, out = adb.shell(f"rm -f {FLAG}")
        if code != 0:
            raise SystemExit(f"[err] 표시 파일을 지우지 못했다\n{out}")
    code, out = adb.shell(f"if [ -f {FLAG} ]; then echo on; else echo off; fi")
    state = "on" if out.strip().endswith("on") else "off"
    return state


# ---------------------------------------------------------------- 회수

def is_wanted(rel: str) -> bool:
    """회수 대상 — 세트 로그, 자가 라벨, 피드백 추적. 그 밖의 파일은 받지 않는다(모르는 것을 원자료에 섞지 않는다)."""
    name = rel.rsplit("/", 1)[-1]
    if "/" not in rel:
        return ((name.startswith("sets-") or name.startswith("feedback-")) and name.endswith(".jsonl")) or name == "rep_truth.csv"
    return rel == "labels/set_labels.jsonl"


def remote_listing(adb: Adb) -> dict[str, int | None]:
    """posture_logs 아래 파일 → 크기(바이트, 모르면 None). 하위 폴더는 labels 만 본다."""
    out_files: dict[str, int | None] = {}
    for sub in ("", "labels/"):
        code, out = adb.shell(f"ls -l {LOG_DIR}/{sub}")
        if code != 0 or "No such file" in out:
            if not sub:
                raise SystemExit(f"[err] 기기에 로그 폴더가 없다: {LOG_DIR} — 세션을 한 세트 이상 마친 뒤 다시 하세요.\n{out}")
            continue
        for line in out.splitlines():
            parts = line.split()
            if len(parts) < 6 or line.startswith(("total", "d")):
                continue
            name = parts[-1]
            size = None
            for tok in parts[3:6]:
                if tok.isdigit():
                    size = int(tok)
            rel = sub + name
            if is_wanted(rel):
                out_files[rel] = size
    return out_files


def default_out() -> Path:
    return DEFAULT_OUT_ROOT / _dt.datetime.now().strftime("%Y%m%d-%H%M%S")


def pull(adb: Adb, out: Path) -> dict:
    serial = require_device(adb)
    files = remote_listing(adb)
    if not any(r.startswith("sets-") for r in files):
        raise SystemExit("[err] 세트 로그(sets-*.jsonl)가 없다 — 세션을 한 세트 이상 마쳐 주세요.")
    out.mkdir(parents=True, exist_ok=True)
    got, problems = {}, []
    for rel, size in sorted(files.items()):
        dest = out / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        code, text = adb("pull", f"{LOG_DIR}/{rel}", str(dest))
        local = dest.stat().st_size if dest.is_file() else None
        ok = code == 0 and local is not None and (size is None or local == size)
        got[rel] = {"remoteBytes": size, "localBytes": local, "ok": ok}
        if not ok:
            problems.append(f"{rel}: adb {code}, 기기 {size} B / 받은 {local} B — {text.strip()[:200]}")
        print(f"[{'pull' if ok else 'FAIL'}] {rel}  {local} B")
    _, flag = adb.shell(f"if [ -f {FLAG} ]; then echo on; else echo off; fi")
    manifest = {"device": serial, "pulledAt": _dt.datetime.now().astimezone().isoformat(timespec="seconds"),
                "remoteDir": LOG_DIR, "validationFlag": "on" if flag.strip().endswith("on") else "off",
                "files": got, "problems": problems,
                "note": "기기에서는 아무것도 지우지 않았다. 이 폴더는 개인 측정 기록 — 커밋하지 않는다."}
    (out / "pull_manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    if problems:
        raise SystemExit("[err] 일부 파일을 온전히 받지 못했다:\n  " + "\n  ".join(problems))
    return manifest


# ---------------------------------------------------------------- 자가 테스트 (기기 없음)

FAKE_ADB = r'''
import os, sys, pathlib
root = pathlib.Path(os.environ["FAKE_ADB_ROOT"])
args = sys.argv[1:]
with open(root / "calls.log", "a", encoding="utf-8") as h:
    h.write("\t".join(args) + "\n")
if args[:2] == ["-s", "SER1"]:
    args = args[2:]
dev = root / "device"
def local(p):
    return dev / p.lstrip("/")
if args == ["devices"]:
    print("List of devices attached\nSER1\tdevice\n"); sys.exit(0)
if args[0] == "shell":
    s = " ".join(args[1:])
    if s.startswith("touch "):
        f = local(s.split()[1]); f.parent.mkdir(parents=True, exist_ok=True); f.write_text(""); sys.exit(0)
    if s.startswith("rm -f "):
        f = local(s.split()[2])
        if f.exists(): f.unlink()
        sys.exit(0)
    if s.startswith("if [ -f "):
        print("on" if local(s.split()[3]).exists() else "off"); sys.exit(0)
    if s.startswith("ls -l "):
        d = local(s.split()[2])
        if not d.is_dir():
            print(f"ls: {s.split()[2]}: No such file or directory"); sys.exit(1)
        print("total 8")
        for q in sorted(d.iterdir()):
            kind = "d" if q.is_dir() else "-"
            size = 0 if q.is_dir() else q.stat().st_size
            print(f"{kind}rw-rw---- 1 u0_a1 ext_data_rw {size} 2026-09-24 10:00 {q.name}")
        sys.exit(0)
if args[0] == "pull":
    src, dst = local(args[1]), pathlib.Path(args[2])
    dst.write_bytes(src.read_bytes()); print(f"{args[1]}: 1 file pulled"); sys.exit(0)
print("unknown " + " ".join(args)); sys.exit(2)
'''


def self_test(work: Path) -> int:
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, ok: bool, detail: str = "") -> None:
        checks.append((name, bool(ok), detail))

    work.mkdir(parents=True, exist_ok=True)
    # 1) 찾기 순서 — 가짜 폴더에 adb 를 놓고 환경만 바꾼다(실제 PATH 는 비운다)
    home = work / "home"
    sdk = {k: work / k / "platform-tools" for k in ("android_home", "sdk_root", "localappdata_sdk")}
    lib = home / "Library" / "Android" / "sdk" / "platform-tools"
    lin = home / "Android" / "Sdk" / "platform-tools"
    name = _exe_names()[0]
    for d in (*sdk.values(), lib, lin):
        d.mkdir(parents=True, exist_ok=True)
        (d / name).write_text("")
    lad = work / "localappdata"
    (lad / "Android" / "Sdk" / "platform-tools").mkdir(parents=True, exist_ok=True)
    (lad / "Android" / "Sdk" / "platform-tools" / name).write_text("")
    explicit = work / "explicit_adb"
    explicit.write_text("")
    env_adb = work / "env_adb"
    env_adb.write_text("")
    path_dir = work / "pathdir"
    path_dir.mkdir(exist_ok=True)
    full_env = {"ADB": str(env_adb), "PATH": str(path_dir), "ANDROID_HOME": str(work / "android_home"),
                "ANDROID_SDK_ROOT": str(work / "sdk_root"), "LOCALAPPDATA": str(lad)}
    no_which = lambda *_a, **_k: None
    fake_which = lambda *_a, **_k: str(path_dir / name)
    order = [
        ("--adb 이 첫째", find_adb(str(explicit), full_env, home, fake_which), explicit, "--adb"),
        ("$ADB 가 둘째", find_adb(None, full_env, home, fake_which), env_adb, "$ADB"),
        ("PATH 가 셋째", find_adb(None, {**full_env, "ADB": ""}, home, fake_which), path_dir / name, "PATH"),
        ("$ANDROID_HOME", find_adb(None, {**full_env, "ADB": ""}, home, no_which), sdk["android_home"] / name, "$ANDROID_HOME"),
        ("$ANDROID_SDK_ROOT", find_adb(None, {k: v for k, v in full_env.items() if k not in ("ADB", "ANDROID_HOME")}, home, no_which),
         sdk["sdk_root"] / name, "$ANDROID_SDK_ROOT"),
        ("%LOCALAPPDATA%", find_adb(None, {"LOCALAPPDATA": str(lad)}, home, no_which),
         lad / "Android" / "Sdk" / "platform-tools" / name, "%LOCALAPPDATA%"),
        ("~/Library (맥)", find_adb(None, {}, home, no_which), lib / name, "~/Library"),
    ]
    for label, (got, src), want, want_src in order:
        check(f"찾기: {label}", got == want and src == want_src, f"{src} {got}")
    (lib / name).unlink()
    got, src = find_adb(None, {}, home, no_which)
    check("찾기: ~/Android (리눅스)", got == lin / name and src == "~/Android", f"{src} {got}")
    (lin / name).unlink()
    got, src = find_adb(str(work / "missing"), {}, home, no_which)
    check("찾기: 없으면 None + 찾아본 곳 목록", got is None and "--adb" in src and "~/Android" in src, src)

    # 2) 명령 구성 — 기기 번호, 원격 셸 한 인자
    a = Adb(Path("/x/adb"), "SER9", runner=lambda c: (0, ""))
    check("명령: -s 기기 번호가 앞에", a.cmd("pull", "a", "b") == ["/x/adb", "-s", "SER9", "pull", "a", "b"])
    try:
        a.shell(f"rm -rf {LOG_DIR}")
        blocked = False
    except RuntimeError:
        blocked = True
    check("안전: 로그 폴더 삭제 명령은 코드에서 막힌다", blocked)

    # 3) 가짜 adb 로 전 과정 — 파이썬 스크립트를 실행 파일처럼 감싼다
    root = work / "fake"
    dev_logs = root / "device" / LOG_DIR.lstrip("/")
    (dev_logs / "labels").mkdir(parents=True, exist_ok=True)
    (dev_logs / "sets-20260924.jsonl").write_text('{"schema":"trex.posture.setlog/1","frames":[]}\n', encoding="utf-8")
    (dev_logs / "feedback-20260924.jsonl").write_text('{"t_ms":1,"kind":"x"}\n', encoding="utf-8")
    (dev_logs / "rep_truth.csv").write_text("set_id,reps_min,reps_max,exercise,form,source,created_at\n", encoding="utf-8")
    (dev_logs / "labels" / "set_labels.jsonl").write_text("{}\n", encoding="utf-8")
    (dev_logs / "notes.txt").write_text("모르는 파일", encoding="utf-8")
    script = root / "fake_adb.py"
    script.write_text(FAKE_ADB, encoding="utf-8")
    if IS_WINDOWS:
        exe = root / "adb.bat"
        exe.write_text(f'@"{sys.executable}" "{script}" %*\n', encoding="utf-8")
    else:
        exe = root / "adb"
        exe.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{script}" "$@"\n', encoding="utf-8")
        exe.chmod(0o755)
    os.environ["FAKE_ADB_ROOT"] = str(root)
    adb = Adb(exe)
    check("가짜 adb: devices", devices(adb) == [("SER1", "device")])
    check("검증 모드: status off → on → status on", validation(adb, "status") == "off" and validation(adb, "on") == "on"
          and validation(adb, "status") == "on")
    out = work / "pulled"
    before = sorted(p.relative_to(root / "device").as_posix() for p in (root / "device").rglob("*") if p.is_file())
    man = pull(adb, out)
    got_files = sorted(p.relative_to(out).as_posix() for p in out.rglob("*") if p.is_file())
    check("회수: 세트 로그·피드백·자가 라벨(labels/ 구조 유지)만, 모르는 파일은 받지 않음 + 매니페스트",
          got_files == ["feedback-20260924.jsonl", "labels/set_labels.jsonl", "pull_manifest.json", "rep_truth.csv", "sets-20260924.jsonl"],
          str(got_files))
    check("회수: 크기 일치 · 매니페스트에 기기·표시 파일 상태", all(v["ok"] for v in man["files"].values()) and man["device"] == "SER1"
          and man["validationFlag"] == "on", json.dumps(man["files"]))
    after_pull = sorted(p.relative_to(root / "device").as_posix() for p in (root / "device").rglob("*") if p.is_file())
    check("회수: 기기 파일이 하나도 줄지 않았다", after_pull == before, f"{len(before)} → {len(after_pull)}")
    check("검증 모드: off → 표시 파일만 사라진다", validation(adb, "off") == "off")
    final = sorted(p.relative_to(root / "device").as_posix() for p in (root / "device").rglob("*") if p.is_file())
    check("검증 모드 off 뒤에도 로그·라벨은 그대로", final == [p for p in before if not p.endswith("rep_validation.on")], str(final))
    calls = (root / "calls.log").read_text(encoding="utf-8").splitlines()
    rms = [c for c in calls if "rm" in c.split("\t")[-1].split()[:1]]
    check("adb 호출 전체에서 rm 은 표시 파일 하나뿐", rms == [f"shell\trm -f {FLAG}"], str(rms))
    # 로그 폴더가 없으면 알아듣게 멈춘다
    shutil.rmtree(root / "device" / LOG_DIR.lstrip("/"))
    try:
        pull(adb, work / "pulled2")
        stopped = False
    except SystemExit as e:
        stopped = "로그 폴더가 없다" in str(e)
    check("회수: 로그 폴더가 없으면 설명과 함께 멈춘다", stopped)

    width = max(len(n) for n, _, _ in checks)
    for n, ok, d in checks:
        print(f"{'PASS' if ok else 'FAIL'}  {n.ljust(width)}  {d}")
    failed = sum(not ok for _, ok, _ in checks)
    print(f"\n{len(checks) - failed}/{len(checks)} 통과 — 작업 폴더 {work}")
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--adb", default=None, help="adb 실행 파일 경로")
    ap.add_argument("--serial", default=None, help="기기 번호(adb devices 첫 열) — 기기가 여럿일 때")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--work", type=Path, default=None, help="자가 테스트 작업 폴더")
    sub = ap.add_subparsers(dest="cmd")
    sub.add_parser("devices")
    v = sub.add_parser("validation")
    v.add_argument("action", choices=("on", "off", "status"))
    p = sub.add_parser("pull")
    p.add_argument("--out", type=Path, default=None, help="받을 폴더 (기본 data/phone/<시각>)")
    args = ap.parse_args()
    if args.self_test:
        return self_test(args.work or Path(tempfile.mkdtemp(prefix="pull_phone_selftest_")))
    if not args.cmd:
        ap.error("devices | validation on|off|status | pull 중 하나")
    exe, src = find_adb(args.adb)
    if exe is None:
        raise SystemExit(f"[err] adb 를 찾지 못했다 — --adb 로 경로를 주거나 $ADB 를 설정하세요. 찾아본 곳: {src}")
    print(f"[adb] {exe}  ({src})")
    adb = Adb(exe, args.serial)
    if args.cmd == "devices":
        rows = devices(adb)
        for s, st in rows:
            print(f"{s}\t{st}")
        if not rows:
            print("(연결된 기기 없음)")
        return 0 if any(st == "device" for _, st in rows) else 1
    if args.cmd == "validation":
        state = validation(adb, args.action)
        print(f"검증 모드: {state}  ({FLAG})")
        if args.action != "status":
            print("앱은 세션 단계가 바뀔 때 표시 파일을 다시 읽는다 — 새 세션을 시작하고 화면 배너를 확인하세요.")
        return 0
    out = args.out or default_out()
    man = pull(adb, out)
    n = sum(r.startswith("sets-") for r in man["files"])
    print(f"\n세트 로그 파일 {n}개 → {out}  (검증 모드 표시 파일: {man['validationFlag']}). 기기에서는 아무것도 지우지 않았다.")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
