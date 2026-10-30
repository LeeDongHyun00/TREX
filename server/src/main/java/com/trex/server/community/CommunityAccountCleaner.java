package com.trex.server.community;

import com.trex.server.entity.CommunityProfile;
import com.trex.server.entity.Post;
import com.trex.server.entity.PostImage;
import com.trex.server.entity.ReportTargetType;
import com.trex.server.entity.User;
import com.trex.server.repository.BlockRepository;
import com.trex.server.repository.CommentRepository;
import com.trex.server.repository.CommunityProfileRepository;
import com.trex.server.repository.PostLikeRepository;
import com.trex.server.repository.PostRepository;
import com.trex.server.repository.ReportRepository;
import com.trex.server.repository.SharedRoutineRepository;
import com.trex.server.storage.StorageCleanup;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 회원 탈퇴 때 커뮤니티 흔적을 모두 지운다 — App Store 5.1.1(v)는 남에게 보인 글·사진까지 지우라고 한다(설계 §5 탈퇴).
 * 그 사람이 누른 좋아요, 쓴 댓글, 한 신고와 그 사람의 글·댓글·계정을 대상으로 한 신고, 들어간 차단(양쪽),
 * 그 사람 글(과 거기 달린 좋아요·댓글), 메신저로 보낸 공유 루틴, 프로필. 사진·프로필 사진은 커밋 뒤 저장소에서 지우고 실패는 로그만.
 * {@code AuthService.deleteAccount} 가 회원을 지우기 직전에 같은 트랜잭션에서 부른다.
 */
@Component
public class CommunityAccountCleaner {

    private final CommunityProfileRepository profiles;
    private final PostRepository posts;
    private final PostLikeRepository likes;
    private final CommentRepository comments;
    private final ReportRepository reports;
    private final BlockRepository blocks;
    private final SharedRoutineRepository sharedRoutines;
    private final StorageCleanup cleanup;

    public CommunityAccountCleaner(CommunityProfileRepository profiles, PostRepository posts, PostLikeRepository likes,
                                   CommentRepository comments, ReportRepository reports, BlockRepository blocks,
                                   SharedRoutineRepository sharedRoutines,
                                   StorageCleanup cleanup) {
        this.profiles = profiles;
        this.posts = posts;
        this.likes = likes;
        this.comments = comments;
        this.reports = reports;
        this.blocks = blocks;
        this.sharedRoutines = sharedRoutines;
        this.cleanup = cleanup;
    }

    public void deleteAllFor(User user) {
        profiles.findByUserId(user.getId()).ifPresent(this::delete);
    }

    private void delete(CommunityProfile profile) {
        Long id = profile.getId();
        List<Post> myPosts = posts.findByAuthorId(id);
        List<Long> postIds = myPosts.stream().map(Post::getId).toList();
        List<String> keys = new ArrayList<>();
        myPosts.forEach(p -> p.getImages().stream().map(PostImage::getStorageKey).forEach(keys::add));
        if (profile.getAvatarKey() != null) keys.add(profile.getAvatarKey());

        Set<Long> commentIds = new HashSet<>(comments.idsByAuthor(id));
        if (!postIds.isEmpty()) commentIds.addAll(comments.idsOnPosts(postIds));

        reports.deleteByReporter(id);
        deleteTargets(ReportTargetType.POST, postIds);
        deleteTargets(ReportTargetType.COMMENT, commentIds);
        reports.deleteTargets(ReportTargetType.USER, List.of(profile.getPublicId()));

        likes.deleteByProfile(id);
        comments.deleteByAuthor(id);
        for (Long postId : postIds) {
            likes.deleteByPostId(postId);
            comments.deleteByPostId(postId);
        }
        blocks.deleteInvolving(id);
        sharedRoutines.deleteByOwnerId(id);
        posts.deleteAll(myPosts);
        profiles.delete(profile);
        cleanup.deleteAfterCommit(keys);
    }

    private void deleteTargets(ReportTargetType type, Collection<Long> ids) {
        if (ids.isEmpty()) return;
        reports.deleteTargets(type, ids.stream().map(String::valueOf).toList());
    }
}
