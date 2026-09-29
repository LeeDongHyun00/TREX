"""사용자가 승인한 기존 TREX 업데이트 설치만 한다. 실행/화면 조작/테스트/삭제 명령 없음."""
from pathlib import Path
import subprocess,json,hashlib,tarfile,io,datetime,os
ROOT=Path(__file__).resolve().parents[2]
ADB=Path('C:/Users/hp276/AppData/Local/Android/Sdk/platform-tools/adb.exe')
APK=ROOT/'app/build/outputs/apk/debug/app-debug.apk'
OUT=ROOT/os.environ.get('TREX_VERIFY_OUTPUT','outputs/muscle-load');OUT.mkdir(parents=True,exist_ok=True)
PACKAGE='com.example.trex_kotlin'
def adb(*args):
    p=subprocess.run([str(ADB),*args],capture_output=True)
    if p.returncode:raise RuntimeError(p.stderr.decode(errors='replace')+p.stdout.decode(errors='replace'))
    return p.stdout
devices=[line.split()[0] for line in adb('devices').decode().splitlines()[1:] if len(line.split())>=2 and line.split()[1]=='device' and not line.startswith('emulator-')]
assert len(devices)==1, '업데이트 대상 휴대폰을 하나만 연결해 주세요'
serial=devices[0]
def device(*args): return adb('-s',serial,*args)
assert device('shell','pm','path',PACKAGE).decode().startswith('package:'), '기존 앱이 확인되지 않습니다'
def backup(label):
    raw=device('exec-out','run-as',PACKAGE,'tar','-cf','-','shared_prefs')
    (OUT/f'{label}-preferences.tar').write_bytes(raw)
    with tarfile.open(fileobj=io.BytesIO(raw),mode='r:') as archive:
        return {m.name:hashlib.sha256(archive.extractfile(m).read()).hexdigest() for m in archive.getmembers() if m.isfile()}
before=backup('before-update')
result=device('install','-r',str(APK)).decode().strip()
assert 'Success' in result,result
after=backup('after-update')
installed=device('shell','pm','path',PACKAGE).decode().strip().splitlines()[0].removeprefix('package:')
assert installed.startswith('/data/app/'), '설치된 APK 경로가 예상과 다릅니다'
installed_hash=device('shell','sha256sum',installed).decode().split()[0]
report={'package':PACKAGE,'result':result,'apkSHA256':hashlib.sha256(APK.read_bytes()).hexdigest(),
    'installedSHA256':installed_hash,'installedApkMatches':installed_hash==hashlib.sha256(APK.read_bytes()).hexdigest(),
    'preferencesUnchanged':before==after,'preferenceFiles':len(before),'beforeHashes':before,'afterHashes':after,
    'timestamp':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'adb install -r 업데이트 설치 및 저장 설정 보존 확인만. 앱 실행/실휴대폰 UI 검증 안 함.'}
(OUT/'deployment.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if k not in ['beforeHashes','afterHashes']},ensure_ascii=False))
