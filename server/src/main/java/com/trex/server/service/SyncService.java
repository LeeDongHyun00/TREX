package com.trex.server.service;

import com.trex.server.dto.SyncItemResponse;
import com.trex.server.entity.SyncItem;
import com.trex.server.entity.User;
import com.trex.server.exception.InvalidRequestException;
import com.trex.server.repository.SyncItemRepository;
import com.trex.server.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 앱 데이터 통째 백업(docs/SERVER_SYNC_HANDOFF.md D5·D6).
 *
 * <p>충돌 규칙: 항목별로 **앱에서 나중에 바꾼 쪽이 이긴다**. 서버에 있는 것보다 오래된 쓰기는 반영하지 않고
 * 서버 값을 돌려준다(컨트롤러가 409 로 내려 앱이 서버 값을 받아들인다). 같은 시각이면 새 쓰기를 받는다 —
 * 같은 기기가 같은 내용을 다시 올리는 경우라 결과가 같다.
 */
@Service
@Transactional(readOnly = true)
public class SyncService {

    private static final Pattern KEY = Pattern.compile("^[a-z0-9_]{1,64}$");

    private final UserRepository userRepository;
    private final SyncItemRepository syncItemRepository;

    public SyncService(UserRepository userRepository, SyncItemRepository syncItemRepository) {
        this.userRepository = userRepository;
        this.syncItemRepository = syncItemRepository;
    }

    public List<SyncItemResponse> getMine(String loginId) {
        User user = userRepository.getByLoginIdOrThrow(loginId);
        return syncItemRepository.findByUserIdOrderByItemKey(user.getId()).stream()
                .map(SyncItemResponse::from)
                .toList();
    }

    /** 쓰기 결과. {@code applied} 가 false 면 서버 값이 더 최신이라 반영하지 않았고 {@code item} 은 서버 값이다. */
    public record PutResult(boolean applied, SyncItemResponse item) {
    }

    @Transactional
    public PutResult put(String loginId, String key, String payload, long updatedAt) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new InvalidRequestException("항목 이름은 영문 소문자·숫자·밑줄 1~64자여야 합니다");
        }
        if (payload.length() > SyncItem.MAX_PAYLOAD_CHARS) {
            throw new InvalidRequestException("항목이 너무 큽니다(최대 " + SyncItem.MAX_PAYLOAD_CHARS + "자)");
        }
        User user = userRepository.getByLoginIdOrThrow(loginId);
        var existing = syncItemRepository.findByUserIdAndItemKey(user.getId(), key);
        if (existing.isPresent()) {
            SyncItem item = existing.get();
            if (item.getClientUpdatedAt() > updatedAt) {
                return new PutResult(false, SyncItemResponse.from(item));
            }
            item.overwrite(payload, updatedAt);
            return new PutResult(true, SyncItemResponse.from(item));
        }
        SyncItem saved = syncItemRepository.save(new SyncItem(user, key, payload, updatedAt));
        return new PutResult(true, SyncItemResponse.from(saved));
    }
}
