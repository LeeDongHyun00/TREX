package com.trex.server.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;

/**
 * 사진 지우기 — DB 가 커밋된 뒤에만 저장소에서 지운다(롤백되면 사진은 남아야 한다). 실패는 로그만 남긴다:
 * 글 삭제·탈퇴는 사진 하나 때문에 실패하면 안 된다(고아 사진은 남지만 아무 글도 가리키지 않는다).
 */
@Component
public class StorageCleanup {

    private static final Logger log = LoggerFactory.getLogger(StorageCleanup.class);

    private final ImageStorage storage;

    public StorageCleanup(ImageStorage storage) {
        this.storage = storage;
    }

    public void deleteAfterCommit(Collection<String> keys) {
        List<String> targets = keys.stream().filter(k -> k != null && !k.isBlank()).toList();
        if (targets.isEmpty()) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteNow(targets);
                }
            });
        } else {
            deleteNow(targets);
        }
    }

    /** 커밋과 무관하게 바로 지운다 — 저장 도중 실패해 이미 올린 사진을 치울 때. */
    public void deleteNow(Collection<String> keys) {
        for (String key : keys) {
            try {
                storage.delete(key);
            } catch (RuntimeException e) {
                log.warn("사진 삭제 실패(고아 사진으로 남는다): {}", key, e);
            }
        }
    }
}
