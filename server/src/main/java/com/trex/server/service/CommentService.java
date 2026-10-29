package com.trex.server.service;

import com.trex.server.community.BannedWords;
import com.trex.server.dto.community.CommentDto;
import com.trex.server.dto.community.CommentPage;
import com.trex.server.entity.Comment;
import com.trex.server.entity.CommunityProfile;
import com.trex.server.entity.Post;
import com.trex.server.exception.ForbiddenException;
import com.trex.server.exception.InvalidRequestException;
import com.trex.server.exception.ResourceNotFoundException;
import com.trex.server.exception.TooManyRequestsException;
import com.trex.server.repository.CommentRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 댓글(한 단계) — 목록·쓰기·지우기(설계 §2 목표 5). 지우기는 댓글 작성자, 그 글의 작성자, 관리자.
 * 숨긴 글에는 댓글을 달 수 없다(없는 글처럼 404).
 */
@Service
public class CommentService {

    public static final int MAX_BODY = 500;
    public static final int PAGE_SIZE = 50;
    private static final int MAX_PER_MINUTE = 5;

    private final CommentRepository comments;
    private final PostService posts;
    private final CommunityProfileService profiles;
    private final BannedWords bannedWords;
    private final Clock clock;

    public CommentService(CommentRepository comments, PostService posts, CommunityProfileService profiles,
                          BannedWords bannedWords, Clock clock) {
        this.comments = comments;
        this.posts = posts;
        this.profiles = profiles;
        this.bannedWords = bannedWords;
        this.clock = clock;
    }

    /** 최근 {@link #PAGE_SIZE} 개를 오래된 순으로. 더 오래된 것은 {@code nextBefore} 로. */
    @Transactional(readOnly = true)
    public CommentPage list(String loginId, long postId, Long before) {
        CommunityProfile me = profiles.requireReader(loginId);
        boolean admin = profiles.isAdmin(loginId);
        posts.visiblePost(postId, me, admin);
        List<Comment> found = comments.page(postId, me.getId(), admin, before == null ? Long.MAX_VALUE : before,
                PageRequest.of(0, PAGE_SIZE + 1));
        boolean more = found.size() > PAGE_SIZE;
        List<Comment> items = new ArrayList<>(more ? found.subList(0, PAGE_SIZE) : found);
        Long next = more ? items.get(items.size() - 1).getId() : null;
        Collections.reverse(items);
        return new CommentPage(items.stream().map(c -> toDto(c, me, admin)).toList(), next);
    }

    @Transactional
    public CommentDto add(String loginId, long postId, String body) {
        CommunityProfile me = profiles.requireWriter(loginId);
        boolean admin = profiles.isAdmin(loginId);
        Post post = posts.visiblePost(postId, me, admin);
        if (post.isHidden() || posts.blockedBy(post.getAuthor(), me)) throw new ResourceNotFoundException("글을 찾을 수 없어요");
        String text = body == null ? "" : body.strip();
        if (text.isEmpty()) throw new InvalidRequestException("댓글을 입력해 주세요");
        if (text.codePointCount(0, text.length()) > MAX_BODY) throw new InvalidRequestException("댓글은 500자까지 쓸 수 있어요");
        if (bannedWords.contains(text)) throw new InvalidRequestException("쓸 수 없는 표현이 있어요");
        Instant now = clock.instant();
        if (comments.countByAuthorIdAndCreatedAtAfter(me.getId(), now.minus(Duration.ofMinutes(1))) >= MAX_PER_MINUTE) {
            throw new TooManyRequestsException("잠시 뒤에 다시 써 주세요");
        }
        return toDto(comments.save(new Comment(post, me, text, now)), me, admin);
    }

    @Transactional
    public void delete(String loginId, long commentId) {
        CommunityProfile me = profiles.requireReader(loginId);
        boolean admin = profiles.isAdmin(loginId);
        Comment comment = comments.findById(commentId).orElseThrow(() -> new ResourceNotFoundException("댓글을 찾을 수 없어요"));
        boolean allowed = comment.getAuthor().getId().equals(me.getId())
                || comment.getPost().getAuthor().getId().equals(me.getId())
                || admin;
        if (!allowed) throw new ForbiddenException("내 댓글이나 내 글의 댓글만 지울 수 있어요");
        comments.delete(comment);
    }

    CommentDto toDto(Comment comment, CommunityProfile viewer, boolean admin) {
        boolean mine = comment.getAuthor().getId().equals(viewer.getId());
        String reason = comment.isHidden() && (mine || admin) ? comment.getHiddenReason() : null;
        return new CommentDto(comment.getId(), profiles.toDto(comment.getAuthor()), comment.getBody(),
                comment.getCreatedAt().toEpochMilli(), comment.isHidden(), reason, mine);
    }
}
