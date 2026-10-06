"""Android 정본의 플랫폼 독립 로직을 iOS 공통 모듈로 동기화한다. --check는 변경 누락을 검사한다."""
import argparse, hashlib, json, re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/java/com/example/trex_kotlin/posture'
TARGET = ROOT / 'posture-core/src/commonMain/kotlin/com/example/trex_kotlin/posture'
NAMES = '''Arm2d Breathing CameraPlacement CapturePreparation ContactMinimum DinoCopy ExerciseProfiles FloorFeedback FloorTemporal FormMotion LegCycle Lunge2d LungeSides NormalPoseReference PlankAlignment PostureAssessment PostureComparison PostureCore PostureFloor PostureFloorCoverage PostureScope PostureSetReport PostureView PostureViewGuide RepCounter RepForm RepFormPriorTable RepFormRules RepHysteresis RepUnit ReturnRepTracker RuleHighlight RuleTypes Stance2d PostureRules PostureBaseline PostureCoach PostureMode'''.split()

def portable(name, text):
    text = re.sub(r'^import (android\..*|java\..*|org.json.JSONObject)\n', '', text, flags=re.M)
    if name == 'PostureRules':
        old = 'fun load(context: Context, assetPath: String = ASSET_PATH): PostureRuleSet {\n            val text = context.assets.open(assetPath).bufferedReader().use { it.readText() }'
        assert old in text
        text = text.replace(old, 'fun loadText(text: String): PostureRuleSet {')
    if name == 'PostureBaseline':
        text = text[:text.index('class BaselineStore(')]
        text = text.replace('SetLog.nowIso()', '""')
    if name == 'PostureCoach': text = text[:text.index('class SpeechCoach(')]
    if name == 'PostureMode': text = text[:text.index('class ModeStore(')]
    if name == 'PostureCore':
        # 피처 맵은 null이 없는 Float 값이다. JVM Map 기본 메서드를 공통 API로 치환한다.
        text, count = re.subn(r'(?m)^(\s*)f\.putIfAbsent\((".*?"), (.+)\)$', r'\1f.getOrPut(\2) { \3 }', text)
        assert count == 5, 'PostureCore의 플랫폼 전용 맵 호출 수를 확인하세요.'
    text = text.replace('String.format(java.util.Locale.US, ', 'PortableFormat.format(')
    text = text.replace('String.format(Locale.US, ', 'PortableFormat.format(')
    text = text.replace('String.format(', 'PortableFormat.format(')
    # iOS 호출자는 직렬 큐 한 곳에서만 접근한다. JVM 모니터를 공통 코드로 옮기지 않는다.
    text = re.sub(r'@(?:Synchronized|Volatile)\s*', '', text)
    assert not re.search(r'^import (android|java|org.json)|System\.|@Synchronized|@Volatile', text, re.M), name
    return '// tools/sync_ios_core.py 생성본 — Android 정본에서 수정한 뒤 동기화하세요.\n' + text.rstrip() + '\n'

def main():
    check = argparse.ArgumentParser(); check.add_argument('--check', action='store_true'); args = check.parse_args()
    manifest = {}; mismatches = []
    TARGET.mkdir(parents=True, exist_ok=True)
    for name in NAMES:
        src = SOURCE / (name + '.kt'); original = src.read_text(encoding='utf-8'); output = portable(name, original)
        target = TARGET / src.name
        if args.check:
            if not target.exists() or target.read_text(encoding='utf-8') != output: mismatches.append(name)
        else: target.write_text(output, encoding='utf-8', newline='\n')
        # Git 정본의 LF 기준. Windows autocrlf로 체크아웃한 바이트를 Mac과 혼동하지 않는다.
        manifest[name] = hashlib.sha256(original.encode('utf-8')).hexdigest()
    mp = ROOT / 'posture-core/source-manifest.json'; expected = json.dumps(manifest, indent=2) + '\n'
    orientation = (SOURCE/'PostureOrientation.kt').read_text(encoding='utf-8')
    orientation = re.sub(r'^import android\..*\n', '', orientation, flags=re.M)
    orientation = orientation[:orientation.index('class GravityTracker(')].replace('Surface.', 'SurfaceRotations.')
    orientation += '\ninternal object SurfaceRotations { const val ROTATION_0=0; const val ROTATION_90=1; const val ROTATION_180=2; const val ROTATION_270=3 }\n'
    log = (SOURCE/'PostureSetLog.kt').read_text(encoding='utf-8')
    log = log[log.index('data class RepEngineLog('):log.index('/**\n * 세트 중 카운터의 진행 사이클')]
    for name, output in [('PortableOrientation.kt', orientation), ('RepEngineLog.kt', 'package com.example.trex_kotlin.posture\n\n' + log.rstrip() + '\n')]:
        target = TARGET/name
        if args.check:
            if not target.exists() or target.read_text(encoding='utf-8') != output: mismatches.append(name)
        else: target.write_text(output,encoding='utf-8',newline='\n')
    for package, name in [('trainingload', 'MuscleLoad'), ('food', 'FoodRegions'), ('food', 'FoodMemory')]:
        src = ROOT / 'app/src/main/java/com/example/trex_kotlin' / package / (name + '.kt')
        target = TARGET.parent / package / src.name
        output = '// Android 정본 동기화 — tools/sync_ios_core.py\n' + src.read_text(encoding='utf-8')
        if name == 'FoodMemory':
            output = re.sub(r'^import java\..*\n', '', output, flags=re.M)
            output = output.replace('class FoodMemory(', '@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)\nclass FoodMemory(')
            output = output.replace('val buf = ByteBuffer.allocate(e.vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)\n        e.vector.forEach { buf.putFloat(it) }', 'val bytes = ByteArray(e.vector.size * 4)\n        e.vector.forEachIndexed { i, f -> val bits = f.toBits(); for (b in 0..3) bytes[i*4+b] = (bits ushr (8*b)).toByte() }')
            output = output.replace('Base64.getEncoder().encodeToString(buf.array())', 'kotlin.io.encoding.Base64.encode(bytes)')
            output = output.replace('Base64.getDecoder().decode(parts[2])', 'kotlin.io.encoding.Base64.decode(parts[2])')
            output = output.replace('val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)\n                val v = FloatArray(bytes.size / 4) { buf.getFloat() }', 'val v = FloatArray(bytes.size / 4) { i -> Float.fromBits((0..3).fold(0) { bits, b -> bits or ((bytes[i*4+b].toInt() and 255) shl (8*b)) }) }')
            assert 'ByteBuffer' not in output and 'java.' not in output
        if args.check:
            if not target.exists() or target.read_text(encoding='utf-8') != output: mismatches.append(name)
        else:
            target.parent.mkdir(parents=True, exist_ok=True); target.write_text(output, encoding='utf-8', newline='\n')
    tests = ROOT / 'app/src/test/java/com/example/trex_kotlin/posture'
    target_tests = ROOT / 'posture-core/src/jvmTest/kotlin/com/example/trex_kotlin/posture'
    excluded = {'SetLabelStoreTest', 'PostureSetLogTest', 'InferencePolicyTest', 'PostureOrientationTest', 'PersonalizationTest', 'PostureBaselineTest'}
    for src in tests.glob('*Test.kt'):
        if src.stem in excluded: continue
        output = '// Android 정본 테스트 — tools/sync_ios_core.py\n' + src.read_text(encoding='utf-8')
        output = output.replace('android.view.Surface.', 'SurfaceRotations.')
        target = target_tests / src.name
        if args.check:
            if not target.exists() or target.read_text(encoding='utf-8') != output: mismatches.append(src.stem)
        else:
            target_tests.mkdir(parents=True, exist_ok=True); target.write_text(output, encoding='utf-8', newline='\n')
    if args.check:
        if not mp.exists() or mp.read_text() != expected: mismatches.append('source-manifest')
        if mismatches: raise SystemExit('동기화 필요: ' + ', '.join(mismatches))
    else: mp.write_text(expected)
    print(f'{len(NAMES)}개 엔진 정본 동기화 ' + ('검사 통과' if args.check else '완료'))

if __name__ == '__main__': main()
