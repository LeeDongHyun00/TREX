package com.example.trex_kotlin.posture

/** 화면·음성의 문장 어미만 바꾼다. 판정 원문·로그·숫자·단위·항목명은 그대로 둔다. */
fun String.toDinoCopy(): String {
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
    // 문장 안에서 끝난 어미도 처리한다. 소수점·측정값·기존 공룡 말투는 건드리지 않는다.
    val nouns = setOf("필요", "주요", "중요", "수요", "개요", "요요", "민요", "동요", "가요")
    return result.replace(Regex("([가-힣]+)요(?=[\\s.!?…。·—,:;\\)\\]▴▾]|$)")) {
        if (it.value in nouns) it.value else it.groupValues[1] + "룡"
    }.replace(Regex("\\.+(?=\\s|$|[\"'”’)])"), "")
}
