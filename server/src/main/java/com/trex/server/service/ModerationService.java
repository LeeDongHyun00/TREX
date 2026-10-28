package com.trex.server.service;

import com.trex.server.dto.community.MyReportDto;
import com.trex.server.dto.community.ProfileDto;
import com.trex.server.dto.community.ReportRequest;
import com.trex.server.entity.Block;
import com.trex.server.entity.Comment;
import com.trex.server.entity.CommunityProfile;
import com.trex.server.entity.Post;
import com.trex.server.entity.Report;
import com.trex.server.entity.ReportTargetType;
import com.trex.server.exception.DuplicateResourceException;
import com.trex.server.exception.InvalidRequestException;
import com.trex.server.exception.ResourceNotFoundException;
import com.trex.server.repository.BlockRepository;
import com.trex.server.repository.CommentRepository;
import com.trex.server.repository.CommunityProfileRepository;
import com.trex.server.repository.ReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 차단·신고 — App Store 1.2·Play UGC 의 필수 장치(설계 §4·§5). 서로 다른 세 사람의 미처리 신고가 모이면 글·댓글을 자동 숨김하고,
 * 신고가 들어올 때마다 운영자에게 알린다({@link ReportNotifier}, 커밋 뒤).
 */
@Service
public class ModerationService {

    public static final int AUTO_HIDE_AT = 3;
    public static final String AUTO_HIDE_REASON = "신고 누적";

    private final BlockRepository blocks;
    private final ReportRepository reports;
    private final CommunityProfileRepository profileRepository;
    private final CommentRepository comments;
    private final PostService posts;
    private final CommunityProfileService profiles;
    private final ReportNotifier notifier;
    private final Clock clock;

    public ModerationService(BlockRepository blocks, ReportRepository reports, CommunityProfileRepository profileRepository,
                             CommentRepository comments, PostService posts, CommunityProfileService profiles,
                             ReportNotifier notifier, Clock clock) {
        this.blocks = blocks;
        this.reports = reports;
        this.profileRepository = profileRepository;
        this.comments = comments;
        this.posts = posts;
        this.profiles = profiles;
        this.notifier = notifier;
        this.clock = clock;
    }

    @Transactional
    public void block(String loginId, String publicId) {
        CommunityProfile me = profiles.requireReader(loginId);
        CommunityProfile target = findProfile(publicId);
        if (target.getId().equals(me.getId())) throw new InvalidRequestException("자기 자신은 차단할 수 없어요");
        if (!blocks.existsByBlockerIdAndBlockedId(me.getId(), target.getId())) {
            blocks.save(new Block(me, target, clock.instant()));
        }
    }

    @Transactional
    public void unblock(String loginId, String publicId) {
        CommunityProfile me = profiles.requireReader(loginId);
        CommunityProfile target = findProfile(publicId);
        blocks.findByBlockerIdAndBlockedId(me.getId(), target.getId()).ifPresent(blocks::delete);
    }

    @Transactional(readOnly = true)
    public List<ProfileDto> blocks(String loginId) {
        CommunityProfile me = profiles.requireReader(loginId);
        return blocks.listByBlocker(me.getId()).stream().map(b -> profiles.toDto(b.getBlocked())).toList();
    }

    @Transactional
    public void report(String loginId, ReportRequest request) {
        CommunityProfile me = profiles.requireWriter(loginId);
        if (request == null || request.targetType() == null || request.reason() == null || request.targetId() == null) {
            throw new InvalidRequestException("신고 대상과 사유를 골라 주세요");
        }
        boolean admin = profiles.isAdmin(loginId);
        String targetId = request.targetId().trim();
        Post post = null;
        Comment comment = null;
        switch (request.targetType()) {
            case POST -> {
                post = posts.visiblePost(parseId(targetId), me, admin);
                if (post.getAuthor().getId().equals(me.getId())) throw new InvalidRequestException("내 글은 신고할 수 없어요");
            }
            case COMMENT -> {
                comment = comments.findById(parseId(targetId)).orElseThrow(() -> new ResourceNotFoundException("댓글을 찾을 수 없어요"));
                if (comment.getAuthor().getId().equals(me.getId())) throw new InvalidRequestException("내 댓글은 신고할 수 없어요");
            }
            case USER -> {
                CommunityProfile target = findProfile(targetId);
                if (target.getId().equals(me.getId())) throw new InvalidRequestException("자기 자신은 신고할 수 없어요");
            }
        }
        if (reports.existsByReporterIdAndTargetTypeAndTargetId(me.getId(), request.targetType(), targetId)) {
            throw new DuplicateResourceException("이미 신고했어요");
        }
        Instant now = clock.instant();
        reports.save(new Report(me, request.targetType(), targetId, request.reason(), now));
        long pending = reports.countByTargetTypeAndTargetIdAndResolvedAtIsNull(request.targetType(), targetId);
        boolean autoHidden = false;
        if (pending >= AUTO_HIDE_AT) {
            if (post != null && !post.isHidden()) {
                post.hide(AUTO_HIDE_REASON, now);
                autoHidden = true;
            } else if (comment != null && !comment.isHidden()) {
                comment.hide(AUTO_HIDE_REASON, now);
                autoHidden = true;
            }
        }
        notifyAfterCommit(request.targetType(), targetId, request, pending, autoHidden);
    }

    @Transactional(readOnly = true)
    public List<MyReportDto> myReports(String loginId) {
        CommunityProfile me = profiles.requireReader(loginId);
        return reports.findByReporterIdOrderByIdDesc(me.getId()).stream()
                .map(r -> new MyReportDto(r.getId(), r.getTargetType(), r.getTargetId(), r.getReason(),
                        r.getCreatedAt().toEpochMilli(), r.isResolved() ? "RESOLVED" : "PENDING", r.getResolution()))
                .toList();
    }

    private void notifyAfterCommit(ReportTargetType type, String targetId, ReportRequest request, long pending, boolean autoHidden) {
        Runnable send = () -> notifier.reportFiled(type, targetId, request.reason(), pending, autoHidden);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    private CommunityProfile findProfile(String publicId) {
        return profileRepository.findByPublicId(publicId).orElseThrow(() -> new ResourceNotFoundException("사용자를 찾을 수 없어요"));
    }

    private static long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw new ResourceNotFoundException("신고할 대상을 찾을 수 없어요");
        }
    }
}
