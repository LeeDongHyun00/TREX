package com.example.trex_kotlin.community

import com.example.trex_kotlin.sync.ApiException
import com.example.trex_kotlin.sync.BinaryTransport
import com.example.trex_kotlin.sync.HttpTransport
import com.example.trex_kotlin.sync.Multipart
import com.example.trex_kotlin.sync.NetworkException
import org.json.JSONArray
import org.json.JSONObject

/**
 * 커뮤니티 서버 API(`/api/community` 아래, 설계 §6) — [com.example.trex_kotlin.sync.TrexApi] 와 같은 방식(라이브러리 없이 플랫폼 전송).
 * JSON 은 [http], 사진이 실리는 글쓰기·프로필 사진은 [binary](multipart). 오류는 서버 문구를 담은 [ApiException],
 * 연결 실패는 [NetworkException] — 화면은 그 문구를 그대로 보인다(예: "잠시 뒤에 다시 올려 주세요").
 */
class CommunityApi(
    private val baseUrl: String,
    private val http: HttpTransport,
    private val binary: BinaryTransport,
    private val token: () -> String?,
) {

    suspend fun me(): CommunityMe = parsed { JSONObject(get("/me")).let { o ->
        CommunityMe(o.optJSONObject("profile")?.let(::profile), o.getBoolean("termsAccepted"), o.getBoolean("admin"), o.getBoolean("suspended"))
    } }

    suspend fun setNickname(nickname: String): CommunityProfile =
        parsed { profile(JSONObject(json("PUT", "/me/profile", JSONObject().put("nickname", nickname)))) }

    suspend fun acceptTerms(version: String = TERMS_VERSION) {
        json("POST", "/me/terms", JSONObject().put("version", version))
    }

    suspend fun setAvatar(jpeg: ByteArray): CommunityProfile =
        parsed { profile(JSONObject(multipart("PUT", "/me/avatar", listOf(Multipart.Part.File("image", "avatar.jpg", "image/jpeg", jpeg))))) }

    suspend fun clearAvatar() {
        json("DELETE", "/me/avatar")
    }

    suspend fun feed(before: Long?): PostPage = parsed { postPage(get("/posts" + cursor(before))) }

    suspend fun post(id: Long): Post = parsed { post(JSONObject(get("/posts/$id"))) }

    /** 글쓰기 — 본문(없어도 된다)과 JPEG 0~5장(긴 변 1080 으로 다시 만든 것). */
    suspend fun createPost(body: String?, images: List<ByteArray>): Post {
        val parts = buildList {
            if (body != null) add(Multipart.Part.Text("body", body))
            images.forEachIndexed { i, jpeg -> add(Multipart.Part.File("images", "photo$i.jpg", "image/jpeg", jpeg)) }
        }
        return parsed { post(JSONObject(multipart("POST", "/posts", parts))) }
    }

    suspend fun deletePost(id: Long) {
        json("DELETE", "/posts/$id")
    }

    suspend fun like(postId: Long) {
        json("PUT", "/posts/$postId/like")
    }

    suspend fun unlike(postId: Long) {
        json("DELETE", "/posts/$postId/like")
    }

    suspend fun comments(postId: Long, before: Long?): CommentPage = parsed { JSONObject(get("/posts/$postId/comments" + cursor(before))).let { o ->
        CommentPage(o.getJSONArray("items").objects().map(::comment), o.optLongOrNull("nextBefore"))
    } }

    suspend fun addComment(postId: Long, body: String): Comment =
        parsed { comment(JSONObject(json("POST", "/posts/$postId/comments", JSONObject().put("body", body)))) }

    suspend fun deleteComment(id: Long) {
        json("DELETE", "/comments/$id")
    }

    suspend fun user(publicId: String): UserProfile = parsed { JSONObject(get("/users/$publicId")).let { o ->
        UserProfile(profile(o.getJSONObject("profile")), o.getLong("postCount"), o.getBoolean("blockedByMe"))
    } }

    suspend fun userPosts(publicId: String, before: Long?): PostPage = parsed { postPage(get("/users/$publicId/posts" + cursor(before))) }

    suspend fun block(publicId: String) {
        json("PUT", "/blocks/$publicId")
    }

    suspend fun unblock(publicId: String) {
        json("DELETE", "/blocks/$publicId")
    }

    suspend fun blocks(): List<CommunityProfile> = parsed { JSONArray(get("/me/blocks")).objects().map(::profile) }

    suspend fun report(target: ReportTarget, targetId: String, reason: ReportReason) {
        json("POST", "/reports", JSONObject().put("targetType", target.name).put("targetId", targetId).put("reason", reason.name))
    }

    suspend fun myReports(): List<MyReport> = parsed { JSONArray(get("/me/reports")).objects().map { o ->
        MyReport(
            o.getLong("id"), ReportTarget.valueOf(o.getString("targetType")), o.getString("targetId"),
            ReportReason.from(o.getString("reason")), o.getLong("createdAt"),
            o.getString("status") == "RESOLVED", o.optStringOrNull("resolution"),
        )
    } }

    // ---- 관리자(관리자가 아니면 서버가 403)

    suspend fun adminReports(): List<AdminReport> = parsed { JSONArray(get("/admin/reports")).objects().map { o ->
        val reasons = o.getJSONArray("reasons")
        AdminReport(
            ReportTarget.valueOf(o.getString("targetType")), o.getString("targetId"), o.getString("preview"),
            o.getLong("reportCount"), List(reasons.length()) { ReportReason.from(reasons.getString(it)) },
            o.getBoolean("hidden"), o.getLong("firstReportedAt"),
        )
    } }

    /** 신고 처리 — [kept] 면 그대로 두고 숨김을 푼다, 아니면 지운다(사용자면 정지). */
    suspend fun resolve(target: ReportTarget, targetId: String, kept: Boolean) {
        json("POST", "/admin/reports/resolve", JSONObject().put("targetType", target.name).put("targetId", targetId)
            .put("resolution", if (kept) "KEPT" else "REMOVED"))
    }

    suspend fun hidePost(id: Long) {
        json("POST", "/admin/posts/$id/hide", JSONObject())
    }

    suspend fun restorePost(id: Long) {
        json("POST", "/admin/posts/$id/restore", JSONObject())
    }

    suspend fun suspend(publicId: String) {
        json("POST", "/admin/users/$publicId/suspend", JSONObject())
    }

    suspend fun unsuspend(publicId: String) {
        json("POST", "/admin/users/$publicId/unsuspend", JSONObject())
    }

    suspend fun suspended(): List<CommunityProfile> = parsed { JSONArray(get("/admin/suspended")).objects().map(::profile) }

    suspend fun hiddenPosts(): List<Post> = parsed { JSONArray(get("/admin/hidden")).objects().map(::post) }

    // ---- 전송

    /**
     * 응답 읽기 — 서버 오류([ApiException])·연결 실패([NetworkException])는 그대로, 그 밖에 읽다 난 예외(공용 와이파이의 200 HTML,
     * 모르는 값)는 [NetworkException] 으로 바꾼다. 화면이 약속된 두 예외만 잡으면 되게(리뷰 2026-10-29).
     */
    private inline fun <T> parsed(block: () -> T): T = try {
        block()
    } catch (e: NetworkException) {
        throw e
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        throw NetworkException("서버 응답을 읽지 못했습니다", e)
    }

    private suspend fun get(path: String): String = json("GET", path)

    private suspend fun json(method: String, path: String, body: JSONObject? = null): String {
        val headers = buildMap {
            put("Accept", "application/json")
            token()?.let { put("Authorization", "Bearer $it") }
            if (body != null) put("Content-Type", "application/json; charset=utf-8")
        }
        val response = http.send(method, url(path), headers, body?.toString())
        return checked(response.status, response.body)
    }

    private suspend fun multipart(method: String, path: String, parts: List<Multipart.Part>): String {
        val boundary = Multipart.boundary()
        val headers = buildMap {
            put("Accept", "application/json")
            token()?.let { put("Authorization", "Bearer $it") }
            put("Content-Type", Multipart.contentType(boundary))
        }
        val response = binary.exchange(method, url(path), headers, Multipart.body(boundary, parts))
        return checked(response.status, response.body.decodeToString())
    }

    private fun url(path: String): String {
        if (baseUrl.isBlank()) throw NetworkException("서버 주소가 설정되지 않았습니다")
        return baseUrl.trimEnd('/') + "/api/community" + path
    }

    private fun checked(status: Int, text: String): String {
        if (status >= 400) {
            val message = runCatching { JSONObject(text).optString("message") }.getOrNull()
            throw ApiException(status, message?.takeIf { it.isNotBlank() } ?: "서버 오류($status)")
        }
        return text
    }

    companion object {
        /** 서버 `CommunityProfileService.TERMS_VERSION`·terms.html 과 같은 버전. */
        const val TERMS_VERSION = "2026-10-28"
        const val TERMS_URL = "https://leedonghyun00.github.io/TREX/terms.html"

        private fun cursor(before: Long?) = if (before == null) "" else "?before=$before"

        private fun profile(o: JSONObject) = CommunityProfile(o.getString("publicId"), o.getString("nickname"), o.optStringOrNull("avatarUrl"))

        private fun post(o: JSONObject): Post {
            val images = o.getJSONArray("images").objects().map { PostImage(it.getString("url"), it.getInt("width"), it.getInt("height")) }
            return Post(
                o.getLong("id"), profile(o.getJSONObject("author")), o.optStringOrNull("body"), images,
                o.getLong("likeCount"), o.getBoolean("likedByMe"), o.getLong("commentCount"), o.getLong("createdAt"),
                o.getBoolean("hidden"), o.optStringOrNull("hiddenReason"), o.getBoolean("mine"),
            )
        }

        private fun postPage(text: String) = JSONObject(text).let { o ->
            PostPage(o.getJSONArray("items").objects().map(::post), o.optLongOrNull("nextBefore"))
        }

        private fun comment(o: JSONObject) = Comment(
            o.getLong("id"), profile(o.getJSONObject("author")), o.getString("body"), o.getLong("createdAt"),
            o.getBoolean("hidden"), o.optStringOrNull("hiddenReason"), o.getBoolean("mine"),
        )

        private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

        private fun JSONObject.optStringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)

        private fun JSONObject.optLongOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)
    }
}
