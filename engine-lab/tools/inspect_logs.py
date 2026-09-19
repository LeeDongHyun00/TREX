"""독립 실험 앱 ZIP/JSONL을 읽고 사용자 정답과 관측치를 분리해 요약한다."""
import argparse
import json
from pathlib import Path
import statistics
import subprocess
import zipfile

PACKAGE = 'com.trex.engine.lab'


def summarize(name, text):
    rows = []
    malformed = []
    for i, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            malformed.append(i)
    header = next((r for r in rows if r.get('type') == 'session_start'), {})
    frames = [r for r in rows if r.get('type') == 'frame']
    end = next((r for r in reversed(rows) if r.get('type') == 'session_end'), None)
    truth = next((r for r in reversed(rows) if r.get('type') == 'human_annotation'), {})
    predicted = (end or (frames[-1] if frames else {})).get('counts', {})
    errors = {}
    for key in ('total', 'left', 'right'):
        target = truth.get('actual_' + key)
        # 시작 전에 지시한 목표 횟수나 앱의 카운트로 정답을 만들지 않는다.
        if type(target) is int and type(predicted.get(key)) is int:
            errors[key] = predicted[key] - target
    times = [r['t_ms'] for r in frames]
    inference = [r['infer_ms'] for r in frames if r.get('infer_ms') is not None]
    return {
        'file': name, 'session_id': header.get('session_id'), 'exercise': header.get('exercise'),
        'engine': header.get('engine'), 'engine_sha256': header.get('engine_sha256'),
        'pattern': header.get('pattern'), 'complete': end is not None,
        'frames': len(frames), 'pose_frames': sum(len(r.get('xy', [])) == 66 for r in frames),
        'tracking_frames': sum(r.get('phase') != 'UNOBSERVABLE' for r in frames),
        'counts': predicted, 'human_annotation': truth or None,
        'observed_minus_human': errors or None, 'malformed_lines': malformed,
        'non_monotonic_time': any(b <= a for a, b in zip(times, times[1:])),
        'gaps_over_1500ms': sum(b-a > 1500 for a, b in zip(times, times[1:])),
        'infer_median_ms': statistics.median(inference) if inference else None,
        'inference_error_frames': sum(bool(r.get('error')) for r in frames),
        'automatic_correction_enabled': header.get('automatic_correction_enabled'),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path, help='ZIP, JSONL 또는 로그 폴더. --pull이면 회수 대상 폴더')
    parser.add_argument('--pull', action='store_true')
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.pull:
        adb = [args.adb] + (['-s', args.serial] if args.serial else [])
        args.input.mkdir(parents=True, exist_ok=True)
        names = subprocess.check_output(adb + ['shell', 'run-as', PACKAGE, 'ls', 'files/sessions'], timeout=20).decode().splitlines()
        for name in names:
            if not name.endswith('.jsonl') or Path(name).name != name or '/' in name or '\\' in name:
                continue
            raw = subprocess.check_output(adb + ['exec-out', 'run-as', PACKAGE, 'cat', 'files/sessions/' + name], timeout=30)
            (args.input / name).write_bytes(raw)
    source = args.input
    if source.suffix.lower() == '.zip':
        with zipfile.ZipFile(source) as archive:
            data = [(n, archive.read(n).decode('utf-8')) for n in archive.namelist() if n.endswith('.jsonl')]
    else:
        paths = sorted(source.glob('*.jsonl')) if source.is_dir() else [source]
        data = [(p.name, p.read_text(encoding='utf-8')) for p in paths]
    sessions = [summarize(name, text) for name, text in data]
    known = [s['observed_minus_human']['total'] for s in sessions if s['observed_minus_human'] and 'total' in s['observed_minus_human']]
    report = {
        'sessions': sessions, 'session_count': len(sessions), 'human_labeled_total_count_sessions': len(known),
        'total_count_mae_on_labeled_sessions': statistics.mean(abs(e) for e in known) if known else None,
        'limits': '자가 입력 정답을 가진 세트만 횟수 오차를 계산합니다. 이 값은 자세 교정 정확도나 전체 26종 정확도가 아닙니다.',
    }
    text = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding='utf-8')
    print(text)


if __name__ == '__main__':
    main()
