package com.trex.server.service;

import com.trex.server.dto.community.AdminReportDto;
import com.trex.server.dto.community.PostDto;
import com.trex.server.dto.community.ProfileDto;
import com.trex.server.dto.community.ResolveRequest;
import com.trex.server.entity.Comment;
import com.trex.server.entity.CommunityProfile;
import com.trex.server.entity.Post;
import com.trex.server.entity.Report;
import com.trex.server.entity.ReportReason;
import com.trex.server.entity.ReportResolution;
import com.trex.server.entity.ReportTargetType;
import com.trex.server.exception.ForbiddenException;
import com.trex.server.exception.InvalidRequestException;
import com.trex.server.exception.ResourceNotFoundException;
import com.trex.server.repository.CommentRepository;
import com.trex.server.repository.CommunityProfileRepository;
import com.trex.server.repository.PostRepository;
import com.trex.server.repository.ReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 관리자 — 앱 안 신고함(대상별로 묶은 신고), 처리(그대로 둠·지움), 숨김·복구, 정지·해제(설계 §3 관리자).
 * 관리자는 {@code trex.community.admin-login-ids} 에 있는 loginId. 신고는 24시간 안에 처리한다(운영 몫).
 */
@Service
public class AdminService {

    public static final String ADMIN_HIDE_REASON = "관리자";
    private static final int PREVIEW = 80;

    private final ReportRepository reports;
    private final PostRepository posts;
    private final CommentRepository comments;
    private final CommunityProfileRepository profileRepository;
    private final PostService postService;
    private final CommunityProfileService profiles;
    private final Clock clock;

    public AdminService(ReportRepository reports, PostRepository posts, CommentRepository comments,
                        CommunityProfileRepository profileRepository, PostService postService,
                        CommunityProfileService profiles, Clock clock) {
        this.reports = reports;
        this.posts = posts;
        this.comments = comments;
        this.profileRepository = profileRepository;
        this.postService = postService;
        this.profiles = profiles;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AdminReportDto> reports(String loginId, boolean pendingOnly) {
        requireAdmin(loginId);
        List<Report> found = pendingOnly ? reports.findByResolvedAtIsNullOrderByIdAsc() : reports.findAllByOrderByIdDesc();
        Map<String, List<Report>> byTarget = new LinkedHashMap<>();
        for (Report r : found) byTarget.computeIfAbsent(r.getTargetType() + ":" + r.getTargetId(), k -> new ArrayList<>()).add(r);
        List<AdminReportDto> out = new ArrayList<>();
        for (List<Report> group : byTarget.values()) {
            Report first = group.get(0);
            List<ReportReason> reasons = group.stream().map(Report::getReason).distinct().toList();
            long firstAt = group.stream().map(Report::getCreatedAt).min(Instant::compareTo).orElseThrow().toEpochMilli();
            Target target = target(first.getTargetType(), first.getTargetId());
            out.add(new AdminReportDto(first.getTargetType(), first.getTargetId(), target.preview(), group.size(), reasons,
                    target.hidden(), firstAt));
        }
        return out;
    }

    @Transactional
    public void resolve(String loginId, ResolveRequest request) {
        requireAdmin(loginId);
        if (request == null || request.targetType() == null || request.targetId() == null || request.resolution() == null) {
            throw new InvalidRequestException("처리할 대상과 결과를 골라 주세요");
        }
        Instant now = clock.instant();
        String targetId = request.targetId().trim();
        reports.findByTargetTypeAndTargetIdAndResolvedAtIsNull(request.targetType(), targetId)
                .forEach(r -> r.resolve(request.resolution(), now));
        boolean remove = request.resolution() == ReportResolution.REMOVED;
        switch (request.targetType()) {
            case POST -> findPost(targetId).ifPresent(post -> {
                if (remove) postService.remove(post);
                else post.restore();
            });
            case COMMENT -> findComment(targetId).ifPresent(comment -> {
                if (remove) comments.delete(comment);
                else comment.restore();
            });
            case USER -> profileRepository.findByPublicId(targetId).ifPresent(profile -> {
                if (remove) profile.suspend(now);
            });
        }
    }

    @Transactional
    public void hidePost(String loginId, long id) {
        requireAdmin(loginId);
        post(id).hide(ADMIN_HIDE_REASON, clock.instant());
    }

    @Transactional
    public void restorePost(String loginId, long id) {
        requireAdmin(loginId);
        post(id).restore();
    }

    @Transactional
    public void hideComment(String loginId, long id) {
        requireAdmin(loginId);
        comment(id).hide(ADMIN_HIDE_REASON, clock.instant());
    }

    @Transactional
    public void restoreComment(String loginId, long id) {
        requireAdmin(loginId);
        comment(id).restore();
    }

    @Transactional(readOnly = true)
    public List<PostDto> hiddenPosts(String loginId) {
        CommunityProfile me = requireAdmin(loginId);
        return postService.toDtos(posts.hidden(), me, true);
    }

    @Transactional
    public void suspend(String loginId, String publicId) {
        requireAdmin(loginId);
        profile(publicId).suspend(clock.instant());
    }

    @Transactional
    public void unsuspend(String loginId, String publicId) {
        requireAdmin(loginId);
        profile(publicId).unsuspend();
    }

    @Transactional(readOnly = true)
    public List<ProfileDto> suspended(String loginId) {
        requireAdmin(loginId);
        return profileRepository.findBySuspendedAtIsNotNullOrderBySuspendedAtDesc().stream().map(profiles::toDto).toList();
    }

    /** 관리자이고 커뮤니티 프로필(닉네임·이용규칙)이 있어야 한다 — 숨긴 글 목록이 보는 사람 기준으로 만들어지기 때문. */
    private CommunityProfile requireAdmin(String loginId) {
        if (!profiles.isAdmin(loginId)) throw new ForbiddenException("관리자만 할 수 있어요");
        return profiles.requireReader(loginId);
    }

    private record Target(String preview, boolean hidden) {
    }

    private Target target(ReportTargetType type, String targetId) {
        return switch (type) {
            case POST -> findPost(targetId)
                    .map(p -> new Target(preview(p.getBody() == null ? "(사진만)" : p.getBody()), p.isHidden()))
                    .orElse(new Target("(삭제됨)", false));
            case COMMENT -> findComment(targetId)
                    .map(c -> new Target(preview(c.getBody()), c.isHidden()))
                    .orElse(new Target("(삭제됨)", false));
            case USER -> profileRepository.findByPublicId(targetId)
                    .map(p -> new Target(p.getNickname(), p.isSuspended()))
                    .orElse(new Target("(탈퇴함)", false));
        };
    }

    private static String preview(String text) {
        if (text.codePointCount(0, text.length()) <= PREVIEW) return text;
        return text.substring(0, text.offsetByCodePoints(0, PREVIEW)) + "…";
    }

    private Optional<Post> findPost(String id) {
        return parse(id).flatMap(posts::findById);
    }

    private Optional<Comment> findComment(String id) {
        return parse(id).flatMap(comments::findById);
    }

    private static Optional<Long> parse(String id) {
        try {
            return Optional.of(Long.parseLong(id));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private Post post(long id) {
        return posts.findById(id).orElseThrow(() -> new ResourceNotFoundException("글을 찾을 수 없어요"));
    }

    private Comment comment(long id) {
        return comments.findById(id).orElseThrow(() -> new ResourceNotFoundException("댓글을 찾을 수 없어요"));
    }

    private CommunityProfile profile(String publicId) {
        return profileRepository.findByPublicId(publicId).orElseThrow(() -> new ResourceNotFoundException("사용자를 찾을 수 없어요"));
    }
}
