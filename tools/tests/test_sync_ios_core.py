"""저장소 선언의 서식 변경이 공통 코드 생성·플랫폼 분리를 깨지 않는지 검사한다."""
import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'sync_ios_core.py'
spec = importlib.util.spec_from_file_location('sync_ios_core', SCRIPT)
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


class StoreBoundaryTest(unittest.TestCase):
    def test_baseline_keeps_shared_logic_and_excludes_android_storage(self):
        source = (sync.SOURCE / 'PostureBaseline.kt').read_text(encoding='utf-8')
        start = sync.class_start(source, 'BaselineStore')
        expected = sync.portable('PostureBaseline', source)
        for declaration in ('class BaselineStore(', 'class BaselineStore (',
                            'class BaselineStore\n(', 'internal class BaselineStore('):
            with self.subTest(declaration=declaration):
                actual = sync.portable('PostureBaseline', source[:start] + declaration + 'private val file: File) {}')
                self.assertEqual(expected, actual)
                self.assertIn('class BaselineCollector', actual)
                self.assertNotIn('class BaselineStore', actual)
                self.assertNotIn('import android.', actual)

    def test_label_format_survives_both_constructor_styles(self):
        source = (sync.SOURCE / 'PostureSetLabel.kt').read_text(encoding='utf-8')
        start = sync.class_start(source, 'SetLabelStore')
        for declaration in ('class SetLabelStore(', 'class SetLabelStore ('):
            with self.subTest(declaration=declaration):
                variant = source[:start] + declaration + 'private val dir: File) {}'
                shared = variant[variant.index('data class SetSelfLabel('):sync.class_start(variant, 'SetLabelStore')]
                self.assertIn('object SetLabelFormat', shared)
                self.assertNotIn('private val dir', shared)

    def test_missing_declaration_does_not_copy_platform_code(self):
        for name in ('BaselineStore', 'SetLabelStore'):
            with self.subTest(name=name):
                with self.assertRaisesRegex(ValueError, name):
                    sync.class_start(f'// class {name}\nclass {name}Other {{}}', name)


if __name__ == '__main__':
    unittest.main()
