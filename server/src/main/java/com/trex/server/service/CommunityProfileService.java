package com.trex.server.service;

import com.trex.server.community.BannedWords;
import com.trex.server.config.CommunityProperties;
import com.trex.server.dto.community.MeResponse;
import com.trex.server.dto.community.ProfileDto;
import com.trex.server.entity.CommunityProfile;
import com.trex.server.entity.User;
import com.trex.server.exception.DuplicateResourceException;
import com.trex.server.exception.ForbiddenException;
import com.trex.server.exception.InvalidRequestException;
import com.trex.server.repository.CommunityProfileRepository;
import com.trex.server.repository.UserRepository;
import com.trex.server.storage.ImageStorage;
import com.trex.server.storage.JpegInfo;
import com.trex.server.storage.StorageCleanup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 커뮤니티 프로필 — 닉네임·이용규칙 동의·프로필 사진, 그리고 다른 커뮤니티 서비스가 쓰는 입장 검사({@link #requireReader}·{@link #requireWriter}).
 * 근거: docs/superpowers/specs/2026-10-28-community-design.md §3(공개 이름·계정)·§4(이용규칙·만 14세)·§5(처음 들어올 때).
 */
@Service
public class CommunityProfileService {

    /** 앱 동의 화면과 terms.html 이 같은 버전을 쓴다. 문구를 바꾸면 올리고, 옛 동의는 다시 받는다. */
    public static final String TERMS_VERSION = "2026-10-28";

    private static final String PUBLIC_ID_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int PUBLIC_ID_LENGTH = 12;

    private final CommunityProfileRepository profiles;
    private final UserRepository users;
    private final BannedWords bannedWords;
    private final ImageStorage storage;
    private final StorageCleanup cleanup;
    private final CommunityProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public CommunityProfileService(CommunityProfileRepository profiles, UserRepository users, BannedWords bannedWords,
                                   ImageStorage storage, StorageCleanup cleanup, CommunityProperties properties, Clock clock) {
        this.profiles = profiles;
        this.users = users;
        this.bannedWords = bannedWords;
        this.storage = storage;
        this.cleanup = cleanup;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MeResponse me(String loginId) {
        Optional<CommunityProfile> profile = find(loginId);
        return new MeResponse(
                profile.map(this::toDto).orElse(null),
                profile.map(p -> p.hasAccepted(TERMS_VERSION)).orElse(false),
                isAdmin(loginId),
                profile.map(CommunityProfile::isSuspended).orElse(false));
    }

    @Transactional
    public ProfileDto setNickname(String loginId, String nickname) {
        String clean = cleanNickname(nickname);
        String key = clean.toLowerCase(Locale.ROOT);
        Optional<CommunityProfile> mine = find(loginId);
        profiles.findByNicknameKey(key)
                .filter(other -> mine.map(m -> !m.getId().equals(other.getId())).orElse(true))
                .ifPresent(other -> {
                    throw new DuplicateResourceException("이미 쓰는 닉네임이에요");
                });
        CommunityProfile profile = mine.orElse(null);
        if (profile == null) {
            User user = users.getByLoginIdOrThrow(loginId);
            profile = profiles.save(new CommunityProfile(user, newPublicId(), clean, key, clock.instant()));
        } else {
            profile.rename(clean, key);
        }
        return toDto(profile);
    }

    @Transactional
    public void acceptTerms(String loginId, String version) {
        CommunityProfile profile = requireProfile(loginId);
        if (!TERMS_VERSION.equals(version)) throw new InvalidRequestException("이용규칙 버전이 맞지 않아요. 앱을 업데이트해 주세요");
        profile.acceptTerms(version, clock.instant());
    }

    @Transactional
    public ProfileDto setAvatar(String loginId, byte[] jpeg) {
        CommunityProfile profile = requireProfile(loginId);
        JpegInfo.read(jpeg);
        String key = "avatars/" + profile.getPublicId() + "-" + UUID.randomUUID() + ".jpg";
        storage.put(key, jpeg);
        String old = profile.getAvatarKey();
        profile.changeAvatar(key);
        if (old != null) cleanup.deleteAfterCommit(List.of(old));
        return toDto(profile);
    }

    @Transactional
    public void clearAvatar(String loginId) {
        CommunityProfile profile = requireProfile(loginId);
        String old = profile.getAvatarKey();
        profile.changeAvatar(null);
        if (old != null) cleanup.deleteAfterCommit(List.of(old));
    }

    /** 커뮤니티를 볼 수 있는가 — 닉네임과 이용규칙 동의가 있어야 한다. 정지된 사람도 읽기는 된다. */
    public CommunityProfile requireReader(String loginId) {
        CommunityProfile profile = requireProfile(loginId);
        if (!profile.hasAccepted(TERMS_VERSION)) throw new ForbiddenException("이용규칙에 먼저 동의해 주세요");
        return profile;
    }

    /** 글·댓글·좋아요·신고를 할 수 있는가 — {@link #requireReader} + 정지되지 않음. */
    public CommunityProfile requireWriter(String loginId) {
        CommunityProfile profile = requireReader(loginId);
        if (profile.isSuspended()) throw new ForbiddenException("이용이 정지된 계정이에요");
        return profile;
    }

    public boolean isAdmin(String loginId) {
        return properties.adminLoginIds().contains(loginId);
    }

    public ProfileDto toDto(CommunityProfile profile) {
        String avatar = profile.getAvatarKey();
        return new ProfileDto(profile.getPublicId(), profile.getNickname(), avatar == null ? null : storage.url(avatar));
    }

    private CommunityProfile requireProfile(String loginId) {
        return find(loginId).orElseThrow(() -> new ForbiddenException("닉네임을 먼저 정해 주세요"));
    }

    private Optional<CommunityProfile> find(String loginId) {
        return users.findByLoginId(loginId).flatMap(user -> profiles.findByUserId(user.getId()));
    }

    /** 앞뒤 공백 제거 → 보이지 않는 글자 금지 → 2~12자(코드포인트) → 금칙어. */
    private String cleanNickname(String nickname) {
        String clean = nickname == null ? "" : nickname.strip();
        boolean invisible = clean.codePoints().anyMatch(cp -> {
            int type = Character.getType(cp);
            return type == Character.CONTROL || type == Character.FORMAT;
        });
        if (invisible) throw new InvalidRequestException("닉네임에 쓸 수 없는 글자가 있어요");
        int length = clean.codePointCount(0, clean.length());
        if (length < 2 || length > 12) throw new InvalidRequestException("닉네임은 2~12자로 정해 주세요");
        if (bannedWords.contains(clean)) throw new InvalidRequestException("쓸 수 없는 표현이 있어요");
        return clean;
    }

    private String newPublicId() {
        while (true) {
            StringBuilder id = new StringBuilder(PUBLIC_ID_LENGTH);
            for (int i = 0; i < PUBLIC_ID_LENGTH; i++) id.append(PUBLIC_ID_CHARS.charAt(random.nextInt(PUBLIC_ID_CHARS.length())));
            if (!profiles.existsByPublicId(id.toString())) return id.toString();
        }
    }
}
