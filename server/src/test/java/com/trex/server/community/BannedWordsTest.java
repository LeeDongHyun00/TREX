package com.trex.server.community;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 금칙어 검사 — 문장부호·제로폭 문자·전각 문자로 피해 가지 못하게 정규화한 뒤 비교한다. 단, 두 글자 단어와 영어 단어는
 * 띄어쓰기를 넘어 맞추지 않는다 — "스쿼트 시 팔은"이 막히면 안 된다(리뷰 2026-10-29).
 */
class BannedWordsTest {

    private final BannedWords words = new BannedWords(List.of("바보", "babo", "개새끼", "시팔", "존나", "shit"));

    @Test
    void 문장부호_제로폭_문자로_끊어도_잡는다() {
        assertTrue(words.contains("너 바보야"));
        assertTrue(words.contains("바.보"));
        assertTrue(words.contains("바​보"));
    }

    @Test
    void 세_글자_이상은_띄어_써도_잡는다() {
        assertTrue(words.contains("개 새 끼"));
    }

    @Test
    void 전각_대문자도_잡는다() {
        assertTrue(words.contains("ＢＡＢＯ"));
    }

    @Test
    void 평범한_운동_문장은_막지_않는다() {
        assertFalse(words.contains("스쿼트 시 팔은 앞으로"));
        assertFalse(words.contains("푸쉬업 시 팔꿈치를 붙여요"));
        assertFalse(words.contains("push it to the limit"));
        assertFalse(words.contains("보존 나중에 할게요"));
        assertFalse(words.contains("너 바 보 야"), "두 글자 단어는 띄어쓰기를 넘어 맞추지 않는다(오탐보다 낫다)");
    }

    @Test
    void 금칙어가_없으면_통과() {
        assertFalse(words.contains("바나나 우유"));
        assertFalse(words.contains(""));
    }

    @Test
    void 기본_목록을_읽고_운동_문장을_막지_않는다() {
        BannedWords defaults = BannedWords.fromClasspath();
        assertFalse(defaults.contains("오늘 오운완 했어요"));
        assertFalse(defaults.contains("스쿼트 시 팔은 앞으로 뻗어요"));
        assertTrue(defaults.contains("씨.발"));
    }
}
