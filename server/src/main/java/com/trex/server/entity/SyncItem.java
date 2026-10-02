package com.trex.server.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 앱 데이터 항목 하나의 통째 백업(docs/SERVER_SYNC_HANDOFF.md D5).
 *
 * <p>앱은 프로필·루틴·운동 기록·식단 같은 항목을 각각 JSON 덩어리로 저장한다. 서버는 그 덩어리를 해석하지 않고
 * 사용자·항목 이름별로 그대로 보관한다 — 앱 저장 구조가 바뀌어도 서버를 고칠 필요가 없다.
 *
 * <p>7일 보존 정책({@code LogRetentionScheduler}) 대상이 아니다(D7). 회원 탈퇴 때만 지운다.
 */
@Entity
@Table(
        name = "SYNC_ITEMS",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "item_key"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SyncItem {

    /** 항목 하나의 최대 크기(문자). 내 음식 기억 300장이 약 0.6MB 라 여유를 둔다. */
    public static final int MAX_PAYLOAD_CHARS = 2 * 1024 * 1024;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "item_key", nullable = false, length = 64)
    private String itemKey;

    @Column(nullable = false, length = MAX_PAYLOAD_CHARS)
    private String payload;

    // 앱이 그 항목을 마지막으로 바꾼 시각(epoch ms). 충돌 판정은 이 값으로 한다(D6).
    @Column(name = "client_updated_at", nullable = false)
    private Long clientUpdatedAt;

    @Column(name = "server_updated_at", nullable = false)
    private Instant serverUpdatedAt;

    public SyncItem(User user, String itemKey, String payload, long clientUpdatedAt) {
        this.user = user;
        this.itemKey = itemKey;
        overwrite(payload, clientUpdatedAt);
    }

    public void overwrite(String payload, long clientUpdatedAt) {
        this.payload = payload;
        this.clientUpdatedAt = clientUpdatedAt;
        this.serverUpdatedAt = Instant.now();
    }
}
