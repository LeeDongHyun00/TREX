"""차렷 자세로 구운 앱 메시의 국소 표면 보정. 리그 원본은 보존한다.

항상 export_atlas.py의 원본 베이크 결과에 한 번 적용한다. 정점/면 수,
오브젝트 이름과 근육 식별자를 유지하며 다른 근육은 변경하지 않는다.
"""
import base64
import numpy as np


def smoothstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)


def polish_surfaces(payload):
    report = []
    for part in payload['parts']:
        if part['group'] != 'hand_skin' and part['name'] != '연결층 · 몸통과 목':
            continue
        v = np.frombuffer(base64.b64decode(part['p']), '<i2').reshape(-1, 3).astype(float) / payload['scale']
        original = v.copy()
        if part['group'] == 'hand_skin':
            # MakeHuman 손의 동일한 토폴로지: 중지/약지 본체 및 세분 정점.
            # 약지 원위부가 접혀 있으므로 단순 평활화 대신 정상 단면을 전사한다.
            assert len(v) == 1597, '손 토폴로지가 바뀌면 대응 정점을 다시 검토해야 한다.'
            middle = np.r_[192:384, 857:896, 1013:1052]
            ring = np.r_[384:576, 896:935, 1052:1091]
            fit = v[ring, 1] > .716
            a, b = v[middle][fit], v[ring][fit]
            ca, cb = a.mean(axis=0), b.mean(axis=0)
            u, s, vt = np.linalg.svd((a - ca).T @ (b - cb))
            rotation = u @ vt
            assert np.linalg.det(rotation) > .999, '반사 전사는 허용하지 않는다.'
            scale = s.sum() / ((a - ca) ** 2).sum()
            restored = (v[middle] - ca) @ rotation * scale + cb
            # 정상 근위부는 보존하고 둘째 마디부터 손끝까지 점진적으로 복원.
            influence = 1 - smoothstep(.716, .744, original[ring, 1])
            v[ring] += (restored - v[ring]) * influence[:, None]
        else:
            # 검토된 연결층 하단(.775)~골반(.94) 사이만 매끈하게 접는다.
            # 둔근 메시 자체를 줄이거나 피부 조각을 덧대지 않는다.
            lower = 1 - smoothstep(.79, .94, v[:, 1])
            center = 1 - smoothstep(.025, .09, np.abs(v[:, 0]))
            back = 1 - smoothstep(-.045, .035, v[:, 2])
            influence = lower * center * back
            v[:, 1] += .084 * lower * center
            v[:, 2] += .043 * influence
            v[:, 0] *= 1 - .27 * influence
        encoded = np.rint(v * payload['scale']).astype('<i2')
        part['p'] = base64.b64encode(encoded.tobytes()).decode()
        displacement = np.linalg.norm(encoded / payload['scale'] - original, axis=1)
        report.append(dict(name=part['name'], changedVertices=int((displacement > 0).sum()),
                           maxDisplacement=float(displacement.max())))
    assert len(report) == 3, '보정 대상은 몸통 연결층과 양손 피부 3개여야 한다.'
    return report
