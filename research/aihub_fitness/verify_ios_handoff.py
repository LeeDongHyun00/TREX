"""iOS 인수인계 자산을 읽기 전용으로 확인한다. 빌드·판정 검사가 아니다."""

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import sys


def verify(root, manifest, strict_bytes=False):
    results = []
    for item in manifest["files"]:
        result = {"path": item["path"], "expectedSha256": item["sha256"]}
        try:
            path = (root / item["path"]).resolve()
            path.relative_to(root.resolve())
            raw = path.read_bytes()
            actual = hashlib.sha256(raw).hexdigest()
            # 내용 비교에서도 CRLF만 허용한다. 공백·인코딩·숫자는 바꾸지 않는다.
            canonical = raw.replace(b"\r\n", b"\n") if item["text"] else raw
            exact = actual == item["sha256"]
            content = exact or hashlib.sha256(canonical).hexdigest() == item["sha256"]
            result.update(rawSha256=actual, byteIdentical=exact,
                          contentIdentical=content, eolOnly=content and not exact)
            counts_ok = True
            if "ruleCounts" in item:
                rules = json.loads(raw.decode("utf-8"))["rules"]
                counts = dict(Counter(rule["status"] for rule in rules))
                counts_ok = counts == item["ruleCounts"]
                result.update(ruleCounts=counts, ruleCountsMatch=counts_ok)
            result["passed"] = (exact if strict_bytes else content) and counts_ok
        except (OSError, ValueError, KeyError, TypeError) as error:
            result.update(passed=False, error=str(error))
        results.append(result)
    return {"schema": "trex.ios-handoff-check/1",
            "sourceCommit": manifest["sourceCommit"],
            "scope": "checkout assets only; no build, inference or engine validation",
            "strictBytes": strict_bytes,
            "passed": bool(results) and all(row["passed"] for row in results),
            "files": results}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2],
                        help="검사할 저장소 루트. 기본값은 이 스크립트의 저장소다.")
    parser.add_argument("--strict-bytes", action="store_true",
                        help="텍스트 줄바꿈만 달라도 실패한다.")
    args = parser.parse_args()
    try:
        manifest = json.loads((Path(__file__).resolve().parents[2] /
                               "docs/ios-kmp/ASSET_BASELINE.json").read_text(encoding="utf-8"))
        if manifest["schema"] != "trex.ios-handoff-assets/1":
            raise ValueError("지원하지 않는 자산 기준 형식")
        report = verify(args.root, manifest, args.strict_bytes)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(json.dumps({"passed": False, "error": str(error)}, ensure_ascii=True))
        return 1
    print(json.dumps(report, ensure_ascii=True, indent=2))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
