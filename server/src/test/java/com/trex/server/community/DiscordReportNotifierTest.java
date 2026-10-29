package com.trex.server.community;

import com.trex.server.config.CommunityProperties;
import com.trex.server.entity.ReportReason;
import com.trex.server.entity.ReportTargetType;
import com.trex.server.service.DiscordReportNotifier;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 디스코드 신고 알림 — 웹후크가 응답하지 않아도 다음 알림이 막히지 않는다. 알림은 스레드 하나로 차례로 보내므로,
 * 시간 제한이 없으면 한 번 멈춘 요청 뒤의 알림이 전부 가지 않는다(24시간 처리의 유일한 신호, 설계 §3 새 신고 알림).
 */
class DiscordReportNotifierTest {

    @Test
    void 웹후크가_응답하지_않아도_다음_알림은_보낸다() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch twoConnections = new CountDownLatch(2);
            List<Socket> held = new CopyOnWriteArrayList<>();
            Thread acceptor = new Thread(() -> {
                try {
                    while (!server.isClosed()) {
                        held.add(server.accept()); // 받기만 하고 답하지 않는다
                        twoConnections.countDown();
                    }
                } catch (Exception ignored) {
                    // 서버 소켓을 닫으면 끝난다
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            CommunityProperties properties = new CommunityProperties("", "", List.of(), "http://127.0.0.1:" + server.getLocalPort() + "/hook", false);
            DiscordReportNotifier notifier = new DiscordReportNotifier(properties, Duration.ofMillis(300));
            notifier.reportFiled(ReportTargetType.POST, "1", ReportReason.SPAM, 1, false);
            notifier.reportFiled(ReportTargetType.POST, "2", ReportReason.SPAM, 1, false);

            assertTrue(twoConnections.await(5, TimeUnit.SECONDS), "첫 알림이 멈추면 두 번째 알림이 연결조차 하지 않는다");
            for (Socket socket : held) socket.close();
        }
    }
}
