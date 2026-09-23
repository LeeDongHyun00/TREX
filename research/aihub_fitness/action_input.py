"""Android와 공유하는 선택 종목 독립 2D 입력. 시간/3D 정답을 만들어내지 않는다."""
from __future__ import annotations

import numpy as np

SCHEMA = "trex.action.pose2d/1"
FRAMES = 16
FEATURES = 33 * 4


def encode(xy, visibility, presence, width, height):
    xy = np.asarray(xy, np.float32).reshape(33, 2).copy()
    v = np.asarray(visibility, np.float32)
    p = np.asarray(presence, np.float32)
    out = np.zeros((33, 4), np.float32)
    if width <= 0 or height <= 0:
        return out.ravel()
    q = np.minimum(v, p)
    ok = np.isfinite(xy).all(axis=1) & np.isfinite(q) & (q >= .5)
    xy[:, 0] *= np.float32(width / height)
    hips = [j for j in (23, 24) if ok[j]]
    shoulders = [j for j in (11, 12) if ok[j]]
    if not hips or not shoulders or ok.sum() < 12:
        return out.ravel()
    root = xy[hips].mean(axis=0)
    shoulder = xy[shoulders].mean(axis=0)
    scale = float(np.linalg.norm(shoulder - root))
    if not np.isfinite(scale) or scale < .02:
        return out.ravel()
    out[ok, :2] = np.clip((xy[ok] - root) / scale, -4., 4.)
    out[ok, 2] = np.clip(q[ok], 0., 1.)
    out[ok, 3] = 1.
    return out.ravel()


def windows(sequence):
    """끝점 4/8/12/16의 과거만 사용. sparse 표본에 실제 시간을 부여하지 않음."""
    for end in (4, 8, 12, 16):
        item = np.zeros((FRAMES, FEATURES), np.float32)
        item[-end:] = sequence[:end]
        if np.count_nonzero(np.any(item != 0, axis=1)) >= 3:
            yield item
