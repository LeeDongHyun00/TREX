package com.trex.server.share;

import com.trex.server.config.ShareProperties;
import com.trex.server.dto.SharedExerciseDto;
import com.trex.server.dto.SharedRoutineResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 공유 링크 웹 페이지(설계 §4) — 카카오톡 카드 메타, 운동 앞 3개, 기기별 "앱에서 열기"·"앱 설치하기", 사용자 글자 이스케이프. */
class SharePagesTest {

    private static ShareProperties props() {
        return new ShareProperties(null, null, null);
    }

    private static SharedRoutineResponse routine(String name, int count, int seconds, String nickname) {
        SharedExerciseDto e = new SharedExerciseDto("기본 스쿼트", "3세트 x 12회", "5분", true, "하체", null, null, 60, "repetitions", 12);
        return new SharedRoutineResponse(name, nickname, seconds, Collections.nCopies(count, e), Instant.EPOCH);
    }

    @Test
    void deviceFromUserAgent() {
        assertEquals("android", SharePages.device("Mozilla/5.0 (Linux; Android 15; SM-F956N) KAKAOTALK"));
        assertEquals("ios", SharePages.device("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)"));
        assertEquals("other", SharePages.device(null));
    }

    @Test
    void routinePageHasCardMetaAndPreview() {
        String html = SharePages.routine(routine("하체 불태우기", 5, 1500, "윤성"), "Ab3dE6gH", "android", props());
        assertTrue(html.contains("<meta property=\"og:title\" content=\"하체 불태우기\""), html);
        assertTrue(html.contains("운동 5개 · 약 25분 · 윤성님의 루틴"));
        assertTrue(html.contains("og:image\" content=\"https://trex-api.duckdns.org/share/og.png\""));
        assertTrue(html.contains("og:url\" content=\"https://trex-api.duckdns.org/r/Ab3dE6gH\""));
        assertTrue(html.contains("…2개 더"));
        assertTrue(html.contains("intent://r/Ab3dE6gH#Intent;scheme=trex;package=com.example.trex_kotlin;S.browser_fallback_url="));
        assertTrue(html.contains("https://github.com/LeeDongHyun00/TREX/releases/latest"));
    }

    @Test
    void iosGetsSchemeLinkAndPendingStoreText() {
        String html = SharePages.routine(routine("하체", 1, 60, "윤성"), "Ab3dE6gH", "ios", props());
        assertTrue(html.contains("href=\"trex://r/Ab3dE6gH\""));
        assertTrue(html.contains("App Store 출시 준비 중"));
        assertFalse(html.contains("intent://"));
    }

    @Test
    void routinePageEscapesUserText() {
        String html = SharePages.routine(routine("<b>\"하체\" & '불'</b>", 1, 60, "<x>"), "Ab3dE6gH", "other", props());
        assertFalse(html.contains("<b>\"하체\""));
        assertTrue(html.contains("&lt;b&gt;&quot;하체&quot; &amp; &#39;불&#39;&lt;/b&gt;"), html);
        assertFalse(html.contains("<x>"));
    }

    @Test
    void postPageShowsNicknameOnly() {
        String html = SharePages.post(42, "윤성", "android", props());
        assertTrue(html.contains("윤성님의 T-REX 커뮤니티 글"));
        assertTrue(html.contains("T-REX 앱에서 볼 수 있어요"));
        assertTrue(html.contains("intent://p/42#Intent;scheme=trex;"));
    }

    @Test
    void notFoundPage() {
        String html = SharePages.notFound("볼 수 없는 글이에요", "other", props());
        assertTrue(html.contains("볼 수 없는 글이에요"));
        assertTrue(html.contains("<meta property=\"og:title\" content=\"볼 수 없는 글이에요\""));
    }
}
