package com.trex.server.community;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 금칙어 검사 — 띄어쓰기·문장부호·제로폭 문자·전각 문자로 피해 가지 못하게 정규화한 뒤 비교한다. */
class BannedWordsTest {

    private final BannedWords words = new BannedWords(List.of("바보", "babo"));

    @Test
    void 띄어쓰기_문장부호_제로폭_문자로_끊어도_잡는다() {
        assertTrue(words.contains("너 바 보 야"));
        assertTrue(words.contains("바.보"));
        assertTrue(words.contains("바\u200B보"));
    }

    @Test
    void 전각_대문자도_잡는다() {
        assertTrue(words.contains("ＢＡＢＯ"));
    }

    @Test
    void 금칙어가_없으면_통과() {
        assertFalse(words.contains("바나나 우유"));
        assertFalse(words.contains(""));
    }

    @Test
    void 기본_목록을_읽는다() {
        assertFalse(BannedWords.fromClasspath().contains("오늘 오운완 했어요"));
    }
}
