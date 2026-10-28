package com.trex.server.repository;

import com.trex.server.entity.Block;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BlockRepository extends JpaRepository<Block, Long> {

    boolean existsByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

    Optional<Block> findByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

    @Query("select b from Block b join fetch b.blocked where b.blocker.id = :blocker order by b.createdAt desc")
    List<Block> listByBlocker(@Param("blocker") Long blocker);

    /** 탈퇴 — 그 사람이 들어간 차단을 양쪽 모두 지운다. */
    @Modifying
    @Query("delete from Block b where b.blocker.id = :profile or b.blocked.id = :profile")
    void deleteInvolving(@Param("profile") Long profile);
}
