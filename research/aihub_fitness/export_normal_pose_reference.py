"""Android VIDEO 결과 중 전 조건 정상 클립의 단계별 참고 범위를 내보낸다. 규칙 임계값은 수정하지 않는다."""
from collections import defaultdict
import hashlib
import json
from pathlib import Path
from statistics import median

HERE = Path(__file__).resolve().parent
OUT = HERE / 'outputs/device_replay'
ASSET = HERE.parents[1] / 'app/src/main/assets/posture/normal_pose_reference.tsv'
# 앱 RepSignals 및 ComparisonMetrics와 동일한 입력 피처. 원본 GT 좌표를 비교값으로 섞지 않는다.
SIGNALS = {'푸시업': ('wrist_shoulder_d', .3), '니푸쉬업': ('wrist_shoulder_d', .3),
           '크런치': ('head_ground', .15), '라잉 레그 레이즈': ('hip_ang', 25),
           '힙쓰러스트': ('hip_dev_ankle', .1), 'Y - Exercise': ('hand_shoulder_off', .2),
           '시저크로스': ('knee_gap2d', .25), '플랭크': ('hip_dev_ankle', 0)}


def signature(frames, exercise):
    key, minimum = SIGNALS[exercise]
    if exercise == '플랭크':
        xs = [f['features'][key] for f in frames if key in f['features']]
        return {(key, 'HOLD'): median(xs)} if len(xs) >= 8 else {}
    usable = [f for f in frames if key in f['features'] and
              (key != 'wrist_shoulder_d' or f['features'][key] >= .1)]
    if len(usable) < 6 or len(usable) < len(frames) * .8:
        return {}
    low, high = min(f['features'][key] for f in usable), max(f['features'][key] for f in usable)
    if high - low < minimum:
        return {}
    features = [key]
    if exercise in ('푸시업', '니푸쉬업'):
        features.append('hip_dev_ankle' if exercise == '푸시업' else 'hip_dev_knee')
    if exercise in ('푸시업', '니푸쉬업', '힙쓰러스트', '시저크로스', '라잉 레그 레이즈'):
        features.append('head_trunk_ang')
    result = {}
    for phase in ('LOW', 'HIGH'):
        group = [f for f in usable if (f['features'][key] <= low + .15 * (high-low) if phase == 'LOW'
                                      else f['features'][key] >= high - .15 * (high-low))]
        for feature in features:
            values = [f['features'][feature] for f in group if feature in f['features']]
            if len(values) >= 2 and len(values) >= len(group) * .8:
                result[(feature, phase)] = median(values)
    if (key, 'LOW') in result and (key, 'HIGH') in result:
        result[(key, 'RANGE')] = result[(key, 'HIGH')] - result[(key, 'LOW')]
    return result


def main():
    manifest = json.loads((OUT / 'manifest.json').read_text(encoding='utf-8'))
    sequences = {s['id']: s for s in manifest['sequences']}
    raw = (OUT / 'results.jsonl').read_bytes()
    results = [json.loads(line) for line in raw.splitlines() if line.strip()]
    complete = json.loads((OUT / 'complete.json').read_text())
    assert len(results) == len(sequences) == complete['processed']
    assert {r['id'] for r in results} == set(sequences)
    assert all(r['manifest_sha256'] == complete['manifest_sha256'] for r in results)
    buckets = defaultdict(list)
    fixture = []
    for result in results:
        seq = sequences[result['id']]
        if not seq['conditions'] or not all(v is True for v in seq['conditions'].values()):
            continue
        sig = signature(result['frames'], result['exercise'])
        for (feature, phase), value in sig.items():
            buckets[(result['exercise'], seq['view'], feature, phase)].append((value, seq['clip_id'], seq['performer']))
        if result['exercise'] != '플랭크' and sig:
            fixture.append({'id': seq['id'], 'exercise': result['exercise'], 'frames': [f['features'] for f in result['frames']],
                            'signature': {f'{k}|{p}': v for (k, p), v in sig.items()}})
    rows = ['# trex.normal_pose_reference/1', '# source=Android VIDEO / full / SM-N976N / GPU / AIHub Training',
            '# 정상 표본의 관측 최솟값~최댓값. 표본이 작으며 자세 정오·음성·점수 임계값으로 사용하지 않는다.',
            '# 클립 내 양 끝 15% 구간의 중앙값. 실제 반복 시각 정답 없음. 운동 범위의 참고 비교용.',
            '# exercise\tview\tfeature\tphase\tlower\tupper\tclips\tpeople\tsource']
    for key, values in sorted(buckets.items()):
        clips = {v[1] for v in values}
        if len(clips) < 2:
            continue
        rows.append('\t'.join([*key, f'{min(v[0] for v in values):.7f}', f'{max(v[0] for v in values):.7f}',
                               str(len(clips)), str(len({v[2] for v in values})), 'android_video_training']))
    ASSET.write_text('\n'.join(rows) + '\n', encoding='utf-8')
    (OUT / 'normal_reference_provenance.json').write_text(json.dumps({
        'results_sha256': hashlib.sha256(raw).hexdigest(), 'manifest_sha256': complete['manifest_sha256'],
        'bands': len(rows)-5, 'training_reuse': True, 'normal_only': True,
        'limitations': ['small sample', 'camera-letter selected by user for floor', 'clip extremes, no rep-time truth']}, indent=2), encoding='utf-8')
    # 실제 입력/기대값을 보존해 Python 생성기와 Kotlin 단계 계산의 수치 일치를 검사한다.
    target = HERE.parents[1] / 'app/src/test/resources/comparison_reference_fixture.tsv'
    lines = []
    selected = defaultdict(int)
    for case in fixture:
        if selected[case['exercise']] >= 2:
            continue
        selected[case['exercise']] += 1
        lines.append('CASE\t' + case['exercise'])
        for frame in case['frames']:
            lines.append('FRAME\t' + '\t'.join(f'{k}={v}' for k, v in sorted(frame.items())))
        for key, value in sorted(case['signature'].items()):
            lines.append(f'EXPECT\t{key}\t{value}')
    target.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print(f'Exported {len(rows)-5} reference bands to {ASSET}')


if __name__ == '__main__':
    main()
