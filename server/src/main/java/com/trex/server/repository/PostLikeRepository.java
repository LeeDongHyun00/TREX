package com.trex.server.repository;

import com.trex.server.entity.PostLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PostLikeRepository extends JpaRepository<PostLike, Long> {

    Optional<PostLike> findByPostIdAndProfileId(Long postId, Long profileId);

    /** 글 id 묶음의 좋아요 수 — [글 id, 수]. 피드 한 쪽을 한 번에 센다. */
    @Query("select l.post.id, count(l) from PostLike l where l.post.id in :ids group by l.post.id")
    List<Object[]> countByPostIds(@Param("ids") Collection<Long> ids);

    @Query("select l.post.id from PostLike l where l.post.id in :ids and l.profile.id = :me")
    List<Long> likedAmong(@Param("ids") Collection<Long> ids, @Param("me") Long me);

    @Modifying
    @Query("delete from PostLike l where l.post.id = :postId")
    void deleteByPostId(@Param("postId") Long postId);

    /** 좋아요 취소 — 없어도 0행으로 성공(동시에 두 번 와도 멱등, 리뷰 2026-10-29). */
    @Modifying
    @Query("delete from PostLike l where l.post.id = :post and l.profile.id = :profile")
    int deleteByPostAndProfile(@Param("post") Long post, @Param("profile") Long profile);

    /** 탈퇴 — 그 사람이 누른 좋아요. */
    @Modifying
    @Query("delete from PostLike l where l.profile.id = :profile")
    void deleteByProfile(@Param("profile") Long profile);
}
