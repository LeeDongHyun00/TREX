package com.trex.server.service;

import com.trex.server.community.BannedWords;
import com.trex.server.dto.community.ImageDto;
import com.trex.server.dto.community.PostDto;
import com.trex.server.dto.community.PostPage;
import com.trex.server.dto.community.UserProfileDto;
import com.trex.server.entity.CommunityProfile;
import com.trex.server.entity.Post;
import com.trex.server.entity.PostImage;
import com.trex.server.entity.PostLike;
import com.trex.server.exception.ForbiddenException;
import com.trex.server.exception.InvalidRequestException;
import com.trex.server.exception.ResourceNotFoundException;
import com.trex.server.exception.TooManyRequestsException;
import com.trex.server.repository.BlockRepository;
import com.trex.server.repository.CommentRepository;
import com.trex.server.repository.CommunityProfileRepository;
import com.trex.server.repository.PostLikeRepository;
import com.trex.server.repository.PostRepository;
import com.trex.server.storage.ImageStorage;
import com.trex.server.storage.JpegInfo;
import com.trex.server.storage.StorageCleanup;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 커뮤니티 글 — 쓰기(글만·사진만·둘 다), 피드, 상세, 삭제, 사람별 글(설계 §2 목표 2·3·6, §5).
 * 보이기 규칙: 숨긴 글·정지된 작성자의 글은 작성자 본인과 관리자만 본다.
 */
@Service
public class PostService {

    public static final int MAX_BODY = 2000;
    public static final int MAX_IMAGES = 5;
    public static final long MAX_IMAGE_BYTES = 2L * 1024 * 1024;
    public static final int PAGE_SIZE = 20;
    private static final int MAX_PER_DAY = 30;

    private final PostRepository posts;
    private final PostLikeRepository likes;
    private final CommentRepository comments;
    private final BlockRepository blocks;
    private final CommunityProfileRepository profileRepository;
    private final CommunityProfileService profiles;
    private final BannedWords bannedWords;
    private final ImageStorage storage;
    private final StorageCleanup cleanup;
    private final Clock clock;

    public PostService(PostRepository posts, PostLikeRepository likes, CommentRepository comments, BlockRepository blocks,
                       CommunityProfileRepository profileRepository, CommunityProfileService profiles,
                       BannedWords bannedWords, ImageStorage storage, StorageCleanup cleanup, Clock clock) {
        this.posts = posts;
        this.likes = likes;
        this.comments = comments;
        this.blocks = blocks;
        this.profileRepository = profileRepository;
        this.profiles = profiles;
        this.bannedWords = bannedWords;
        this.storage = storage;
        this.cleanup = cleanup;
        this.clock = clock;
    }

    @Transactional
    public PostDto create(String loginId, String body, List<MultipartFile> images) {
        CommunityProfile me = profiles.requireWriter(loginId);
        String text = body == null || body.isBlank() ? null : body.strip();
        List<MultipartFile> files = images == null ? List.of() : images.stream().filter(f -> !f.isEmpty()).toList();
        if (text == null && files.isEmpty()) throw new InvalidRequestException("글이나 사진을 넣어 주세요");
        if (text != null && text.codePointCount(0, text.length()) > MAX_BODY) {
            throw new InvalidRequestException("글은 2,000자까지 쓸 수 있어요");
        }
        if (files.size() > MAX_IMAGES) throw new InvalidRequestException("사진은 5장까지 올릴 수 있어요");
        List<byte[]> bytes = new ArrayList<>();
        List<JpegInfo> infos = new ArrayList<>();
        for (MultipartFile file : files) {
            // MockMvc·프록시 뒤에서도 같은 한도가 걸리게 multipart 설정과 별도로 직접 본다.
            if (file.getSize() > MAX_IMAGE_BYTES) throw new MaxUploadSizeExceededException(MAX_IMAGE_BYTES);
            byte[] data = read(file);
            infos.add(JpegInfo.read(data));
            bytes.add(data);
        }
        if (text != null && bannedWords.contains(text)) throw new InvalidRequestException("쓸 수 없는 표현이 있어요");
        Instant now = clock.instant();
        if (posts.countByAuthorIdAndCreatedAtAfter(me.getId(), now.minus(Duration.ofMinutes(1))) >= 1) {
            throw new TooManyRequestsException("잠시 뒤에 다시 올려 주세요");
        }
        if (posts.countByAuthorIdAndCreatedAtAfter(me.getId(), now.minus(Duration.ofHours(24))) >= MAX_PER_DAY) {
            throw new TooManyRequestsException("오늘은 글을 더 올릴 수 없어요");
        }

        Post post = posts.save(new Post(me, text, now));
        List<String> uploaded = new ArrayList<>();
        try {
            for (int i = 0; i < bytes.size(); i++) {
                String key = "posts/" + post.getId() + "/" + i + "-" + UUID.randomUUID() + ".jpg";
                storage.put(key, bytes.get(i));
                uploaded.add(key);
                post.addImage(new PostImage(post, i, key, infos.get(i).width(), infos.get(i).height()));
            }
        } catch (RuntimeException e) {
            // 글은 롤백된다 — 이미 올린 사진만 치운다.
            cleanup.deleteNow(uploaded);
            throw e;
        }
        return toDto(post, me, profiles.isAdmin(loginId));
    }

    @Transactional(readOnly = true)
    public PostPage feed(String loginId, Long before, int limit) {
        CommunityProfile me = profiles.requireReader(loginId);
        int size = clamp(limit);
        List<Post> found = posts.feed(me.getId(), cursor(before), PageRequest.of(0, size + 1));
        return page(found, size, me, profiles.isAdmin(loginId));
    }

    @Transactional(readOnly = true)
    public PostDto get(String loginId, long postId) {
        CommunityProfile me = profiles.requireReader(loginId);
        boolean admin = profiles.isAdmin(loginId);
        return toDto(visiblePost(postId, me, admin), me, admin);
    }

    @Transactional
    public void delete(String loginId, long postId) {
        CommunityProfile me = profiles.requireReader(loginId);
        boolean admin = profiles.isAdmin(loginId);
        Post post = visiblePost(postId, me, admin);
        if (!isMine(post, me) && !admin) throw new ForbiddenException("내 글만 지울 수 있어요");
        remove(post);
    }

    /** 글과 그 좋아요·댓글을 지우고, 사진은 커밋 뒤 저장소에서 지운다(관리자 처리도 이 길). */
    void remove(Post post) {
        List<String> keys = post.getImages().stream().map(PostImage::getStorageKey).toList();
        likes.deleteByPostId(post.getId());
        comments.deleteByPostId(post.getId());
        posts.delete(post);
        cleanup.deleteAfterCommit(keys);
    }

    /** 좋아요 — 이미 눌렀으면 그대로(두 번 눌러도 하나). 동시에 두 번 와서 유니크에 걸리는 경우는 컨트롤러가 성공으로 삼킨다. */
    @Transactional
    public void like(String loginId, long postId) {
        CommunityProfile me = profiles.requireWriter(loginId);
        Post post = visiblePost(postId, me, profiles.isAdmin(loginId));
        if (post.isHidden()) throw new ResourceNotFoundException("글을 찾을 수 없어요");
        if (likes.findByPostIdAndProfileId(postId, me.getId()).isEmpty()) {
            likes.save(new PostLike(post, me, clock.instant()));
        }
    }

    /** 좋아요 취소 — 안 눌렀어도 그대로. */
    @Transactional
    public void unlike(String loginId, long postId) {
        CommunityProfile me = profiles.requireWriter(loginId);
        likes.findByPostIdAndProfileId(postId, me.getId()).ifPresent(likes::delete);
    }

    @Transactional(readOnly = true)
    public UserProfileDto user(String loginId, String publicId) {
        CommunityProfile me = profiles.requireReader(loginId);
        CommunityProfile target = findProfile(publicId);
        boolean hiddenAuthor = target.isSuspended() && !target.getId().equals(me.getId()) && !profiles.isAdmin(loginId);
        long count = hiddenAuthor ? 0 : posts.countByAuthorIdAndHiddenAtIsNull(target.getId());
        return new UserProfileDto(profiles.toDto(target), count, blocks.existsByBlockerIdAndBlockedId(me.getId(), target.getId()));
    }

    @Transactional(readOnly = true)
    public PostPage userPosts(String loginId, String publicId, Long before) {
        CommunityProfile me = profiles.requireReader(loginId);
        boolean admin = profiles.isAdmin(loginId);
        CommunityProfile target = findProfile(publicId);
        if (target.isSuspended() && !target.getId().equals(me.getId()) && !admin) return new PostPage(List.of(), null);
        if (blocks.existsByBlockerIdAndBlockedId(me.getId(), target.getId())) return new PostPage(List.of(), null);
        List<Post> found = posts.byAuthor(me.getId(), target.getId(), cursor(before), PageRequest.of(0, PAGE_SIZE + 1));
        return page(found, PAGE_SIZE, me, admin);
    }

    /** 보는 사람에게 보이는 글 — 숨김·정지된 작성자·내가 차단한 작성자의 글은 작성자 본인과 관리자 말고는 없는 것처럼 404. */
    Post visiblePost(long postId, CommunityProfile viewer, boolean admin) {
        Post post = posts.findById(postId).orElseThrow(() -> new ResourceNotFoundException("글을 찾을 수 없어요"));
        if (isMine(post, viewer) || admin) return post;
        boolean hiddenFromOthers = post.isHidden() || post.getAuthor().isSuspended()
                || blocks.existsByBlockerIdAndBlockedId(viewer.getId(), post.getAuthor().getId());
        if (hiddenFromOthers) throw new ResourceNotFoundException("글을 찾을 수 없어요");
        return post;
    }

    private PostPage page(List<Post> found, int size, CommunityProfile viewer, boolean admin) {
        boolean more = found.size() > size;
        List<Post> items = more ? found.subList(0, size) : found;
        Long next = more ? items.get(items.size() - 1).getId() : null;
        return new PostPage(toDtos(items, viewer, admin), next);
    }

    PostDto toDto(Post post, CommunityProfile viewer, boolean admin) {
        return toDtos(List.of(post), viewer, admin).get(0);
    }

    /** 글 묶음 → 응답. 좋아요·댓글 수와 내 좋아요는 묶음째 한 번씩 센다(피드 한 쪽에 쿼리 세 번). */
    List<PostDto> toDtos(List<Post> list, CommunityProfile viewer, boolean admin) {
        if (list.isEmpty()) return List.of();
        List<Long> ids = list.stream().map(Post::getId).toList();
        Map<Long, Long> likeCounts = counts(likes.countByPostIds(ids));
        Map<Long, Long> commentCounts = counts(comments.countVisibleByPostIds(ids));
        Set<Long> liked = new HashSet<>(likes.likedAmong(ids, viewer.getId()));
        return list.stream().map(post -> {
            boolean mine = isMine(post, viewer);
            List<ImageDto> images = post.getImages().stream()
                    .map(i -> new ImageDto(storage.url(i.getStorageKey()), i.getWidth(), i.getHeight()))
                    .toList();
            String reason = post.isHidden() && (mine || admin) ? post.getHiddenReason() : null;
            return new PostDto(post.getId(), profiles.toDto(post.getAuthor()), post.getBody(), images,
                    likeCounts.getOrDefault(post.getId(), 0L), liked.contains(post.getId()),
                    commentCounts.getOrDefault(post.getId(), 0L),
                    post.getCreatedAt().toEpochMilli(), post.isHidden(), reason, mine);
        }).toList();
    }

    private static Map<Long, Long> counts(List<Object[]> rows) {
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : rows) map.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        return map;
    }

    private CommunityProfile findProfile(String publicId) {
        return profileRepository.findByPublicId(publicId).orElseThrow(() -> new ResourceNotFoundException("사용자를 찾을 수 없어요"));
    }

    private static boolean isMine(Post post, CommunityProfile viewer) {
        return post.getAuthor().getId().equals(viewer.getId());
    }

    private static long cursor(Long before) {
        return before == null ? Long.MAX_VALUE : before;
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(PAGE_SIZE, limit));
    }

    private static byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
