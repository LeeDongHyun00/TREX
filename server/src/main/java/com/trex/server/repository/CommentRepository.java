package com.trex.server.repository;

import com.trex.server.entity.Comment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    /**
     * 한 글의 댓글 — {@code before} 보다 오래된 것을 최신순으로(서비스가 오래된 순으로 뒤집어 보인다).
     * 숨긴 댓글·정지된 작성자의 댓글은 내 것일 때와 관리자일 때만, 내가 차단한 사람의 댓글은 관리자가 아니면 뺀다.
     */
    @Query("""
            select c from Comment c join fetch c.author a
            where c.post.id = :post and c.id < :before
              and (:admin = true or c.hiddenAt is null or a.id = :me)
              and (:admin = true or a.suspendedAt is null or a.id = :me)
              and (:admin = true or not exists (select 1 from Block b where b.blocker.id = :me and b.blocked.id = a.id))
            order by c.id desc
            """)
    List<Comment> page(@Param("post") Long post, @Param("me") Long me, @Param("admin") boolean admin,
                       @Param("before") long before, Pageable page);

    /** 글 id 묶음의 보이는 댓글 수 — [글 id, 수]. 보는 사람이 차단한 사람의 댓글은 목록처럼 뺀다(수와 목록이 어긋나지 않게). */
    @Query("""
            select c.post.id, count(c) from Comment c
            where c.post.id in :ids and c.hiddenAt is null and c.author.suspendedAt is null
              and not exists (select 1 from Block b where b.blocker.id = :me and b.blocked.id = c.author.id)
            group by c.post.id
            """)
    List<Object[]> countVisibleByPostIds(@Param("ids") Collection<Long> ids, @Param("me") Long me);

    long countByAuthorIdAndCreatedAtAfter(Long authorId, Instant after);

    @Modifying
    @Query("delete from Comment c where c.post.id = :postId")
    void deleteByPostId(@Param("postId") Long postId);

    /** 탈퇴 — 그 사람이 쓴 댓글 id(신고 기록을 같이 지우려고). */
    @Query("select c.id from Comment c where c.author.id = :author")
    List<Long> idsByAuthor(@Param("author") Long author);

    /** 탈퇴 — 그 사람 글에 달린 댓글 id. */
    @Query("select c.id from Comment c where c.post.id in :posts")
    List<Long> idsOnPosts(@Param("posts") Collection<Long> posts);

    @Modifying
    @Query("delete from Comment c where c.author.id = :author")
    void deleteByAuthor(@Param("author") Long author);
}
