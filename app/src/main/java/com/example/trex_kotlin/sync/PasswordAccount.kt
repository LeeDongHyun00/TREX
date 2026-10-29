package com.example.trex_kotlin.sync

/*
 * 아이디·비밀번호 가입·로그인(docs/SERVER_SYNC_HANDOFF.md D13) — 서버 SignupRequest 와 같은 규칙을 화면에서 먼저 본다.
 * 비밀번호 찾기는 아직 없다(가입 화면이 알린다). iPhone 도 같은 코드를 쓴다(ios-ui/android-sources.txt).
 */

private val LOGIN_ID = Regex("^[A-Za-z0-9_]{4,20}$")
private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

/** 회원가입 칸에 적은 값 그대로(다듬기 전). */
data class SignupForm(
    val name: String,
    val loginId: String,
    val password: String,
    val passwordConfirm: String,
    val email: String,
    val agreed: Boolean,
)

fun loginIdProblem(loginId: String): String? =
    if (LOGIN_ID.matches(loginId.trim())) null else "아이디는 영문·숫자·밑줄 4~20자예요"

fun passwordProblem(password: String): String? = when {
    password.length !in 8..64 -> "비밀번호는 8~64자예요"
    !password.any { it in 'A'..'Z' || it in 'a'..'z' } || !password.any { it in '0'..'9' } -> "비밀번호는 영문과 숫자를 섞어 주세요"
    else -> null
}

/** 가입 버튼을 누를 수 없는 까닭 — 위 칸부터 첫 문제 하나, 없으면 null. */
fun signupProblem(form: SignupForm): String? = when {
    form.name.isBlank() -> "이름을 적어 주세요"
    form.name.trim().length > 20 -> "이름은 20자까지예요"
    else -> loginIdProblem(form.loginId)
        ?: passwordProblem(form.password)
        ?: when {
            form.password != form.passwordConfirm -> "비밀번호가 서로 달라요"
            !EMAIL.matches(form.email.trim()) -> "이메일 형식을 확인해 주세요"
            !form.agreed -> "개인정보 처리방침에 동의해 주세요"
            else -> null
        }
}
