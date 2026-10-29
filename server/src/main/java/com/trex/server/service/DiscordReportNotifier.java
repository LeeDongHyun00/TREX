package com.trex.server.service;

import com.trex.server.config.CommunityProperties;
import com.trex.server.entity.ReportReason;
import com.trex.server.entity.ReportTargetType;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 새 신고를 팀 디스코드 채널 웹후크로 알린다({@code trex.community.report-webhook-url}, 비면 아무것도 안 한다).
 * 요청 스레드를 붙잡지 않게 따로 보내고, 실패는 로그만 — 알림이 안 가도 신고 자체는 저장돼 있다.
 */
@Component
public class DiscordReportNotifier implements ReportNotifier {

    private static final Logger log = LoggerFactory.getLogger(DiscordReportNotifier.class);

    private final String webhookUrl;
    private final RestClient client;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "report-webhook");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public DiscordReportNotifier(CommunityProperties properties) {
        this(properties, Duration.ofSeconds(10));
    }

    /**
     * [timeout] 은 연결·읽기 시간 제한 — 알림은 스레드 하나로 차례로 보내서, 제한이 없으면 디스코드가 한 번 멈출 때
     * 뒤의 알림이 전부 가지 않는다. 테스트는 짧게 준다.
     */
    public DiscordReportNotifier(CommunityProperties properties, Duration timeout) {
        this.webhookUrl = properties.reportWebhookUrl();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public void reportFiled(ReportTargetType type, String targetId, ReportReason reason, long reportCount, boolean autoHidden) {
        if (webhookUrl.isEmpty()) return;
        String content = message(type, targetId, reason, reportCount, autoHidden);
        sender.execute(() -> {
            try {
                client.post().uri(webhookUrl).contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("content", content)).retrieve().toBodilessEntity();
            } catch (RuntimeException e) {
                log.warn("신고 알림 실패: {}", content, e);
            }
        });
    }

    /** 예: {@code [T-REX 신고] 글 #12 · 욕설·혐오 · 누적 3건 · 자동 숨김}. */
    public static String message(ReportTargetType type, String targetId, ReportReason reason, long reportCount, boolean autoHidden) {
        String target = switch (type) {
            case POST -> "글 #" + targetId;
            case COMMENT -> "댓글 #" + targetId;
            case USER -> "사용자 " + targetId;
        };
        return "[T-REX 신고] " + target + " · " + reason.label + " · 누적 " + reportCount + "건" + (autoHidden ? " · 자동 숨김" : "")
                + " — 앱 내 정보 탭 신고함에서 처리해 주세요";
    }

    @PreDestroy
    void shutdown() {
        sender.shutdown();
    }
}
