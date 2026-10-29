package com.trex.server.repository;

import com.trex.server.entity.CommunityProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CommunityProfileRepository extends JpaRepository<CommunityProfile, Long> {

    Optional<CommunityProfile> findByUserId(Integer userId);

    Optional<CommunityProfile> findByPublicId(String publicId);

    Optional<CommunityProfile> findByNicknameKey(String nicknameKey);

    boolean existsByPublicId(String publicId);

    List<CommunityProfile> findBySuspendedAtIsNotNullOrderBySuspendedAtDesc();
}
