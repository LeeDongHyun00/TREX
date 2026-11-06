package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * 말투 변환 최적화(스펙 §115) — 표·정규식을 한 번만 만들고 바꿀 것 없는 글자를 바로 돌려줘도
 * 결과가 예전 구현과 한 글자도 다르지 않아야 한다. 화면·음성 문장이 조용히 바뀌면 안 된다.
 */
class DinoCopyEquivalenceTest {
    /** 최적화 전 구현을 그대로 옮겨 둔 기준 — 고치지 않는다. */
    private fun String.reference(): String {
        val endings = listOf(
            "하겠습니다" to "할게룡", "되었습니다" to "됐어룡", "했습니다" to "했어룡",
            "가려졌습니다" to "가려졌어룡", "나타납니다" to "나타나룡", "덮어씁니다" to "덮어써룡",
            "만듭니다" to "만들어룡", "버팁니다" to "버텨룡", "지웠습니다" to "지웠어룡",
            "보입니다" to "보여룡", "아닙니다" to "아니에룡", "높아집니다" to "높아져룡",
            "있습니다" to "있어룡", "없습니다" to "없어룡", "않습니다" to "않아룡",
            "바랍니다" to "바라룡", "드립니다" to "드려룡", "됩니다" to "돼룡",
            "합니다" to "해룡", "입니다" to "이에룡", "하십시오" to "하세룡",
            "마십시오" to "마세룡", "주세요" to "주세룡",
        )
        var result = this
        endings.forEach { (from, to) -> result = result.replace(from, to) }
        val nouns = setOf("필요", "주요", "중요", "수요", "개요", "요요", "민요", "동요", "가요")
        return result.replace(Regex("([가-힣]+)요(?=[\\s.!?…。·—,:;\\)\\]▴▾]|$)")) {
            if (it.value in nouns) it.value else it.groupValues[1] + "룡"
        }.replace(Regex("\\.+(?=\\s|$|[\"'”’)])"), "")
    }

    @Test fun everyStringLiteralInTheAppConvertsExactlyAsBefore() {
        // 앱 코드의 문자열 리터럴 전부 — 실제로 화면·음성에 나가는 문장들. iOS 공통 모듈(posture-core)로 옮겨 돌 때도 앱 소스를 읽는다
        val literal = Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"")
        val root = listOf(File("src/main/java"), File("../app/src/main/java")).first { File(it, "com/example/trex_kotlin").isDirectory }
        val sources = root.walkTopDown().filter { it.extension == "kt" }.toList()
        val strings = sources.flatMap { f -> literal.findAll(f.readText()).map { it.groupValues[1] }.toList() }.toSet()
        check(sources.size > 50 && strings.size > 1000) { "소스를 못 찾았다: ${sources.size}개 파일, ${strings.size}개 문자열" }
        strings.forEach { assertEquals(it, it.reference(), it.toDinoCopy()) }
    }

    @Test fun randomMixturesOfEndingsNounsAndPunctuationConvertExactlyAsBefore() {
        val pieces = listOf(
            "니다", "습니다", "합니다", "입니다", "주세요", "주세", "요", "필요", "주요", "가요", "요요", "시오", "하십시오", "십시",
            ".", "..", "3.5", "v1.2", " ", "\n", "!", "?", "…", "·", "—", ",", ":", ";", ")", "]", "▴", "▾", "\"", "'", "”", "’",
            "해", "돼", "룡", "가", "a", "1", "중", "운동", "공룡", "있어", "세트",
        )
        val random = Random(115)
        repeat(20_000) {
            val text = buildString { repeat(random.nextInt(1, 10)) { append(pieces[random.nextInt(pieces.size)]) } }
            assertEquals(text, text.reference(), text.toDinoCopy())
        }
    }
}
