"""iOS TFLite C API용으로 YOLO의 외부 가중치를 inline Buffer.data에 보관한다.

연산·텐서·양자화·메타데이터는 재직렬화하지 않는다. 원본 뒤에 새 버퍼 배열을
붙이고 Model.buffers의 포인터 4바이트만 바꾼다. 원본 모델은 Android에서 유지한다.
"""
import argparse
import hashlib
import json
import struct
from pathlib import Path

import flatbuffers
import tflite

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/assets/models/yolov8n_food.tflite'
TARGET = ROOT / 'iosApp/TrexFoodModels/yolov8n_food.tflite'
MANIFEST = ROOT / 'iosApp/Trex/Resources/FOOD_MODEL_BASELINE.json'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def buffer_bytes(buffer, raw):
    if buffer.Offset() > 1:
        assert buffer.DataIsNone(), '외부와 inline 데이터가 동시에 있는 버퍼는 지원하지 않습니다.'
        start, size = buffer.Offset(), buffer.Size()
        assert size > 0 and start + size <= len(raw), '외부 가중치 범위를 벗어났습니다.'
        return raw[start:start + size]
    return None if buffer.DataIsNone() else buffer.DataAsNumpy().tobytes()


def prepare(source):
    assert source[4:8] == b'TFL3'
    model = tflite.Model.GetRootAs(source, 0)
    # 외부 custom options는 이 모델에 없다. 그런 모델은 별도 명세 없이 변환하지 않는다.
    for i in range(model.SubgraphsLength()):
        graph = model.Subgraphs(i)
        assert all(graph.Operators(j).LargeCustomOptionsOffset() == 0 for j in range(graph.OperatorsLength()))
    slot = model._tab.Pos + model._tab.Offset(12)  # schema Model.buffers (필드 4)
    assert model._tab.Offset(12) > 0
    builder = flatbuffers.Builder(0)
    offsets, weights, external = [], [], 0
    for i in range(model.BuffersLength()):
        buffer = model.Buffers(i)
        vtable = buffer._tab.Pos - struct.unpack_from('<i', source, buffer._tab.Pos)[0]
        assert struct.unpack_from('<H', source, vtable)[0] <= 10, '새 Buffer 필드의 보존 정책이 필요합니다.'
        data = buffer_bytes(buffer, source)
        external += buffer.Offset() > 1
        vector = None
        if data is not None:
            # schema Buffer.data의 force_align:16을 유지한다.
            builder.StartVector(1, len(data), 16)
            builder.head -= len(data)
            builder.Bytes[builder.head:builder.head + len(data)] = data
            vector = builder.EndVector()
        tflite.BufferStart(builder)
        if vector is not None:
            tflite.BufferAddData(builder, vector)
        offsets.append(tflite.BufferEnd(builder))
        weights.append(dict(index=i, size=0 if data is None else len(data), sha256=None if data is None else digest(data)))
    tflite.ModelStartBuffersVector(builder, len(offsets))
    for offset in reversed(offsets):
        builder.PrependUOffsetTRelative(offset)
    builder.Finish(builder.EndVector())
    suffix = bytes(builder.Output())
    output = bytearray(source)
    output.extend(b'\0' * (-len(output) % 16))
    new_vector = len(output) + struct.unpack_from('<I', suffix, 0)[0]
    output.extend(suffix)
    struct.pack_into('<I', output, slot, new_vector - slot)
    output = bytes(output)
    # 그래프/텐서/옵션/양자화/메타데이터를 포함한 원본 바이트는 포인터 외 전부 동일하다.
    assert output[:slot] == source[:slot] and output[slot + 4:len(source)] == source[slot + 4:]
    rebuilt = tflite.Model.GetRootAs(output, 0)
    assert rebuilt.BuffersLength() == model.BuffersLength()
    for i in range(rebuilt.BuffersLength()):
        buffer = rebuilt.Buffers(i)
        assert buffer.Offset() == 0 and buffer.Size() == 0
        assert buffer_bytes(buffer, output) == buffer_bytes(model.Buffers(i), source), f'가중치 변경: {i}'
        if not buffer.DataIsNone():
            assert buffer._tab.Vector(buffer._tab.Offset(4)) % 16 == 0
    manifest = dict(source=str(SOURCE.relative_to(ROOT)).replace('\\', '/'), sourceSha256=digest(source),
                    bundledName=TARGET.name, bundledSha256=digest(output), originalSize=len(source), bundledSize=len(output),
                    externalBuffers=external, buffers=len(weights), originalBytesPreservedExceptBuffersPointer=True,
                    weights=weights)
    return output, manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    check = parser.parse_args().check
    output, manifest = prepare(SOURCE.read_bytes())
    manifest_text = json.dumps(manifest, ensure_ascii=False, indent=2) + '\n'
    if check:
        assert TARGET.read_bytes() == output, 'iOS 음식 모델을 다시 생성하세요.'
        assert MANIFEST.read_text(encoding='utf-8') == manifest_text, 'iOS 음식 모델 기준표를 다시 생성하세요.'
    else:
        TARGET.parent.mkdir(parents=True, exist_ok=True)
        TARGET.write_bytes(output)
        MANIFEST.write_text(manifest_text, encoding='utf-8', newline='\n')
    print(f'YOLO 버퍼 {manifest["buffers"]}개 · 외부 {manifest["externalBuffers"]}개 inline 전환 · 가중치/원본 그래프 보존 검사 통과')


if __name__ == '__main__':
    main()
