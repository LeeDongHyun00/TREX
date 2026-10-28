package com.trex.server.repository;

import com.trex.server.entity.Post;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface PostRepository extends JpaRepository<Post, Long> {

    /**
     * 피드 — {@code before} 보다 오래된(작은 id) 글을 최신순으로. 숨긴 글과 정지된 작성자의 글은 내 글일 때만.
     * 커서가 id 라 넘기는 사이 글이 지워지거나 숨겨져도 중복·누락이 없다. {@code before} 가 없으면 {@link Long#MAX_VALUE}.
     */
    @Query("""
            select p from Post p join fetch p.author a
            where p.id < :before
              and (p.hiddenAt is null or a.id = :me)
              and (a.suspendedAt is null or a.id = :me)
            order by p.id desc
            """)
    List<Post> feed(@Param("me") Long me, @Param("before") long before, Pageable page);

    /** 한 사람의 글 — 보이기 규칙은 피드와 같다. */
    @Query("""
            select p from Post p join fetch p.author a
            where a.id = :author and p.id < :before
              and (p.hiddenAt is null or a.id = :me)
            order by p.id desc
            """)
    List<Post> byAuthor(@Param("me") Long me, @Param("author") Long author, @Param("before") long before, Pageable page);

    long countByAuthorIdAndCreatedAtAfter(Long authorId, Instant after);

    long countByAuthorIdAndHiddenAtIsNull(Long authorId);
}
