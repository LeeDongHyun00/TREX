package com.trex.server.repository;

import com.trex.server.entity.SyncItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SyncItemRepository extends JpaRepository<SyncItem, Long> {

    List<SyncItem> findByUserIdOrderByItemKey(Integer userId);

    /**
     * 쓰기용 — 행을 잠그고(SELECT … FOR UPDATE) 읽는다. 두 기기가 같은 항목을 동시에 올리면 둘 다 "내가 더 최신"
     * 판정을 통과한 뒤 더 오래된 쪽이 나중에 커밋될 수 있었다(코드 리뷰 지적). 잠그면 뒤에 온 쓰기가 앞의 커밋을 보고 판정한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SyncItem s where s.user.id = :userId and s.itemKey = :itemKey")
    Optional<SyncItem> findForUpdate(@Param("userId") Integer userId, @Param("itemKey") String itemKey);

    long deleteByUserId(Integer userId);
}
