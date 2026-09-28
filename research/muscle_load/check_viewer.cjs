/* 피로도 페이지도 동일 드래그 뷰어를 사용한다. PC에서 실제 앱 자산만 검사한다. */
process.argv.push('--fatigue');
require('./check_session_viewer.cjs');
