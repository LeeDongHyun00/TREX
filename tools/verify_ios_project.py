"""Swift 문법·Xcode 정본·번들 의존성을 Windows/Mac에서 검사한다. Xcode 빌드의 대체 검사가 아니다."""
import argparse, json, plistlib, subprocess, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

def main():
    import yaml
    from tree_sitter import Language,Parser
    import tree_sitter_swift
    parser=Parser(Language(tree_sitter_swift.language()))
    errors=[]; swift=[path for path in (ROOT/'iosApp').rglob('*.swift') if not any(part in {'Pods','TrexPods','DerivedData','build'} for part in path.parts)]
    for path in swift:
        tree=parser.parse(path.read_bytes())
        if tree.root_node.has_error: errors.append(str(path.relative_to(ROOT)))
    assert not errors, 'Swift 구문 오류: '+str(errors)
    spec=yaml.safe_load((ROOT/'iosApp/project.yml').read_text(encoding='utf-8'))
    target=spec['targets']['Trex']; sources=target['sources']
    for entry in sources:
        path=ROOT/'iosApp'/entry['path']; assert path.exists(), str(path)
    assert len([x for x in sources if x['path'].endswith('.task')])==1
    assert len([x for x in sources if x['path'].endswith('.tflite')])==3
    assert sum(x['path'].endswith('CaptureController.swift') for x in sources)==1
    assert 'resources' not in target, 'XcodeGen은 sources의 buildPhase를 쓴다'
    info=plistlib.loads((ROOT/'iosApp/Trex/Info.plist').read_bytes())
    for key in ['NSCameraUsageDescription','NSMotionUsageDescription','NSPhotoLibraryUsageDescription']: assert info[key]
    assert info['UISupportedInterfaceOrientations']==['UIInterfaceOrientationPortrait']
    for command in ['sync_ios_core.py','export_ios_resources.py','prepare_ios_food_model.py']:
        subprocess.run([sys.executable,str(ROOT/'tools'/command),'--check'],check=True)
    output=dict(swiftSyntaxFiles=len(swift),assetPaths=len(sources),plist=True,sourceSync=True,
        xcodeBuild='NOT_CHECKED_BY_THIS_SCRIPT',iphoneRun='NOT_CHECKED_BY_THIS_SCRIPT',ipa='NOT_CHECKED_BY_THIS_SCRIPT')
    print(json.dumps(output,ensure_ascii=False,indent=2))
    path=ROOT/'outputs/ios-port/static-verification.json';path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(output,indent=2)+'\n')
if __name__=='__main__': main()
