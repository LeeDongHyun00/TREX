package com.example.trex_kotlin.sync

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * "Google로 계속하기" — Credential Manager 로 구글 ID 토큰을 받는다. 토큰 검증은 서버가 한다(D8).
 *
 * [webClientId] 는 구글 콘솔의 **웹 애플리케이션** 클라이언트 ID 다. 구글은 이 값을 토큰의 aud 로 넣고,
 * 서버는 aud 가 자기 설정(GOOGLE_CLIENT_IDS)과 같은지 본다. 안드로이드 클라이언트(패키지명·SHA-1)는
 * 콘솔에 등록만 돼 있으면 되고 코드에는 쓰지 않는다.
 *
 * [activityContext] 는 액티비티여야 한다(계정 선택 화면을 띄운다). Credential Manager 의 실패는 공통
 * [SignInException] 으로 바꿔 던진다(§108) — 사용자가 닫으면 [SignInException.Cancelled].
 */
suspend fun requestGoogleIdToken(activityContext: Context, webClientId: String): String {
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(GetSignInWithGoogleOption.Builder(webClientId).build())
        .build()
    val credential = try {
        CredentialManager.create(activityContext).getCredential(activityContext, request).credential
    } catch (_: GetCredentialCancellationException) {
        throw SignInException.Cancelled()
    } catch (_: NoCredentialException) {
        throw SignInException.NoAccount()
    } catch (e: GetCredentialException) {
        // 콘솔 설정 오류(SHA-1·패키지명·테스트 사용자)는 여기로 온다 — 원인을 화면과 로그에 남긴다
        android.util.Log.w("TrexAuth", "구글 로그인 실패 type=${e.type}", e)
        throw SignInException.Failed("${e.errorMessage ?: e.type}")
    }
    if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }
    throw IllegalStateException("구글 계정 정보를 받지 못했습니다")
}
