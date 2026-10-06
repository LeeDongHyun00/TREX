"""iOS 카탈로그/가이드/영양 DB와 자산 SHA 기준을 Android 정본에서 추출한다."""
import hashlib, json, re, argparse
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/example/trex_kotlin'
OUT = ROOT / 'iosApp/Trex/Resources'

def strings(text): return [json.loads('"' + x + '"') for x in re.findall(r'"((?:[^"\\]|\\.)*)"', text)]
def main():
    p = argparse.ArgumentParser(); p.add_argument('--check', action='store_true'); check = p.parse_args().check
    files = {}
    catalog = []
    for name, reps, duration, category in re.findall(r'WorkoutTemplate\("([^"]+)", "([^"]+)", "([^"]+)", "([^"]+)", true\)', (JAVA/'Sheets.kt').read_text(encoding='utf-8')):
        numbers = [int(x) for x in re.findall(r'\d+', reps)]
        catalog.append(dict(name=name, category=category, repetitions=numbers[0], sets=numbers[-1], hold='초' in reps))
    assert len(catalog) == 26
    files['catalog.json'] = catalog
    guides = []
    for chunk in (JAVA/'ExerciseGuides.kt').read_text(encoding='utf-8').split('ExerciseGuide(')[2:]:
        head = strings(chunk.split('setup =')[0]); guide = dict(zip(['name', 'asset', 'comment'], head))
        for field in ['setup', 'steps', 'cautions']:
            guide[field] = strings(re.search(field+r' = listOf\((.*?)\)', chunk, re.S).group(1))
        for field in ['breathing', 'sourceName', 'sourceUrl']:
            guide[field] = strings(re.search(field+r' = ("(?:[^"\\]|\\.)*")', chunk).group(1))[0]
        guides.append(guide)
    assert len(guides) == 7
    files['guides.json'] = guides
    nutrition = []
    for name,kcal,carbs,protein,fat in re.findall(r'"([^"]+)" to Nutrition\((\d+), ([\d.]+), ([\d.]+), ([\d.]+)\)', (JAVA/'TrexData.kt').read_text(encoding='utf-8')):
        nutrition.append(dict(name=name,kcal=int(kcal),carbs=float(carbs),protein=float(protein),fat=float(fat)))
    assert len(nutrition) > 300
    files['nutrition.json'] = nutrition
    assets = ['pose_landmarker_full.task','rules_mp_v0.json','rules_floor_v0.json','normal_pose_reference.tsv']
    def asset_hash(name):
        raw=(ROOT/'app/src/main/assets/posture'/name).read_bytes()
        # 모델은 원시 바이트, 텍스트는 Git 정본 LF. Mac 번들은 이 LF 바이트를 그대로 복사한다.
        if name.endswith(('.json','.tsv')): raw=raw.replace(b'\r\n',b'\n')
        return hashlib.sha256(raw).hexdigest()
    baseline = {'files': [dict(path='app/src/main/assets/posture/'+name,sha256=asset_hash(name)) for name in assets]}
    files['ASSET_BASELINE.json'] = baseline
    OUT.mkdir(parents=True, exist_ok=True)
    for name,obj in files.items():
        content = json.dumps(obj,ensure_ascii=False,indent=2)+'\n'; path = OUT/name
        if check:
            if not path.exists() or path.read_text(encoding='utf-8') != content: raise SystemExit('동기화 필요: '+name)
        else: path.write_text(content,encoding='utf-8',newline='\n')
    print(f'운동 {len(catalog)} · 가이드 {len(guides)} · 영양 {len(nutrition)} 정본 확인')
if __name__ == '__main__': main()
