package com.trex.server.repository;

import com.trex.server.entity.SyncItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SyncItemRepository extends JpaRepository<SyncItem, Long> {

    List<SyncItem> findByUserIdOrderByItemKey(Integer userId);

    Optional<SyncItem> findByUserIdAndItemKey(Integer userId, String itemKey);

    long deleteByUserId(Integer userId);
}
