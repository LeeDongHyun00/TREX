"""공통 2D 뷰어의 실제 DOM·색상·반응형 배치를 검증한다. pip install playwright 필요.

python3 tools/test_muscle_atlas_2d.py --screenshots outputs/ui-review
"""
import argparse
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
URL = (ROOT / 'app/src/main/assets/muscle_load/index.html').as_uri()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--screenshots', type=Path)
    args = parser.parse_args()
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True, args=['--no-sandbox'])
        page = browser.new_page(viewport={'width': 390, 'height': 400}, device_scale_factor=2)
        errors = []
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.goto(URL)
        page.wait_for_selector('body[data-ready="true"]')
        assert page.locator('figure').evaluate_all('(nodes) => nodes.map(n => n.dataset.view)') == ['back', 'front']
        assert page.locator('canvas').count() == 0
        assert page.locator('script[src*="three"]').count() == 0
        keys = page.locator('[data-muscle]').evaluate_all('(nodes) => [...new Set(nodes.map(n => n.dataset.muscle))]')
        assert len(keys) == 36, keys  # 18근육 × 신체 좌우
        def fill(key):
            return page.locator(f'[data-muscle="{key}"]').first.get_attribute('fill')
        neutral = fill('QUADS_L')
        page.evaluate("window.setFatigue({QUADS_L:90,QUADS_R:70,LATS_L:45,LATS_R:45,PECS_L:25,PECS_R:25},'QUADS')")
        assert fill('QUADS_L') != fill('QUADS_R') != neutral
        assert fill('BICEPS_L') == neutral
        assert page.locator('[data-muscle="QUADS_L"]').first.get_attribute('stroke-width') == '2.5'
        assert page.locator('[data-muscle="LATS_L"]').first.get_attribute('stroke-width') == '.65'
        for width, height in [(320, 366), (390, 400), (768, 400)]:
            page.set_viewport_size({'width': width, 'height': height})
            for box in page.locator('svg').all():
                bounds = box.bounding_box()
                assert 0 <= bounds['x'] and bounds['x'] + bounds['width'] <= width
                assert 0 <= bounds['y'] and bounds['y'] + bounds['height'] <= height
        if args.screenshots:
            args.screenshots.mkdir(parents=True, exist_ok=True)
            page.set_viewport_size({'width': 390, 'height': 400})
            page.screenshot(path=str(args.screenshots / 'muscles-2d.png'))
        page.evaluate('window.setFatigue({}, "")')
        assert fill('QUADS_L') == neutral
        page.goto(URL + '?mode=session')
        page.wait_for_selector('body[data-ready="true"]')
        page.evaluate("window.setUsedMuscles(['BICEPS_L'])")
        assert fill('BICEPS_L') != neutral and fill('BICEPS_R') == neutral
        page.evaluate("window.setFatigue({BICEPS_R:100},'BICEPS')")
        assert fill('BICEPS_R') == neutral  # 완료 화면은 누적 피로도가 아닌 이 세션 사용 부위만 표시
        page.evaluate('window.setUsedMuscles([])')
        assert fill('BICEPS_L') == neutral
        assert not errors, errors
        browser.close()
    print('2D 뷰어: 좌우 배치·36개 식별자·피로도 색상·선택·세션 분리·3개 화면 크기 통과')


if __name__ == '__main__':
    main()
