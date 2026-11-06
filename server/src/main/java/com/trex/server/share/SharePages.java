package com.trex.server.share;

import com.trex.server.config.ShareProperties;
import com.trex.server.dto.SharedExerciseDto;
import com.trex.server.dto.SharedRoutineResponse;
import org.springframework.web.util.HtmlUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 공유 링크 웹 페이지(docs/superpowers/specs/2026-10-29-routine-share-design.md §4) — 템플릿 엔진 없이 문자열로 만든다.
 * {@code <head>} 의 og 메타는 카카오톡 미리보기 카드가 읽는다(대표 그림 = 앱 아이콘 {@code /share/trex-icon.png}). 사용자가 적은 글자(루틴·운동 이름, 닉네임)는 모두 이스케이프한다.
 * "앱에서 열기"는 Android 면 카카오톡 안 브라우저에서도 동작하는 intent 주소(앱이 없으면 설치 주소로), 그 밖은 {@code trex://}.
 * 게시글 페이지에는 닉네임만 — 본문·사진은 앱 안에서만 본다.
 */
public final class SharePages {

    private static final String PACKAGE = "com.example.trex_kotlin";
    private static final int PREVIEW = 3;

    private SharePages() {
    }

    /** User-Agent → {@code "android" | "ios" | "other"}. */
    public static String device(String userAgent) {
        if (userAgent == null) return "other";
        if (userAgent.contains("Android")) return "android";
        if (userAgent.contains("iPhone") || userAgent.contains("iPad")) return "ios";
        return "other";
    }

    public static String routine(SharedRoutineResponse r, String code, String device, ShareProperties p) {
        int minutes = (r.totalSeconds() + 59) / 60;
        String meta = "운동 " + r.exercises().size() + "개 · 약 " + minutes + "분 · " + r.senderNickname() + "님의 루틴";
        StringBuilder body = new StringBuilder()
                .append("<p class=\"label\">T-REX 루틴</p><h1>").append(esc(r.name())).append("</h1>")
                .append("<p class=\"meta\">").append(esc(meta)).append("</p>");
        List<SharedExerciseDto> list = r.exercises();
        list.stream().limit(PREVIEW).forEach(e -> body.append("<div class=\"ex\"><b>").append(esc(e.name())).append("</b><span>")
                .append(esc(e.reps())).append("</span></div>"));
        if (list.size() > PREVIEW) body.append("<div class=\"ex more\">…").append(list.size() - PREVIEW).append("개 더</div>");
        body.append(buttons("r/" + code, device, p));
        return page(r.name(), meta, p.baseUrl() + "/r/" + code, p, body.toString());
    }

    public static String post(long postId, String nickname, String device, ShareProperties p) {
        String title = nickname + "님의 T-REX 커뮤니티 글";
        String desc = "T-REX 앱에서 볼 수 있어요";
        String body = "<p class=\"label\">T-REX 커뮤니티</p><h1>" + esc(title) + "</h1><p class=\"meta\">" + esc(desc) + "</p>"
                + buttons("p/" + postId, device, p);
        return page(title, desc, p.baseUrl() + "/p/" + postId, p, body);
    }

    public static String notFound(String title, String device, ShareProperties p) {
        String body = "<p class=\"label\">T-REX</p><h1>" + esc(title) + "</h1>" + buttons(null, device, p);
        return page(title, "카메라로 자세를 봐 주는 운동 앱 T-REX", p.baseUrl(), p, body);
    }

    private static String buttons(String path, String device, ShareProperties p) {
        StringBuilder b = new StringBuilder();
        if (path != null) {
            String open = "android".equals(device)
                    ? "intent://" + path + "#Intent;scheme=trex;package=" + PACKAGE + ";S.browser_fallback_url="
                    + URLEncoder.encode(p.androidInstallUrl(), StandardCharsets.UTF_8) + ";end"
                    : "trex://" + path;
            b.append("<a class=\"btn main\" href=\"").append(esc(open)).append("\">T-REX 앱에서 열기</a>");
        }
        if ("ios".equals(device)) {
            if (p.iosInstallUrl().isEmpty()) b.append("<p class=\"note\">App Store 출시 준비 중</p>");
            else b.append("<a class=\"btn\" href=\"").append(esc(p.iosInstallUrl())).append("\">앱 설치하기</a>");
        } else {
            b.append("<a class=\"btn\" href=\"").append(esc(p.androidInstallUrl())).append("\">앱 설치하기</a>");
        }
        return b.append("<p class=\"note\">카메라로 자세를 봐 주는 운동 앱</p>").toString();
    }

    private static String page(String title, String description, String url, ShareProperties p, String body) {
        return "<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + esc(title) + "</title>"
                + "<meta property=\"og:type\" content=\"website\">"
                + "<meta property=\"og:title\" content=\"" + esc(title) + "\">"
                + "<meta property=\"og:description\" content=\"" + esc(description) + "\">"
                + "<meta property=\"og:image\" content=\"" + esc(p.baseUrl() + "/share/trex-icon.png") + "\">"
                + "<meta property=\"og:url\" content=\"" + esc(url) + "\">"
                + "<style>" + STYLE + "</style></head><body><main>" + body + "</main></body></html>";
    }

    private static String esc(String s) {
        return HtmlUtils.htmlEscape(s == null ? "" : s, "UTF-8");
    }

    private static final String STYLE = "body{margin:0;background:#141811;color:#e8ecdf;font-family:-apple-system,'Apple SD Gothic Neo',"
            + "'Noto Sans KR',sans-serif}main{max-width:480px;margin:0 auto;padding:32px 16px 48px}"
            + ".label{color:#a9cf7a;font-size:13px;margin:0}h1{font-size:26px;margin:6px 0 4px}.meta{color:#b7bfae;font-size:14px;margin:0 0 16px}"
            + ".ex{display:flex;justify-content:space-between;gap:12px;background:#1f251b;border-radius:14px;padding:12px 14px;margin-top:8px;font-size:15px}"
            + ".ex span{color:#b7bfae;font-size:13px}.more{color:#b7bfae}"
            + ".btn{display:block;text-align:center;border:1px solid #3a4532;color:#cfe3b0;border-radius:16px;padding:14px;margin-top:12px;"
            + "font-weight:700;text-decoration:none}.btn.main{background:#76a04a;border-color:#76a04a;color:#fff;margin-top:20px}"
            + ".note{color:#8d9684;font-size:12px;text-align:center;margin-top:12px}";
}
