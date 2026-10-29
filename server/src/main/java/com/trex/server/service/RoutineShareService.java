package com.trex.server.service;

import com.trex.server.community.BannedWords;
import com.trex.server.config.ShareProperties;
import com.trex.server.dto.ShareRoutineRequest;
import com.trex.server.dto.ShareRoutineResponse;
import com.trex.server.dto.SharedExerciseDto;
import com.trex.server.dto.SharedRoutineResponse;
import com.trex.server.entity.CommunityProfile;
import com.trex.server.entity.Post;
import com.trex.server.entity.SharedRoutine;
import com.trex.server.exception.InvalidRequestException;
import com.trex.server.exception.ResourceNotFoundException;
import com.trex.server.exception.TooManyRequestsException;
import com.trex.server.repository.PostRepository;
import com.trex.server.repository.SharedRoutineRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 루틴 공유(docs/superpowers/specs/2026-10-29-routine-share-design.md §4) — 공유는 커뮤니티에 글을 쓸 수 있는 회원
 * ({@code requireWriter}: 닉네임·이용규칙 동의·정지 아님), 조회는 누구나. 링크 코드는 추측하기 어려운 무작위 8자.
 * 게시글 링크 페이지용으로 보이는 글의 작성자 닉네임도 여기서 꺼낸다(본문·사진은 내보내지 않는다).
 */
@Service
public class RoutineShareService {

    static final int MAX_NAME = 30;
    static final int MAX_EXERCISES = 30;
    private static final int MAX_PER_WINDOW = 10;
    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final int CODE_LENGTH = 8;
    private static final String CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final Set<String> TARGET_KINDS = Set.of("duration", "repetitions");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<SharedExerciseDto>> EXERCISES = new TypeReference<>() {
    };

    private final SharedRoutineRepository routines;
    private final PostRepository posts;
    private final CommunityProfileService profiles;
    private final BannedWords bannedWords;
    private final ShareProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RoutineShareService(SharedRoutineRepository routines, PostRepository posts, CommunityProfileService profiles,
                               BannedWords bannedWords, ShareProperties properties, Clock clock) {
        this.routines = routines;
        this.posts = posts;
        this.profiles = profiles;
        this.bannedWords = bannedWords;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public ShareRoutineResponse share(String loginId, ShareRoutineRequest req) {
        CommunityProfile me = profiles.requireWriter(loginId);
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty() || name.length() > MAX_NAME) throw new InvalidRequestException("루틴 이름은 1~30자로 적어 주세요");
        if (bannedWords.contains(name)) throw new InvalidRequestException("루틴 이름에 쓸 수 없는 말이 있어요");
        List<SharedExerciseDto> exercises = req.exercises() == null ? List.of() : req.exercises();
        if (exercises.isEmpty() || exercises.size() > MAX_EXERCISES) throw new InvalidRequestException("운동은 1~30개까지 공유할 수 있어요");
        exercises.forEach(RoutineShareService::check);
        int totalSeconds = req.totalSeconds() == null ? 0 : req.totalSeconds();
        if (totalSeconds < 0 || totalSeconds > 36_000) throw new InvalidRequestException("예상 시간이 올바르지 않아요");

        Instant now = clock.instant();
        if (routines.countByOwnerIdAndCreatedAtAfter(me.getId(), now.minus(WINDOW)) >= MAX_PER_WINDOW) {
            throw new TooManyRequestsException("잠시 뒤에 다시 공유해 주세요");
        }
        String payload;
        try {
            payload = MAPPER.writeValueAsString(exercises);
        } catch (JacksonException e) {
            throw new InvalidRequestException("운동 목록을 읽지 못했어요");
        }
        if (payload.length() > SharedRoutine.MAX_PAYLOAD_CHARS) throw new InvalidRequestException("운동 목록이 너무 길어요");
        String code = newCode();
        routines.save(new SharedRoutine(code, me, name, totalSeconds, exercises.size(), payload, now));
        return new ShareRoutineResponse(code, properties.baseUrl() + "/r/" + code);
    }

    @Transactional(readOnly = true)
    public SharedRoutineResponse get(String code) {
        SharedRoutine r = routines.findByCode(code).orElseThrow(() -> new ResourceNotFoundException("없어졌거나 잘못된 링크예요"));
        List<SharedExerciseDto> exercises;
        try {
            exercises = MAPPER.readValue(r.getPayload(), EXERCISES);
        } catch (JacksonException e) {
            throw new IllegalStateException("공유 루틴 운동 목록을 읽지 못했습니다", e);
        }
        return new SharedRoutineResponse(r.getName(), r.getOwner().getNickname(), r.getTotalSeconds(), exercises, r.getCreatedAt());
    }

    /** 링크 페이지에 보일 작성자 닉네임 — 숨긴 글·정지된 작성자의 글·없는 글은 비어 있다. */
    @Transactional(readOnly = true)
    public Optional<String> postAuthorNickname(long postId) {
        return posts.findById(postId)
                .filter(p -> !p.isHidden() && !p.getAuthor().isSuspended())
                .map(Post::getAuthor)
                .map(CommunityProfile::getNickname);
    }

    private static void check(SharedExerciseDto e) {
        if (e == null || !length(e.name(), 1, 40) || !length(e.reps(), 1, 40) || !length(e.duration(), 0, 40)
                || !length(e.category(), 1, 20)) {
            throw new InvalidRequestException("운동 정보가 올바르지 않아요");
        }
        if (e.alt() != null && (!length(e.alt().name(), 1, 40) || !length(e.alt().reps(), 0, 40))) {
            throw new InvalidRequestException("대체 운동 정보가 올바르지 않아요");
        }
        if (!within(e.secondsPerRep(), 1, 15) || !within(e.restSeconds(), 0, 600) || !within(e.targetAmount(), 1, 3600)
                || (e.targetKind() != null && !TARGET_KINDS.contains(e.targetKind()))) {
            throw new InvalidRequestException("운동 설정 값이 올바르지 않아요");
        }
    }

    private static boolean length(String s, int min, int max) {
        return s != null && s.length() >= min && s.length() <= max;
    }

    private static boolean within(Integer v, int min, int max) {
        return v == null || (v >= min && v <= max);
    }

    private String newCode() {
        while (true) {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
            String code = sb.toString();
            if (!routines.existsByCode(code)) return code;
        }
    }
}
